package com.zqnt.sdk.edge.livedata.application.impl;

import com.zqnt.sdk.edge.adapter.domains.DetectionRequestData;
import com.zqnt.sdk.edge.adapter.domains.NotificationRequestData;
import com.zqnt.sdk.edge.adapter.domains.TelemetryRequestData;
import com.zqnt.sdk.edge.gateway.CommandEventMapper;
import com.zqnt.sdk.edge.gateway.CommandExecutions;
import com.zqnt.sdk.edge.gateway.EdgeGatewayClient;
import com.zqnt.sdk.edge.gateway.V3Availability;
import com.zqnt.sdk.edge.livedata.application.DetectionMapper;
import com.zqnt.sdk.edge.livedata.application.LiveDataService;
import com.zqnt.sdk.edge.livedata.application.NotificationMapper;
import com.zqnt.sdk.edge.livedata.application.TelemetryMapper;
import com.zqnt.protos.capability.v3.CommandEvent;
import com.zqnt.utils.common.proto.DetectionBatch;
import com.zqnt.utils.events.proto.CommandExecutionEvent;
import com.zqnt.utils.events.proto.ProduceNotificationRequest;
import com.zqnt.utils.livedata.proto.LiveDataResponse;
import com.zqnt.utils.livedata.proto.LiveDataServiceGrpc;
import com.zqnt.utils.livedata.proto.ProduceTelemetryRequest;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Standard gRPC implementation of {@link LiveDataService}. A stream is installed in its
 * device map before the RPC is opened, so even an immediate asynchronous transport failure
 * can only remove its own stream generation. Reconnects continue indefinitely with capped
 * exponential backoff.
 *
 * <p>With an {@link EdgeGatewayClient}, a notification carrying a command execution event goes to the
 * platform as a v3 {@code PublishCommandEvent} under the platform's {@code command_execution_id};
 * against a platform without v3 it takes the v2 notification stream as before. Either way the event
 * carries {@code occurred_at} (now, when the adapter gave none).
 */
@Slf4j
public class LiveDataServiceImpl implements LiveDataService {

	private static final int INITIAL_RECONNECT_DELAY_SECONDS = 2;
	private static final int MAX_RECONNECT_DELAY_SECONDS = 60;
	private static final int STABLE_STREAM_SECONDS = 10;

	private final TelemetryMapper telemetryMapper;
	private final DetectionMapper detectionMapper;
	private final NotificationMapper notificationMapper;
	private final LiveDataServiceGrpc.LiveDataServiceStub liveDataServiceStub;
	private final EdgeGatewayClient gateway;
	private final CommandExecutions executions;

	private final Map<String, StreamState<ProduceTelemetryRequest>> activeStreams = new ConcurrentHashMap<>();
	private final Map<String, StreamState<DetectionBatch>> activeDetectionStreams = new ConcurrentHashMap<>();
	private final Map<String, StreamState<ProduceNotificationRequest>> activeNotificationStreams = new ConcurrentHashMap<>();

	private final Map<String, AtomicInteger> reconnectAttempts = new ConcurrentHashMap<>();
	private final Map<String, AtomicInteger> detectionReconnectAttempts = new ConcurrentHashMap<>();
	private final Map<String, AtomicInteger> notificationReconnectAttempts = new ConcurrentHashMap<>();

	private final Set<String> pendingReconnects = ConcurrentHashMap.newKeySet();
	private final Set<String> pendingDetectionReconnects = ConcurrentHashMap.newKeySet();
	private final Set<String> pendingNotificationReconnects = ConcurrentHashMap.newKeySet();

	/* Explicit close suppresses a pending reconnect until the next produce call for that device. */
	private final Set<String> closedStreams = ConcurrentHashMap.newKeySet();
	private final Set<String> closedDetectionStreams = ConcurrentHashMap.newKeySet();
	private final Set<String> closedNotificationStreams = ConcurrentHashMap.newKeySet();

	private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
	private final ScheduledExecutorService reconnectScheduler;
	private final int initialReconnectDelaySeconds;
	private final int maxReconnectDelaySeconds;

	public LiveDataServiceImpl(TelemetryMapper telemetryMapper,
							   DetectionMapper detectionMapper,
							   NotificationMapper notificationMapper,
							   LiveDataServiceGrpc.LiveDataServiceStub liveDataServiceStub) {
		this(telemetryMapper, detectionMapper, notificationMapper, liveDataServiceStub, null);
	}

	/** {@code gateway}: where command events go over v3; null keeps them on the v2 notification stream. */
	public LiveDataServiceImpl(TelemetryMapper telemetryMapper,
							   DetectionMapper detectionMapper,
							   NotificationMapper notificationMapper,
							   LiveDataServiceGrpc.LiveDataServiceStub liveDataServiceStub,
							   EdgeGatewayClient gateway) {
		this(telemetryMapper, detectionMapper, notificationMapper, liveDataServiceStub, gateway,
				CommandExecutions.shared(), Executors.newSingleThreadScheduledExecutor(),
				INITIAL_RECONNECT_DELAY_SECONDS, MAX_RECONNECT_DELAY_SECONDS);
	}

	LiveDataServiceImpl(TelemetryMapper telemetryMapper,
						DetectionMapper detectionMapper,
						NotificationMapper notificationMapper,
						LiveDataServiceGrpc.LiveDataServiceStub liveDataServiceStub,
						ScheduledExecutorService reconnectScheduler,
						int initialReconnectDelaySeconds,
						int maxReconnectDelaySeconds) {
		this(telemetryMapper, detectionMapper, notificationMapper, liveDataServiceStub, null,
				CommandExecutions.shared(), reconnectScheduler, initialReconnectDelaySeconds, maxReconnectDelaySeconds);
	}

	LiveDataServiceImpl(TelemetryMapper telemetryMapper,
						DetectionMapper detectionMapper,
						NotificationMapper notificationMapper,
						LiveDataServiceGrpc.LiveDataServiceStub liveDataServiceStub,
						EdgeGatewayClient gateway,
						CommandExecutions executions,
						ScheduledExecutorService reconnectScheduler,
						int initialReconnectDelaySeconds,
						int maxReconnectDelaySeconds) {
		this.telemetryMapper = telemetryMapper;
		this.detectionMapper = detectionMapper;
		this.notificationMapper = notificationMapper;
		this.liveDataServiceStub = liveDataServiceStub;
		this.gateway = gateway;
		this.executions = executions;
		this.reconnectScheduler = reconnectScheduler;
		this.initialReconnectDelaySeconds = initialReconnectDelaySeconds;
		this.maxReconnectDelaySeconds = maxReconnectDelaySeconds;
	}

	/** Cleanup method - call this when shutting down. */
	public void shutdown() {
		if (!shuttingDown.compareAndSet(false, true)) {
			return;
		}
		log.info("LiveDataService shutdown initiated, cleaning up gRPC streams");
		closeAllStreams();
		reconnectScheduler.shutdown();
		try {
			if (!reconnectScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
				reconnectScheduler.shutdownNow();
			}
		} catch (InterruptedException e) {
			reconnectScheduler.shutdownNow();
			Thread.currentThread().interrupt();
		}
	}

	// -------------------------------------------------------------------------
	// Detection
	// -------------------------------------------------------------------------

	@Override
	public CompletableFuture<Void> produceDetectionData(DetectionRequestData requestData) {
		if (requestData == null) {
			return CompletableFuture.completedFuture(null);
		}
		return produceDetection(requestData.getSn(), detectionMapper.map(requestData));
	}

	@Override
	public CompletableFuture<Void> produceDetection(String deviceSn, DetectionBatch detectionBatch) {
		closedDetectionStreams.remove(deviceSn);
		return send(deviceSn, detectionBatch, "detection", activeDetectionStreams,
				detectionReconnectAttempts, pendingDetectionReconnects, closedDetectionStreams,
				liveDataServiceStub::produceDetection);
	}

	@Override
	public CompletableFuture<Void> closeDetectionStream(String deviceSn) {
		return closeStream(deviceSn, "detection", activeDetectionStreams, closedDetectionStreams);
	}

	// -------------------------------------------------------------------------
	// Notification
	// -------------------------------------------------------------------------

	@Override
	public CompletableFuture<Void> produceNotificationData(NotificationRequestData requestData) {
		if (requestData == null) {
			return CompletableFuture.completedFuture(null);
		}
		return produceNotification(requestData.getSn(), notificationMapper.map(requestData));
	}

	@Override
	public CompletableFuture<Void> produceNotification(String deviceSn, ProduceNotificationRequest notificationRequest) {
		if (notificationRequest != null && notificationRequest.getEvent().hasCommandExecution()) {
			return produceCommandEvent(deviceSn, notificationRequest);
		}
		return sendNotification(deviceSn, notificationRequest);
	}

	private CompletableFuture<Void> produceCommandEvent(String deviceSn, ProduceNotificationRequest request) {
		CommandExecutionEvent event = CommandEventMapper.withOccurredAt(request.getEvent().getCommandExecution());
		ProduceNotificationRequest v2 = request.toBuilder()
				.setEvent(request.getEvent().toBuilder().setCommandExecution(event)).build();
		CommandEvent v3 = CommandEventMapper.toV3(event, deviceSn, executions);
		CompletableFuture<Void> sent;
		if (gateway == null || !gateway.commandEventsAvailable()) {
			sent = sendNotification(deviceSn, v2);
		} else {
			sent = gateway.publishCommandEvent(v3)
					.handle((ignored, error) -> {
						if (error == null) return CompletableFuture.<Void>completedFuture(null);
						if (V3Availability.isUnimplemented(error)) return sendNotification(deviceSn, v2);
						log.warn("v3 command event {} for {} failed: {}", v3.getCommandExecutionId(), deviceSn, error.getMessage());
						return CompletableFuture.<Void>failedFuture(error);
					})
					.thenCompose(future -> future);
		}
		return sent.thenRun(() -> {
			if (CommandEventMapper.isTerminal(v3.getState())) executions.finished(v3.getCommandExecutionId());
		});
	}

	private CompletableFuture<Void> sendNotification(String deviceSn, ProduceNotificationRequest notificationRequest) {
		closedNotificationStreams.remove(deviceSn);
		return send(deviceSn, notificationRequest, "notification", activeNotificationStreams,
				notificationReconnectAttempts, pendingNotificationReconnects, closedNotificationStreams,
				liveDataServiceStub::produceNotification);
	}

	@Override
	public CompletableFuture<Void> closeNotificationStream(String deviceSn) {
		return closeStream(deviceSn, "notification", activeNotificationStreams, closedNotificationStreams);
	}

	// -------------------------------------------------------------------------
	// Telemetry
	// -------------------------------------------------------------------------

	@Override
	public CompletableFuture<Void> produceTelemetryData(TelemetryRequestData requestData) {
		if (requestData == null) {
			return CompletableFuture.completedFuture(null);
		}
		return produceTelemetry(requestData.getSn(), telemetryMapper.map(requestData));
	}

	@Override
	public CompletableFuture<Void> produceTelemetry(String deviceSn, ProduceTelemetryRequest telemetryRequest) {
		closedStreams.remove(deviceSn);
		return send(deviceSn, telemetryRequest, "telemetry", activeStreams, reconnectAttempts,
				pendingReconnects, closedStreams, liveDataServiceStub::produceTelemetry);
	}

	@Override
	public CompletableFuture<Void> closeStream(String deviceSn) {
		return closeStream(deviceSn, "telemetry", activeStreams, closedStreams);
	}

	@Override
	public CompletableFuture<Void> closeAllStreams() {
		log.info("Closing all streams (telemetry: {}, detection: {}, notification: {})",
				activeStreams.size(), activeDetectionStreams.size(), activeNotificationStreams.size());

		closedStreams.addAll(activeStreams.keySet());
		closedStreams.addAll(pendingReconnects);
		closedDetectionStreams.addAll(activeDetectionStreams.keySet());
		closedDetectionStreams.addAll(pendingDetectionReconnects);
		closedNotificationStreams.addAll(activeNotificationStreams.keySet());
		closedNotificationStreams.addAll(pendingNotificationReconnects);

		closeStreams("telemetry", activeStreams);
		closeStreams("detection", activeDetectionStreams);
		closeStreams("notification", activeNotificationStreams);
		return CompletableFuture.completedFuture(null);
	}

	private <T> CompletableFuture<Void> send(
			String deviceSn,
			T request,
			String streamType,
			Map<String, StreamState<T>> active,
			Map<String, AtomicInteger> attempts,
			Set<String> pending,
			Set<String> explicitlyClosed,
			StreamFactory<T> factory) {

		if (shuttingDown.get()) {
			log.warn("Cannot produce {} for device {} - service is shutting down", streamType, deviceSn);
			return CompletableFuture.completedFuture(null);
		}

		StreamState<T> state = getOrCreateStream(deviceSn, streamType, active, attempts, pending,
				explicitlyClosed, factory);
		if (state == null) {
			return CompletableFuture.failedFuture(new IllegalStateException(
					"Could not create " + streamType + " stream for device " + deviceSn));
		}

		synchronized (state) {
			if (state.terminated || state.requestObserver == null) {
				Throwable failure = state.failure != null ? state.failure
						: new IllegalStateException(streamType + " stream is not available");
				return CompletableFuture.failedFuture(failure);
			}
			try {
				state.requestObserver.onNext(request);
				return CompletableFuture.completedFuture(null);
			} catch (RuntimeException e) {
				terminate(deviceSn, streamType, state, active, attempts, pending, explicitlyClosed, factory, e);
				return CompletableFuture.failedFuture(e);
			}
		}
	}

	private <T> StreamState<T> getOrCreateStream(
			String deviceSn,
			String streamType,
			Map<String, StreamState<T>> active,
			Map<String, AtomicInteger> attempts,
			Set<String> pending,
			Set<String> explicitlyClosed,
			StreamFactory<T> factory) {

		if (shuttingDown.get() || explicitlyClosed.contains(deviceSn)) {
			return null;
		}

		StreamState<T> candidate = new StreamState<>();
		StreamState<T> state = active.putIfAbsent(deviceSn, candidate);
		if (state != null) {
			return state;
		}
		if (shuttingDown.get() || explicitlyClosed.contains(deviceSn)) {
			active.remove(deviceSn, candidate);
			return null;
		}

		state = candidate;
		log.info("Creating gRPC {} stream for device {}", streamType, deviceSn);
		synchronized (state) {
			try {
				StreamState<T> openedState = state;
				StreamObserver<LiveDataResponse> responseObserver = new StreamObserver<>() {
					@Override
					public void onNext(LiveDataResponse response) {
						attempts.remove(deviceSn);
						log.debug("{} response received for device {}", streamType, deviceSn);
					}

					@Override
					public void onError(Throwable t) {
						log.warn("gRPC {} stream error for device {}: {}", streamType, deviceSn,
								t.getMessage(), t);
						terminate(deviceSn, streamType, openedState, active, attempts, pending,
								explicitlyClosed, factory, t);
					}

					@Override
					public void onCompleted() {
						log.info("gRPC {} stream completed by server for device {}", streamType, deviceSn);
						terminate(deviceSn, streamType, openedState, active, attempts, pending,
								explicitlyClosed, factory, null);
					}
				};
				state.requestObserver = factory.open(responseObserver);
			} catch (RuntimeException e) {
				terminate(deviceSn, streamType, state, active, attempts, pending, explicitlyClosed, factory, e);
			}
		}

		if (!state.terminated) {
			scheduleAttemptReset(deviceSn, state, active, attempts);
		}
		return state;
	}

	private <T> void terminate(
			String deviceSn,
			String streamType,
			StreamState<T> state,
			Map<String, StreamState<T>> active,
			Map<String, AtomicInteger> attempts,
			Set<String> pending,
			Set<String> explicitlyClosed,
			StreamFactory<T> factory,
			Throwable failure) {

		synchronized (state) {
			if (state.terminated) {
				return;
			}
			state.terminated = true;
			state.failure = failure;
		}

		/* A callback from an older generation must never remove or reconnect a newer stream. */
		if (!active.remove(deviceSn, state)) {
			return;
		}
		if (failure == null || shouldReconnect(failure)) {
			scheduleReconnect(deviceSn, streamType, active, attempts, pending, explicitlyClosed, factory);
		} else {
			log.error("gRPC {} stream for device {} failed permanently with status {}", streamType,
					deviceSn, Status.fromThrowable(failure).getCode());
		}
	}

	private <T> void scheduleReconnect(
			String deviceSn,
			String streamType,
			Map<String, StreamState<T>> active,
			Map<String, AtomicInteger> attempts,
			Set<String> pending,
			Set<String> explicitlyClosed,
			StreamFactory<T> factory) {

		if (shuttingDown.get() || explicitlyClosed.contains(deviceSn) || !pending.add(deviceSn)) {
			return;
		}

		int attempt = attempts.computeIfAbsent(deviceSn, ignored -> new AtomicInteger()).incrementAndGet();
		int delaySeconds = computeNextDelay(attempt);
		log.info("Scheduling {} reconnection for device {} in {} seconds (attempt {})",
				streamType, deviceSn, delaySeconds, attempt);

		try {
			reconnectScheduler.schedule(() -> {
				pending.remove(deviceSn);
				if (shuttingDown.get() || explicitlyClosed.contains(deviceSn) || active.containsKey(deviceSn)) {
					return;
				}
				log.info("Attempting to reconnect gRPC {} stream for device {}", streamType, deviceSn);
				getOrCreateStream(deviceSn, streamType, active, attempts, pending, explicitlyClosed, factory);
			}, delaySeconds, TimeUnit.SECONDS);
		} catch (RejectedExecutionException e) {
			pending.remove(deviceSn);
			if (!shuttingDown.get()) {
				log.error("Could not schedule {} reconnect for device {}", streamType, deviceSn, e);
			}
		}
	}

	private <T> void scheduleAttemptReset(String deviceSn, StreamState<T> state,
									  Map<String, StreamState<T>> active,
									  Map<String, AtomicInteger> attempts) {
		try {
			reconnectScheduler.schedule(() -> {
				if (active.get(deviceSn) == state && !state.terminated) {
					attempts.remove(deviceSn);
				}
			}, STABLE_STREAM_SECONDS, TimeUnit.SECONDS);
		} catch (RejectedExecutionException ignored) {
			// Shutdown won the race; no retry state needs to be reset.
		}
	}

	private boolean shouldReconnect(Throwable throwable) {
		return switch (Status.fromThrowable(throwable).getCode()) {
			case UNAUTHENTICATED, PERMISSION_DENIED, FAILED_PRECONDITION, UNIMPLEMENTED, DATA_LOSS -> false;
			default -> true;
		};
	}

	private int computeNextDelay(int attempt) {
		int exponent = Math.min(Math.max(0, attempt - 1), 6);
		int cappedDelay = Math.min(initialReconnectDelaySeconds * (1 << exponent), maxReconnectDelaySeconds);
		int jitterBound = Math.max(1, cappedDelay / 4);
		return cappedDelay + ThreadLocalRandom.current().nextInt(jitterBound);
	}

	private <T> CompletableFuture<Void> closeStream(String deviceSn, String streamType,
											Map<String, StreamState<T>> active, Set<String> explicitlyClosed) {
		explicitlyClosed.add(deviceSn);
		StreamState<T> state = active.remove(deviceSn);
		closeState(deviceSn, streamType, state);
		return CompletableFuture.completedFuture(null);
	}

	private <T> void closeStreams(String streamType, Map<String, StreamState<T>> active) {
		active.forEach((deviceSn, state) -> {
			if (active.remove(deviceSn, state)) {
				closeState(deviceSn, streamType, state);
			}
		});
	}

	private <T> void closeState(String deviceSn, String streamType, StreamState<T> state) {
		if (state == null) {
			return;
		}
		synchronized (state) {
			state.terminated = true;
			if (state.requestObserver != null) {
				try {
					state.requestObserver.onCompleted();
					log.info("Closed {} stream for device {}", streamType, deviceSn);
				} catch (RuntimeException e) {
					log.warn("Error closing {} stream for device {}: {}", streamType, deviceSn, e.getMessage());
				}
			}
		}
	}

	@FunctionalInterface
	private interface StreamFactory<T> {
		StreamObserver<T> open(StreamObserver<LiveDataResponse> responseObserver);
	}

	private static final class StreamState<T> {
		private StreamObserver<T> requestObserver;
		private volatile boolean terminated;
		private Throwable failure;
	}
}
