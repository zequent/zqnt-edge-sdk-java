package com.zqnt.sdk.edge.missionautonomy.application.impl;

import com.zqnt.sdk.edge.application.ProtoJsonMapper;
import com.zqnt.sdk.edge.missionautonomy.application.MissionAutonomyService;
import com.zqnt.utils.mission.proto.GetSchedulerRequest;
import com.zqnt.utils.mission.proto.MissionAutonomyServiceGrpc;
import com.zqnt.utils.missionautonomy.domains.SchedulerDTO;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.*;
import java.util.function.BiConsumer;

@Slf4j
public class MissionAutonomyServiceImpl implements MissionAutonomyService {

	private static final int INITIAL_RETRY_DELAY_SECONDS = 1;
	private static final int MAX_RETRY_DELAY_SECONDS = 30;
	private static final int MAX_RETRY_ATTEMPTS = 5;

	private final MissionAutonomyServiceGrpc.MissionAutonomyServiceStub missionAutonomyServiceStub;
	private final ProtoJsonMapper protoJsonMapper;
	private final ScheduledExecutorService retryScheduler = Executors.newScheduledThreadPool(2);

	public MissionAutonomyServiceImpl(MissionAutonomyServiceGrpc.MissionAutonomyServiceStub missionAutonomyServiceStub, ProtoJsonMapper protoJsonMapper) {
		this.missionAutonomyServiceStub = missionAutonomyServiceStub;
        this.protoJsonMapper = protoJsonMapper;
    }

	private boolean shouldReconnect(Throwable t) {
		Status status = Status.fromThrowable(t);
		if (status == null) {
			return true;
		}
		return switch (status.getCode()) {
			case UNAVAILABLE,
				DEADLINE_EXCEEDED,
				RESOURCE_EXHAUSTED,
				INTERNAL,
				UNKNOWN -> true;
			case UNAUTHENTICATED,
				PERMISSION_DENIED,
				FAILED_PRECONDITION,
				UNIMPLEMENTED,
				DATA_LOSS -> false;
			default -> true;
		};
	}

	private int computeNextDelay(int attempts) {
		int next = INITIAL_RETRY_DELAY_SECONDS * (1 << Math.min(attempts - 1, 5)); // max 32x base
		next = Math.min(next, MAX_RETRY_DELAY_SECONDS);
		int jitter = ThreadLocalRandom.current().nextInt(0, Math.max(1, next / 4));
		return next + jitter;
	}

	/**
	 * Helper method to wrap gRPC async calls into CompletableFuture
	 */
	private <REQ, RES> CompletableFuture<RES> callAsync(
			REQ request,
			BiConsumer<REQ, StreamObserver<RES>> grpcMethod) {

		CompletableFuture<RES> future = new CompletableFuture<>();

		grpcMethod.accept(request, new StreamObserver<RES>() {
			private RES response;

			@Override
			public void onNext(RES value) {
				response = value;
			}

			@Override
			public void onError(Throwable t) {
				future.completeExceptionally(t);
			}

			@Override
			public void onCompleted() {
				future.complete(response);
			}
		});

		return future;
	}

	/**
	 * Helper method to wrap gRPC async calls with automatic retry on transient failures
	 */
	private <REQ, RES> CompletableFuture<RES> callAsyncWithRetry(
			REQ request,
			BiConsumer<REQ, StreamObserver<RES>> grpcMethod) {
		return callAsyncWithRetry(request, grpcMethod, 1);
	}

	private <REQ, RES> CompletableFuture<RES> callAsyncWithRetry(
			REQ request,
			BiConsumer<REQ, StreamObserver<RES>> grpcMethod,
			int attempt) {

		CompletableFuture<RES> future = new CompletableFuture<>();

		grpcMethod.accept(request, new StreamObserver<RES>() {
			private RES response;

			@Override
			public void onNext(RES value) {
				response = value;
			}

			@Override
			public void onError(Throwable t) {
				if (attempt < MAX_RETRY_ATTEMPTS && shouldReconnect(t)) {
					int nextDelay = computeNextDelay(attempt);
					log.warn("gRPC call failed (attempt {}/{}). Retrying in {}s: {}", attempt, MAX_RETRY_ATTEMPTS, nextDelay, t.getMessage());
					retryScheduler.schedule(() ->
							callAsyncWithRetry(request, grpcMethod, attempt + 1).whenComplete((res, ex) -> {
								if (ex != null) {
									future.completeExceptionally(ex);
								} else {
									future.complete(res);
								}
							}),
							nextDelay, TimeUnit.SECONDS);
				} else {
					log.error("gRPC call failed after {} attempts: {}", attempt, t.getMessage());
					future.completeExceptionally(t);
				}
			}

			@Override
			public void onCompleted() {
				future.complete(response);
			}
		});

		return future;
	}

	public void shutdown() {
		retryScheduler.shutdown();
		try {
			if (!retryScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
				retryScheduler.shutdownNow();
			}
		} catch (InterruptedException e) {
			retryScheduler.shutdownNow();
			Thread.currentThread().interrupt();
		}
	}

	// Mission/Task CRUD was retired from MissionAutonomyService in favor of the
	// capability-execution model (Application/SkillExecution); the underlying
	// gRPC methods no longer exist.

	@Override
	public CompletableFuture<SchedulerDTO> getScheduler(GetSchedulerRequest getSchedulerRequest) {
		return callAsyncWithRetry(getSchedulerRequest, missionAutonomyServiceStub::getScheduler)
				.thenApply(response -> {
					if (response.getHasErrors()) {
						log.error("Error getting scheduler: {}", response.getError());
						return null;
					}
					return protoJsonMapper.map(response.getScheduler());
				})
				.exceptionally(t -> {
					log.error("Error getting scheduler", t);
					return null;
				});
	}
}
