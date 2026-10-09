package com.zqnt.sdk.edge.telemetry;

import com.zqnt.protos.telemetry.v3.Alert;
import com.zqnt.protos.telemetry.v3.DetectionBatch;
import com.zqnt.protos.telemetry.v3.PublishAlertsRequest;
import com.zqnt.protos.telemetry.v3.PublishAlertsResponse;
import com.zqnt.protos.telemetry.v3.PublishDetectionsRequest;
import com.zqnt.protos.telemetry.v3.PublishDetectionsResponse;
import com.zqnt.protos.telemetry.v3.PublishTelemetryRequest;
import com.zqnt.protos.telemetry.v3.PublishTelemetryResponse;
import com.zqnt.protos.telemetry.v3.TelemetryIngestServiceGrpc;
import com.zqnt.protos.telemetry.v3.TelemetrySample;
import com.zqnt.sdk.edge.gateway.V3Availability;
import com.zqnt.sdk.edge.livedata.application.LiveDataService;
import com.zqnt.utils.core.ProtobufHelpers;
import io.grpc.Channel;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Publishes v3 live data over the platform's {@code zqnt.telemetry.v3.TelemetryIngestService}
 * (live-data, the endpoint of v2 {@code LiveDataService}): one long-lived client stream each for
 * samples, detections and alerts, shared by every asset of the adapter, reopened with capped
 * exponential backoff after an error.
 *
 * <p>Against a platform without v3 (UNIMPLEMENTED) samples and detections go through the v2
 * {@link LiveDataService} instead, for ten minutes before v3 is tried again. v2 has no place for
 * them, so a sample's {@code details} and every alert are dropped there; only the shared fields
 * (position, altitudes, heading, speeds, battery) arrive. Items sent on a stream that is just
 * failing can be lost, as on any stream.
 *
 * <p>The asset's serial is required; {@code observed_at}/{@code occurred_at} is set to now when missing.
 */
@Slf4j
public class TelemetryPublisher implements AutoCloseable {

	private static final long INITIAL_BACKOFF_MILLIS = 1_000;
	private static final long MAX_BACKOFF_MILLIS = 60_000;
	private static final long STABLE_STREAM_MILLIS = 30_000;

	private final LiveDataService v2;
	private final V3Availability availability;
	private final IngestStream<PublishTelemetryRequest, PublishTelemetryResponse> samples;
	private final IngestStream<PublishDetectionsRequest, PublishDetectionsResponse> detections;
	private final IngestStream<PublishAlertsRequest, PublishAlertsResponse> alerts;

	/** {@code liveDataChannel}: the channel to live-data, with the edge credential interceptor. */
	public TelemetryPublisher(Channel liveDataChannel, LiveDataService v2) {
		this(TelemetryIngestServiceGrpc.newStub(liveDataChannel), v2,
				new V3Availability("zqnt.telemetry.v3.TelemetryIngestService"), System::currentTimeMillis);
	}

	public TelemetryPublisher(TelemetryIngestServiceGrpc.TelemetryIngestServiceStub stub, LiveDataService v2,
			V3Availability availability, LongSupplier clock) {
		this.v2 = v2;
		this.availability = availability;
		this.samples = new IngestStream<>("telemetry", stub::publishTelemetry, clock);
		this.detections = new IngestStream<>("detections", stub::publishDetections, clock);
		this.alerts = new IngestStream<>("alerts", stub::publishAlerts, clock);
	}

	public CompletableFuture<Void> publish(TelemetrySample sample) {
		if (sample.getAsset().getSn().isBlank()) {
			return CompletableFuture.failedFuture(new IllegalArgumentException("A telemetry sample needs asset.sn"));
		}
		TelemetrySample complete = sample.hasObservedAt() ? sample : sample.toBuilder().setObservedAt(ProtobufHelpers.now()).build();
		if (!availability.available()) {
			return v2 == null ? dropped("telemetry") : v2.produceTelemetry(complete.getAsset().getSn(), V2Telemetry.toV2(complete));
		}
		return samples.send(PublishTelemetryRequest.newBuilder().setSample(complete).build());
	}

	public CompletableFuture<Void> publish(DetectionBatch batch) {
		if (batch.getAsset().getSn().isBlank()) {
			return CompletableFuture.failedFuture(new IllegalArgumentException("A detection batch needs asset.sn"));
		}
		DetectionBatch complete = batch.hasObservedAt() ? batch : batch.toBuilder().setObservedAt(ProtobufHelpers.now()).build();
		if (!availability.available()) {
			return v2 == null ? dropped("detections") : v2.produceDetection(complete.getAsset().getSn(), V2Telemetry.toV2(complete));
		}
		return detections.send(PublishDetectionsRequest.newBuilder().setBatch(complete).build());
	}

	public CompletableFuture<Void> publish(Alert alert) {
		if (alert.getAsset().getSn().isBlank() || alert.getCode().isBlank()) {
			return CompletableFuture.failedFuture(new IllegalArgumentException("An alert needs asset.sn and a code"));
		}
		Alert complete = alert.hasOccurredAt() ? alert : alert.toBuilder().setOccurredAt(ProtobufHelpers.now()).build();
		if (!availability.available()) {
			return dropped("alerts");
		}
		return alerts.send(PublishAlertsRequest.newBuilder().setAlert(complete).build());
	}

	public boolean v3Available() {
		return availability.available();
	}

	@Override
	public void close() {
		samples.close();
		detections.close();
		alerts.close();
	}

	private static CompletableFuture<Void> dropped(String kind) {
		log.debug("v2 has no place for {} without the v2 live data service; dropped", kind);
		return CompletableFuture.completedFuture(null);
	}

	private final class IngestStream<T, R> {
		private final String kind;
		private final Function<StreamObserver<R>, StreamObserver<T>> opener;
		private final LongSupplier clock;
		private StreamObserver<T> requests;
		private int failures;
		private long openedAt;
		private long reopenAt;

		IngestStream(String kind, Function<StreamObserver<R>, StreamObserver<T>> opener, LongSupplier clock) {
			this.kind = kind;
			this.opener = opener;
			this.clock = clock;
		}

		synchronized CompletableFuture<Void> send(T item) {
			if (requests == null) {
				if (clock.getAsLong() < reopenAt) {
					return CompletableFuture.failedFuture(new IllegalStateException(
							"v3 " + kind + " stream is reconnecting"));
				}
				open();
			}
			try {
				requests.onNext(item);
				return CompletableFuture.completedFuture(null);
			} catch (RuntimeException e) {
				failed(requests, e);
				return CompletableFuture.failedFuture(e);
			}
		}

		private void open() {
			AtomicReference<StreamObserver<T>> self = new AtomicReference<>();
			self.set(opener.apply(new StreamObserver<>() {
				@Override
				public void onNext(R response) {
					log.debug("v3 {} stream answered {}", kind, response);
				}

				@Override
				public void onError(Throwable t) {
					failed(self.get(), t);
				}

				@Override
				public void onCompleted() {
					closed(self.get());
				}
			}));
			requests = self.get();
			openedAt = clock.getAsLong();
		}

		private synchronized void failed(StreamObserver<T> stream, Throwable error) {
			if (stream != requests || requests == null) {
				return;
			}
			requests = null;
			if (V3Availability.isUnimplemented(error)) {
				availability.markUnavailable();
				failures = 0;
				reopenAt = 0;
				return;
			}
			failures = clock.getAsLong() - openedAt > STABLE_STREAM_MILLIS ? 1 : failures + 1;
			long backoff = Math.min(INITIAL_BACKOFF_MILLIS << Math.min(failures - 1, 10), MAX_BACKOFF_MILLIS);
			reopenAt = clock.getAsLong() + backoff + ThreadLocalRandom.current().nextLong(Math.max(1, backoff / 4));
			log.warn("v3 {} stream failed ({}); reopening in {} ms", kind, Status.fromThrowable(error).getCode(), backoff);
		}

		private synchronized void closed(StreamObserver<T> stream) {
			if (stream == requests) {
				requests = null;
			}
		}

		synchronized void close() {
			if (requests != null) {
				try {
					requests.onCompleted();
				} catch (RuntimeException e) {
					log.debug("Closing the v3 {} stream: {}", kind, e.getMessage());
				}
				requests = null;
			}
		}
	}
}
