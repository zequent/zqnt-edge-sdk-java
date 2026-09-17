package com.zqnt.sdk.edge.connector.application.impl;

import com.zqnt.sdk.edge.application.ProtoJsonMapper;
import com.zqnt.sdk.edge.connector.application.ConnectorService;
import com.zqnt.utils.asset.domains.AssetDTO;
import com.zqnt.utils.asset.domains.AssetPayloadDTO;
import com.zqnt.utils.asset.domains.SubAssetDTO;
import com.zqnt.utils.common.proto.RequestBase;
import com.zqnt.utils.connector.proto.*;
import com.zqnt.utils.core.ProtobufHelpers;
import com.zqnt.utils.mission.proto.CreateSchedulerRequest;
import com.zqnt.utils.mission.proto.DeleteSchedulerRequest;
import com.zqnt.utils.mission.proto.GetSchedulerRequest;
import com.zqnt.utils.mission.proto.UpdateSchedulerRequest;
import com.zqnt.utils.missionautonomy.domains.OrganizationDTO;
import com.zqnt.utils.missionautonomy.domains.SchedulerDTO;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

@Slf4j
public class ConnectorServiceImpl implements ConnectorService {

	private static final int INITIAL_RETRY_DELAY_SECONDS = 1;
	private static final int MAX_RETRY_DELAY_SECONDS = 30;
	private static final int MAX_RETRY_ATTEMPTS = 5;

	private final ProtoJsonMapper protoJsonMapper;
	private final ConnectorServiceGrpc.ConnectorServiceStub connectorServiceStub;
	private final ScheduledExecutorService retryScheduler = Executors.newScheduledThreadPool(2);

	public ConnectorServiceImpl(ProtoJsonMapper protoJsonMapper, ConnectorServiceGrpc.ConnectorServiceStub connectorServiceStub) {
		this.protoJsonMapper = protoJsonMapper;
		this.connectorServiceStub = connectorServiceStub;
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

	@Override
	public CompletableFuture<AssetDTO> getAssetBySn(String sn) {
		var request = RequestBase.newBuilder()
						.setTid(UUID.randomUUID().toString())
						.setSn(sn)
						.setTimestamp(ProtobufHelpers.now())
						.build();

		return callAsyncWithRetry(request, connectorServiceStub::getAssetBySn)
				.thenApply(response -> {
					if (response.getHasErrors()) {
						log.error("Error getting Asset from Connector Service");
						return null;
					}
					return protoJsonMapper.map(response.getAsset());
				});
	}

	@Override
	public CompletableFuture<AssetDTO> getAssetById(String id) {
		var request = ConnectorGetAssetByIdRequest.newBuilder()
				.setBase(RequestBase.newBuilder()
				.setTimestamp(ProtobufHelpers.now())
						.setTid(UUID.randomUUID().toString())
						.build())
				.setAssetId(id)
				.build();

		return callAsyncWithRetry(request, connectorServiceStub::getAssetById)
				.thenApply(response -> {
					if (response.getHasErrors()) {
						log.error("Error getting Asset from Connector Service");
						return null;
					}
					return protoJsonMapper.map(response.getAsset());
				});
	}

	@Override
	public CompletableFuture<SubAssetDTO> getSubAssetBySn(String sn) {
		var request = RequestBase.newBuilder()
						.setTid(UUID.randomUUID().toString())
						.setSn(sn)
						.setTimestamp(ProtobufHelpers.now())
						.build();

		return callAsyncWithRetry(request, connectorServiceStub::getSubAssetBySn)
				.thenApply(response -> {
					if (response.getHasErrors()) {
						log.error("Error getting SubAsset from Connector Service");
						return null;
					}
					return protoJsonMapper.map(response.getSubAsset());
				});
	}

	@Override
	public CompletableFuture<AssetPayloadDTO> upsertAssetPayload(String assetSn, String subAssetSn,
			AssetPayloadDTO payload) {
		var base = RequestBase.newBuilder()
				.setTid(UUID.randomUUID().toString())
				.setSn(assetSn)
				.setTimestamp(ProtobufHelpers.now())
				.build();
		var builder = UpsertAssetPayloadRequest.newBuilder()
				.setBase(base)
				.setPayload(protoJsonMapper.map(payload));
		if (subAssetSn != null && !subAssetSn.isBlank()) builder.setSubAssetSn(subAssetSn);

		return callAsyncWithRetry(builder.build(), connectorServiceStub::upsertAssetPayload)
				.thenApply(response -> {
					if (response.getHasErrors()) {
						String message = response.hasError() ? response.getError().getErrorMessage() : "unknown error";
						throw new CompletionException(new IllegalStateException("Payload upsert failed: " + message));
					}
					return response.hasPayload() ? protoJsonMapper.map(response.getPayload()) : null;
				});
	}

	@Override
	public CompletableFuture<AssetDTO> updateAsset(String id, AssetDTO assetDTO) {
		var request = ConnectorUpdateAssetRequest.newBuilder()
				.setBase(RequestBase.newBuilder()
						.setSn(assetDTO.getSn())
						.setTimestamp(ProtobufHelpers.now())
				.setTid(UUID.randomUUID().toString())
						.build())
				.setAssetId(id)
				.setAsset(protoJsonMapper.map(assetDTO))
				.build();

		return callAsyncWithRetry(request, connectorServiceStub::updateAsset)
				.thenApply(response -> {
					if (response.getHasErrors()) {
						log.error("Error updating asset: {}", response.getError());
						return null;
					}
					return protoJsonMapper.map(response.getAsset());
				});
	}

	@Override
	@Deprecated
	public CompletableFuture<AssetDTO> registerAsset(AssetDTO assetDTO) {
		var request = ConnectorRegisterAssetRequest.newBuilder()
				.setBase(RequestBase.newBuilder()
						.setTid(UUID.randomUUID().toString())
						.setTimestamp(ProtobufHelpers.now())
						.setSn(assetDTO.getSn())
						.build())
				.setAsset(protoJsonMapper.map(assetDTO))
				.build();

		return callAsyncWithRetry(request, connectorServiceStub::registerAsset)
				.thenApply(response -> {
					if (response.getHasErrors()) {
						log.error("Error registering asset: {}", response.getError());
						return null;
					}
					return protoJsonMapper.map(response.getAsset());
				});
	}

	@Override
	public CompletableFuture<AssetDTO> redeemAssetClaim(String code, AssetDTO asset) {
		var request = RedeemAssetClaimRequest.newBuilder()
				.setBase(RequestBase.newBuilder()
						.setTid(UUID.randomUUID().toString())
						.setTimestamp(ProtobufHelpers.now())
						.setSn(asset.getSn())
						.build())
				.setCode(code)
				.setAsset(protoJsonMapper.map(asset))
				.build();

		// Deliberately not callAsyncWithRetry: a redemption that actually landed and whose response
		// was lost would, on retry, be refused as already-spent — reporting failure for a pairing
		// that succeeded. The single call either creates the asset or does not.
		return callAsync(request, connectorServiceStub::redeemAssetClaim)
				.thenApply(response -> {
					if (response.getHasErrors() || !response.hasAsset()) {
						log.error("Claim code refused for sn={} — it may be unknown, expired, revoked, "
								+ "already used up, or not valid for this kind of device; the platform does "
								+ "not say which. Create a new code in the console and try again.", asset.getSn());
						return null;
					}
					log.info("Claim redeemed for sn={} — asset created", asset.getSn());
					return protoJsonMapper.map(response.getAsset());
				});
	}

	@Override
	public CompletableFuture<String> describeAssetClaim(String code) {
		var request = DescribeAssetClaimRequest.newBuilder()
				.setBase(RequestBase.newBuilder()
						.setTid(UUID.randomUUID().toString())
						.setTimestamp(ProtobufHelpers.now())
						.build())
				.setCode(code)
				.build();

		// Retryable, unlike redeemAssetClaim: this consumes nothing, so a lost response costs only
		// the call.
		return callAsyncWithRetry(request, connectorServiceStub::describeAssetClaim)
				.thenApply(response -> {
					if (response.getHasErrors() || !response.hasOrganizationName()) {
						log.warn("Claim code could not be resolved to an organization — it may be unknown, "
								+ "expired, revoked or already used up; the platform does not say which.");
						return null;
					}
					return response.getOrganizationName();
				});
	}

	@Override
	public CompletableFuture<AssetDTO> ensureAsset(AssetDTO asset, String claimCode) {
		return getAssetBySn(asset.getSn()).thenCompose(existing -> {
			if (existing != null) {
				return CompletableFuture.completedFuture(existing);
			}
			if (claimCode == null || claimCode.isBlank()) {
				log.warn("No asset registered for sn={}, and no claim code to pair one with. Create the "
						+ "asset in the console, or pair this device with a code.", asset.getSn());
				return CompletableFuture.completedFuture(null);
			}
			return redeemAssetClaim(claimCode, asset);
		});
	}

	@Override
	public CompletableFuture<Boolean> deRegisterAsset(String id) {
		var request = RequestBase.newBuilder()
						.setTid(UUID.randomUUID().toString())
						.setAssetId(id)
						.setTimestamp(ProtobufHelpers.now())
						.build();

		return callAsyncWithRetry(request, connectorServiceStub::deregisterAsset)
				.thenApply(response -> {
					if (response.getHasErrors()) {
						log.error("Error deregistering asset: {}", response.getError());
						return false;
					}
					return true;
				});
	}

	// Mission/Task CRUD was retired from ConnectorService in favor of the capability-execution
	// model (Application/SkillExecution); the underlying gRPC methods no longer exist.

	@Override
	public CompletableFuture<SchedulerDTO> getSchedulerById(String id) {
		var request = GetSchedulerRequest.newBuilder()
				.setBase(RequestBase.newBuilder()
						.setTid(UUID.randomUUID().toString())
						.setTimestamp(ProtobufHelpers.now())
						.build())
				.setSchedulerId(id)
				.build();

		return callAsyncWithRetry(request, connectorServiceStub::getScheduler)
				.thenApply(response -> {
					if (response.getHasErrors()) {
						log.error("Error getting scheduler: {}", response.getError());
						return null;
					}
					return protoJsonMapper.map(response.getScheduler());
				});
	}

	@Override
	public CompletableFuture<SchedulerDTO> createScheduler(SchedulerDTO schedulerDTO) {
		var request = CreateSchedulerRequest.newBuilder()
				.setBase(RequestBase.newBuilder()
						.setTid(UUID.randomUUID().toString())
						.setTimestamp(ProtobufHelpers.now())
						.build())
				.setScheduler(protoJsonMapper.map(schedulerDTO))
				.build();

		return callAsyncWithRetry(request, connectorServiceStub::createScheduler)
				.thenApply(response -> {
					if (response.getHasErrors()) {
						log.error("Error creating scheduler: {}", response.getError());
						return null;
					}
					return protoJsonMapper.map(response.getScheduler());
				});
	}

	@Override
	public CompletableFuture<SchedulerDTO> updateScheduler(String id, SchedulerDTO schedulerDTO) {
		var request = UpdateSchedulerRequest.newBuilder()
				.setBase(RequestBase.newBuilder()
						.setTid(UUID.randomUUID().toString())
						.setTimestamp(ProtobufHelpers.now())
						.build())
				.setSchedulerId(id)
				.setScheduler(protoJsonMapper.map(schedulerDTO))
				.build();

		return callAsyncWithRetry(request, connectorServiceStub::updateScheduler)
				.thenApply(response -> {
					if (response.getHasErrors()) {
						log.error("Error updating scheduler: {}", response.getError());
						return null;
					}
					return protoJsonMapper.map(response.getScheduler());
				});
	}

	@Override
	public CompletableFuture<Boolean> deleteScheduler(String id) {
		var request = DeleteSchedulerRequest.newBuilder()
				.setBase(RequestBase.newBuilder()
						.setTid(UUID.randomUUID().toString())
						.setTimestamp(ProtobufHelpers.now())
						.build())
				.setSchedulerId(id)
				.build();

		return callAsyncWithRetry(request, connectorServiceStub::deleteScheduler)
				.thenApply(response -> {
					if (response.getHasErrors()) {
						log.error("Error deleting scheduler: {}", response.getError());
						return false;
					}
					return true;
				});
	}

	@Override
	public CompletableFuture<OrganizationDTO> getOrganizationById(String id) {
		var request = ConnectorGetOrganizationRequest.newBuilder()
				.setBase(RequestBase.newBuilder()
						.setTid(UUID.randomUUID().toString())
						.setTimestamp(ProtobufHelpers.now())
						.build())
				.build();

		return callAsyncWithRetry(request, connectorServiceStub::getOrganization)
				.thenApply(response -> {
					if (response.getHasErrors()) {
						log.error("Error getting Organization: {}", response.getError());
						return null;
					}
					return protoJsonMapper.map(response.getOrganization());
				});
	}

	@Override
	public CompletableFuture<SkillContractProtoDTO> observeSkillContract(SkillContractProtoDTO contract) {
		var request = UpsertSkillContractRequest.newBuilder()
				.setBase(RequestBase.newBuilder().setTid(UUID.randomUUID().toString())
						.setTimestamp(ProtobufHelpers.now()))
				.setContract(contract).build();

		return callAsyncWithRetry(request, connectorServiceStub::observeSkillContract)
				.thenApply(response -> {
					if (response.getHasErrors()) {
						log.error("Error observing skill contract {}: {}", contract.getCommandId(), response.getError());
						return null;
					}
					return response.getContract();
				});
	}

	@Override
	public CompletableFuture<List<SkillContractProtoDTO>> listSkillContracts(SkillContractStatus status, String commandId) {
		var builder = ListSkillContractsRequest.newBuilder()
				.setBase(RequestBase.newBuilder().setTid(UUID.randomUUID().toString())
						.setTimestamp(ProtobufHelpers.now()));
		if (status != null) builder.setStatus(status);
		if (commandId != null && !commandId.isBlank()) builder.setCommandId(commandId);

		return callAsyncWithRetry(builder.build(), connectorServiceStub::listSkillContracts)
				.thenApply(response -> {
					if (response.getHasErrors()) {
						log.error("Error listing skill contracts: {}", response.getError());
						return List.<SkillContractProtoDTO>of();
					}
					return response.getContractsList();
				});
	}

	@Override
	public CompletableFuture<SkillContractProtoDTO> setSkillContractStatus(String id, SkillContractStatus status) {
		var request = SetSkillContractStatusRequest.newBuilder()
				.setBase(RequestBase.newBuilder().setTid(UUID.randomUUID().toString())
						.setTimestamp(ProtobufHelpers.now()))
				.setId(id).setStatus(status).build();

		return callAsyncWithRetry(request, connectorServiceStub::setSkillContractStatus)
				.thenApply(response -> {
					if (response.getHasErrors()) {
						log.error("Error setting skill contract status for {}: {}", id, response.getError());
						return null;
					}
					return response.getContract();
				});
	}

	@Override
	public CompletableFuture<SkillContractProtoDTO> setSkillContractPermissions(String id, List<String> requiredPermissions) {
		var request = SetSkillContractPermissionsRequest.newBuilder()
				.setBase(RequestBase.newBuilder().setTid(UUID.randomUUID().toString())
						.setTimestamp(ProtobufHelpers.now()))
				.setId(id).addAllRequiredPermissions(requiredPermissions == null ? List.of() : requiredPermissions)
				.build();

		return callAsyncWithRetry(request, connectorServiceStub::setSkillContractPermissions)
				.thenApply(response -> {
					if (response.getHasErrors()) {
						log.error("Error setting skill contract permissions for {}: {}", id, response.getError());
						return null;
					}
					return response.getContract();
				});
	}


}
