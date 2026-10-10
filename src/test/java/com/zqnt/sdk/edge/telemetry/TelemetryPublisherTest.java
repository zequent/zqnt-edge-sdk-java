package com.zqnt.sdk.edge.telemetry;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.zqnt.protos.common.v3.AssetRef;
import com.zqnt.protos.common.v3.GeoPoint;
import com.zqnt.protos.edge.v3.Detection;
import com.zqnt.protos.telemetry.v3.Alert;
import com.zqnt.protos.telemetry.v3.AlertSeverity;
import com.zqnt.protos.telemetry.v3.DetectionBatch;
import com.zqnt.protos.telemetry.v3.PublishAlertsRequest;
import com.zqnt.protos.telemetry.v3.PublishAlertsResponse;
import com.zqnt.protos.telemetry.v3.PublishDetectionsRequest;
import com.zqnt.protos.telemetry.v3.PublishDetectionsResponse;
import com.zqnt.protos.telemetry.v3.PublishTelemetryRequest;
import com.zqnt.protos.telemetry.v3.PublishTelemetryResponse;
import com.zqnt.protos.telemetry.v3.TelemetryIngestServiceGrpc;
import com.zqnt.protos.telemetry.v3.TelemetrySample;
import com.zqnt.sdk.edge.livedata.application.impl.LiveDataServiceImpl;
import com.zqnt.sdk.edge.testing.TestGrpc;
import com.zqnt.utils.livedata.proto.LiveDataResponse;
import com.zqnt.utils.livedata.proto.LiveDataServiceGrpc;
import com.zqnt.utils.livedata.proto.ProduceTelemetryRequest;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.zqnt.sdk.edge.testing.TestGrpc.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * v3 live data goes over one long-lived TelemetryIngestService stream per kind; an older platform
 * gets the shared fields over v2 instead.
 */
class TelemetryPublisherTest {

	private final List<TelemetrySample> samples = TestGrpc.list();
	private final List<DetectionBatch> detections = TestGrpc.list();
	private final List<Alert> alerts = TestGrpc.list();
	private final List<ProduceTelemetryRequest> v2Telemetry = TestGrpc.list();
	private final List<com.zqnt.utils.common.proto.DetectionBatch> v2Detections = TestGrpc.list();
	private final AtomicInteger telemetryStreams = new AtomicInteger();
	private TestGrpc grpc;
	private TelemetryPublisher publisher;
	private LiveDataServiceImpl v2;

	private final class Ingest extends TelemetryIngestServiceGrpc.TelemetryIngestServiceImplBase {
		@Override
		public StreamObserver<PublishTelemetryRequest> publishTelemetry(StreamObserver<PublishTelemetryResponse> observer) {
			telemetryStreams.incrementAndGet();
			return forward(request -> samples.add(request.getSample()));
		}

		@Override
		public StreamObserver<PublishDetectionsRequest> publishDetections(StreamObserver<PublishDetectionsResponse> observer) {
			return forward(request -> detections.add(request.getBatch()));
		}

		@Override
		public StreamObserver<PublishAlertsRequest> publishAlerts(StreamObserver<PublishAlertsResponse> observer) {
			return forward(request -> alerts.add(request.getAlert()));
		}

		private <T> StreamObserver<T> forward(java.util.function.Consumer<T> sink) {
			return new StreamObserver<>() {
				@Override
				public void onNext(T value) {
					sink.accept(value);
				}

				@Override
				public void onError(Throwable t) {
				}

				@Override
				public void onCompleted() {
				}
			};
		}
	}

	private final class LiveData extends LiveDataServiceGrpc.LiveDataServiceImplBase {
		@Override
		public StreamObserver<ProduceTelemetryRequest> produceTelemetry(StreamObserver<LiveDataResponse> observer) {
			return TestGrpc.recording(v2Telemetry);
		}

		@Override
		public StreamObserver<com.zqnt.utils.common.proto.DetectionBatch> produceDetection(StreamObserver<LiveDataResponse> observer) {
			return TestGrpc.recording(v2Detections);
		}
	}

	@AfterEach
	void tearDown() throws InterruptedException {
		if (publisher != null) publisher.close();
		if (v2 != null) v2.shutdown();
		if (grpc != null) grpc.close();
	}

	private void connect() {
		v2 = new LiveDataServiceImpl(null, null, null, LiveDataServiceGrpc.newStub(grpc.channel()));
		publisher = new TelemetryPublisher(grpc.channel(), v2);
	}

	private static TelemetrySample sample(String sn) {
		return TelemetrySample.newBuilder()
				.setAsset(AssetRef.newBuilder().setSn(sn))
				.setPosition(GeoPoint.newBuilder().setLatitude(52.5).setLongitude(13.4).setAltitude(80))
				.setRelativeAltitude(40)
				.setHeadingDegrees(90)
				.setHorizontalSpeed(12)
				.setBatteryPercent(76)
				.setDetails(Struct.newBuilder().putFields("drone.gear", Value.newBuilder().setNumberValue(1).build()))
				.build();
	}

	@Test
	void everyAssetSharesOneStreamPerKind() throws Exception {
		grpc = TestGrpc.serving(new Ingest(), new LiveData());
		connect();

		publisher.publish(sample("SN-1")).get();
		publisher.publish(sample("SN-2")).get();
		publisher.publish(DetectionBatch.newBuilder().setAsset(AssetRef.newBuilder().setSn("SN-1"))
				.addDetections(Detection.newBuilder().setObjectType("person").setConfidence(0.9f)).build()).get();
		publisher.publish(Alert.newBuilder().setAsset(AssetRef.newBuilder().setSn("SN-1"))
				.setSeverity(AlertSeverity.ALERT_SEVERITY_WARNING).setCode("dock.rain").setMessage("Rain").build()).get();

		await(() -> samples.size() == 2 && detections.size() == 1 && alerts.size() == 1);
		assertEquals(1, telemetryStreams.get());
		assertTrue(samples.get(0).hasObservedAt(), "observed_at is filled in");
		assertEquals(1.0, samples.get(0).getDetails().getFieldsOrThrow("drone.gear").getNumberValue());
		assertTrue(detections.get(0).hasObservedAt());
		assertTrue(alerts.get(0).hasOccurredAt());
		assertTrue(v2Telemetry.isEmpty());
	}

	@Test
	void anOlderPlatformGetsTheSharedFieldsOverV2() throws Exception {
		grpc = TestGrpc.serving(new LiveData());
		connect();

		publisher.publish(sample("SN-1")).get();
		await(() -> !publisher.v3Available());
		publisher.publish(sample("SN-1")).get();
		publisher.publish(DetectionBatch.newBuilder().setAsset(AssetRef.newBuilder().setSn("SN-1"))
				.addDetections(Detection.newBuilder().setObjectType("car")).build()).get();
		publisher.publish(Alert.newBuilder().setAsset(AssetRef.newBuilder().setSn("SN-1")).setCode("dock.rain").build()).get();

		await(() -> v2Telemetry.size() == 1 && v2Detections.size() == 1);
		var telemetry = v2Telemetry.get(0);
		assertEquals("SN-1", telemetry.getBase().getSn());
		assertEquals(52.5, telemetry.getData().getLatitude());
		assertEquals(80f, telemetry.getData().getAbsoluteAltitude());
		assertEquals(40f, telemetry.getData().getRelativeAltitude());
		assertEquals(12f, telemetry.getData().getSubAsset().getHorizontalSpeed());
		assertEquals("76", telemetry.getData().getSubAsset().getBatteryInformation().getPercentage());
		assertEquals("car", v2Detections.get(0).getDetections(0).getObjectType());
	}

	@Test
	void anItemWithoutItsAssetIsRefused() throws Exception {
		grpc = TestGrpc.serving(new Ingest());
		connect();

		assertTrue(publisher.publish(TelemetrySample.getDefaultInstance()).isCompletedExceptionally());
		assertTrue(publisher.publish(Alert.newBuilder().setAsset(AssetRef.newBuilder().setSn("SN-1")).build())
				.isCompletedExceptionally());
	}

	@Test
	void aBatteryWithoutMovementIsTheAssetsOwnInV2() {
		var v2Sample = V2Telemetry.toV2(TelemetrySample.newBuilder().setAsset(AssetRef.newBuilder().setSn("DOCK-1"))
				.setBatteryPercent(55.5).build());

		assertEquals(55.5f, v2Sample.getData().getAsset().getSubAssetPercentage());
		assertFalse(v2Sample.getData().hasSubAsset());
	}
}
