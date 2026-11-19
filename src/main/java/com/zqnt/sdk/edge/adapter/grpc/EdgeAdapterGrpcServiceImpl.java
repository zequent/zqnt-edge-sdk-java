package com.zqnt.sdk.edge.adapter.grpc;

import com.google.protobuf.Empty;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.google.protobuf.util.Timestamps;
import com.zqnt.sdk.edge.adapter.application.EdgeAdapterService;
import com.zqnt.sdk.edge.adapter.domains.CommandResult;
import com.zqnt.sdk.edge.adapter.domains.ManualControlInput;
import com.zqnt.sdk.edge.application.ProtoJsonMapper;
import com.zqnt.utils.common.proto.ErrorCode;
import com.zqnt.utils.common.proto.GlobalErrorMessage;
import com.zqnt.utils.common.proto.RequestBase;
import com.zqnt.utils.common.proto.ResponseMeta;
import com.zqnt.utils.core.ProtobufHelpers;
import com.zqnt.utils.devicecontrol.proto.*;
import com.zqnt.utils.edge.sdk.proto.EdgeAdapterServiceGrpc;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

// Named, not wildcard: com.zqnt.utils.devicecontrol.proto also declares ManualControlInput/
// Capability, colliding with this SDK's own com.zqnt.sdk.edge.adapter.domains versions above.

@Slf4j
@SuppressWarnings("deprecation")
public class EdgeAdapterGrpcServiceImpl extends EdgeAdapterServiceGrpc.EdgeAdapterServiceImplBase {

	private final EdgeAdapterService edgeAdapterService;
	private final ProtoJsonMapper protoJsonMapper;

	public EdgeAdapterGrpcServiceImpl(EdgeAdapterService edgeAdapterService, ProtoJsonMapper protoJsonMapper) {
		this.edgeAdapterService = edgeAdapterService;
		this.protoJsonMapper = protoJsonMapper;
	}

	@Override
	public void getCapabilities(AssetCapabilitiesRequest request,
			StreamObserver<AssetCapabilitiesResponse> responseObserver) {
		edgeAdapterService.getCapabilities(request.getSn()).thenAccept(current -> {
			AssetCapabilities.Builder capabilities = AssetCapabilities.newBuilder()
					.setAssetSn(current.getSn() == null ? request.getSn() : current.getSn())
					.setAssetType(current.getAssetType() == null ? "" : current.getAssetType().name())
					.setTimestamp(Timestamps.fromMillis(current.getTimestamp()))
					.setSnapshotState(CapabilitySnapshotState.CAPABILITY_SNAPSHOT_STATE_CURRENT);
			if (current.getCapabilities() != null) {
				current.getCapabilities().stream().map(CapabilityMappers::toV2).forEach(capabilities::addCapabilities);
			}
			responseObserver.onNext(AssetCapabilitiesResponse.newBuilder().setCapabilities(capabilities).build());
			responseObserver.onCompleted();
		}).exceptionally(error -> {
			responseObserver.onNext(AssetCapabilitiesResponse.newBuilder()
					.setError(toGlobalError(unwrap(error))).build());
			responseObserver.onCompleted();
			return null;
		});
	}

	@Override
	public void sendCustomCommand(CustomCommandRequest request,
			StreamObserver<CustomCommandResponse> responseObserver) {
		String componentId = request.hasTarget() && request.getTarget().hasTargetRef()
				? request.getTarget().getTargetRef() : null;
		edgeAdapterService.sendCustomCommand(request.getBase().getSn(), componentId,
				request.getCommandId(), structToMap(request.getParams()))
				.thenAccept(result -> {
					CustomCommandResponse.Builder builder = CustomCommandResponse.newBuilder()
							.setCommandId(request.getCommandId())
							.setMeta(responseMeta(request.getBase(), result));
					if (result.isSuccess()) {
						builder.setHasErrors(false).setEmpty(Empty.getDefaultInstance());
					} else {
						builder.setHasErrors(true).setError(GlobalErrorMessage.newBuilder()
								.setErrorMessage(result.getMessage())
								.setErrorCode(result.isNotImplemented() || result.isRejected() ? ErrorCode.ERROR_CODE_CLIENT : ErrorCode.ERROR_CODE_ASSET)
								.setTimestamp(ProtobufHelpers.now()));
					}
					responseObserver.onNext(builder.build());
					responseObserver.onCompleted();
				})
				.exceptionally(error -> {
					Throwable cause = unwrap(error);
					responseObserver.onNext(CustomCommandResponse.newBuilder()
								.setHasErrors(true).setCommandId(request.getCommandId())
							.setMeta(responseMeta(request.getBase(), cause.getMessage()))
							.setError(toGlobalError(cause)).build());
					responseObserver.onCompleted();
					return null;
				});
	}

	private Map<String, Object> structToMap(Struct struct) {
		Map<String, Object> result = new java.util.LinkedHashMap<>();
		struct.getFieldsMap().forEach((key, value) -> result.put(key, valueToObject(value)));
		return result;
	}

	private Object valueToObject(Value value) {
		return switch (value.getKindCase()) {
			case NULL_VALUE, KIND_NOT_SET -> null;
			case NUMBER_VALUE -> value.getNumberValue();
			case STRING_VALUE -> value.getStringValue();
			case BOOL_VALUE -> value.getBoolValue();
			case STRUCT_VALUE -> structToMap(value.getStructValue());
			case LIST_VALUE -> value.getListValue().getValuesList().stream().map(this::valueToObject).toList();
		};
	}

	@Override
	public void takeOff(CoordinateCommandRequest request, StreamObserver<CommandResponse> responseObserver) {
		log.info("TakeOff for Edge SN: {}", request.getBase().getSn());
		handle(request.getBase(), edgeAdapterService.takeOff(protoJsonMapper.mapTakeOff(request)), responseObserver);
	}

	@Override
	public void goTo(CoordinateCommandRequest request, StreamObserver<CommandResponse> responseObserver) {
		log.info("GoTo for Edge SN: {}", request.getBase().getSn());
		handle(request.getBase(), edgeAdapterService.goTo(protoJsonMapper.mapGoTo(request)), responseObserver);
	}

	@Override
	public void returnToHome(ReturnToHomeCommandRequest request, StreamObserver<CommandResponse> responseObserver) {
		log.info("ReturnToHome for Edge SN: {}", request.getBase().getSn());
		handle(request.getBase(), edgeAdapterService.returnToHome(protoJsonMapper.map(request)), responseObserver);
	}

	@Override
	public void enterManualControl(ManualControlCommandRequest request, StreamObserver<CommandResponse> responseObserver) {
		log.info("EnterManualControl for Edge SN: {}", request.getBase().getSn());
		handle(request.getBase(), edgeAdapterService.enterManualControl(request.getBase().getSn()), responseObserver);
	}

	@Override
	public void exitManualControl(ManualControlCommandRequest request, StreamObserver<CommandResponse> responseObserver) {
		log.info("ExitManualControl for Edge SN: {}", request.getBase().getSn());
		handle(request.getBase(), edgeAdapterService.exitManualControl(request.getBase().getSn()), responseObserver);
	}

	@Override
	public StreamObserver<ManualControlInputCommandRequest> manualControlInput(StreamObserver<CommandResponse> responseObserver) {
		log.info("ManualControlInput stream started");

		return new StreamObserver<>() {
			private String sn;

			@Override
			public void onNext(ManualControlInputCommandRequest request) {
				ManualControlInput input = protoJsonMapper.map(request);
				if (sn == null) {
					sn = input.getSn();
					log.info("Starting manual control input stream for SN: {}", sn);
				}
				edgeAdapterService.manualControlInput(input)
						.exceptionally(throwable -> {
							log.error("Failed to process manual input for SN: {}", sn, throwable);
							return null;
						});
			}

			@Override
			public void onError(Throwable t) {
				log.error("Manual control input stream error for SN: {}", sn, t);
				responseObserver.onError(t);
			}

			@Override
			public void onCompleted() {
				log.info("Manual control input stream completed for SN: {}", sn);
				String tid = java.util.UUID.randomUUID().toString();
				RequestBase base = createBase(sn != null ? sn : "", tid);
				responseObserver.onNext(toCommandResponse(base,
						CommandResult.success("Manual control input session completed", tid, sn)));
				responseObserver.onCompleted();
			}
		};
	}

	@Override
	public void lookAt(LookAtCommandRequest request, StreamObserver<CommandResponse> responseObserver) {
		log.info("LookAt for Edge SN: {}", request.getBase().getSn());
		handle(request.getBase(), edgeAdapterService.lookAt(protoJsonMapper.map(request)), responseObserver);
	}

	@Override
	public void liveStreamSplitScreen(ToggleCommandRequest request, StreamObserver<CommandResponse> responseObserver) {
		log.info("LiveStreamSplitScreen for Edge SN: {}, enabled: {}",
				request.getBase().getSn(), request.getEnabled());
		handle(request.getBase(), edgeAdapterService.liveStreamSplitScreen(
				request.getBase().getSn(), request.getEnabled()), responseObserver);
	}

	@Override
	public void capturePhoto(EmptyCommandRequest request, StreamObserver<CommandResponse> responseObserver) {
		log.info("TakePhoto for Edge SN: {}", request.getBase().getSn());
		handle(request.getBase(), edgeAdapterService.takePhoto(protoJsonMapper.map(request)), responseObserver);
	}

	@Override
	public void enableGimbalTracking(ToggleCommandRequest request, StreamObserver<CommandResponse> responseObserver) {
		log.info("EnableGimbalTracking for Edge SN: {}", request.getBase().getSn());
		handle(request.getBase(), edgeAdapterService.enableGimbalTracking(request.getBase().getSn(), request.getEnabled()), responseObserver);
	}

	@Override
	public void openCover(EmptyCommandRequest request, StreamObserver<CommandResponse> responseObserver) {
		log.info("OpenCover for Edge SN: {}", request.getBase().getSn());
		handle(request.getBase(), edgeAdapterService.openCover(request.getBase().getSn()), responseObserver);
	}

	@Override
	public void closeCover(CloseCoverCommandRequest request, StreamObserver<CommandResponse> responseObserver) {
		log.info("CloseCover for Edge SN: {}", request.getBase().getSn());
		Boolean force = request.hasForce() ? request.getForce() : null;
		handle(request.getBase(), edgeAdapterService.closeCover(request.getBase().getSn(), force), responseObserver);
	}

	@Override
	public void startCharging(EmptyCommandRequest request, StreamObserver<CommandResponse> responseObserver) {
		log.info("StartCharging for Edge SN: {}", request.getBase().getSn());
		handle(request.getBase(), edgeAdapterService.startCharging(request.getBase().getSn()), responseObserver);
	}

	@Override
	public void stopCharging(EmptyCommandRequest request, StreamObserver<CommandResponse> responseObserver) {
		log.info("StopCharging for Edge SN: {}", request.getBase().getSn());
		handle(request.getBase(), edgeAdapterService.stopCharging(request.getBase().getSn()), responseObserver);
	}

	@Override
	public void rebootAsset(EmptyCommandRequest request, StreamObserver<CommandResponse> responseObserver) {
		log.info("RebootAsset for Edge SN: {}", request.getBase().getSn());
		handle(request.getBase(), edgeAdapterService.rebootAsset(request.getBase().getSn()), responseObserver);
	}

	@Override
	public void bootSubAsset(ToggleCommandRequest request, StreamObserver<CommandResponse> responseObserver) {
		log.info("BootSubAsset for Edge SN: {}, enabled: {}", request.getBase().getSn(), request.getEnabled());
		CompletableFuture<CommandResult> result = request.getEnabled()
				? edgeAdapterService.bootUpSubAsset(request.getBase().getSn())
				: edgeAdapterService.bootDownSubAsset(request.getBase().getSn());
		handle(request.getBase(), result, responseObserver);
	}

	@Override
	public void setRemoteDebugMode(ToggleCommandRequest request, StreamObserver<CommandResponse> responseObserver) {
		log.info("RemoteDebugMode for Edge SN: {}, enabled: {}", request.getBase().getSn(), request.getEnabled());
		CompletableFuture<CommandResult> result = request.getEnabled()
				? edgeAdapterService.enterRemoteDebugMode(request.getBase().getSn())
				: edgeAdapterService.closeRemoteDebugMode(request.getBase().getSn());
		handle(request.getBase(), result, responseObserver);
	}

	@Override
	public void changeAcMode(ChangeAcModeCommandRequest request, StreamObserver<CommandResponse> responseObserver) {
		log.info("ChangeAcMode for Edge SN: {}", request.getBase().getSn());
		handle(request.getBase(), edgeAdapterService.changeAcMode(request.getBase().getSn(), request.getMode().name()), responseObserver);
	}

	@Override
	public void startLiveStream(LiveStreamStartCommandRequest request, StreamObserver<CommandResponse> responseObserver) {
		log.info("StartLiveStream for Edge SN: {}", request.getBase().getSn());
		handle(request.getBase(), edgeAdapterService.startLiveStream(protoJsonMapper.map(request)), responseObserver);
	}

	@Override
	public void stopLiveStream(LiveStreamStopCommandRequest request, StreamObserver<CommandResponse> responseObserver) {
		log.info("StopLiveStream for Edge SN: {}", request.getBase().getSn());
		handle(request.getBase(), edgeAdapterService.stopLiveStream(protoJsonMapper.map(request)), responseObserver);
	}

	@Override
	public void changeLens(ChangeCameraLensCommandRequest request, StreamObserver<CommandResponse> responseObserver) {
		log.info("ChangeLens for Edge SN: {}", request.getBase().getSn());
		handle(request.getBase(), edgeAdapterService.changeLens(protoJsonMapper.map(request)), responseObserver);
	}

	@Override
	public void changeZoom(ChangeCameraZoomCommandRequest request, StreamObserver<CommandResponse> responseObserver) {
		log.info("ChangeZoom for Edge SN: {}", request.getBase().getSn());
		handle(request.getBase(), edgeAdapterService.changeZoom(protoJsonMapper.map(request)), responseObserver);
	}

	@Override
	public void registerAsset(RegisterAssetCommandRequest request, StreamObserver<CommandResponse> responseObserver) {
		super.registerAsset(request, responseObserver);
	}

	@Override
	public void deregisterAsset(EmptyCommandRequest request, StreamObserver<CommandResponse> responseObserver) {
		super.deregisterAsset(request, responseObserver);
	}

	@Override
	public void prepareTask(TaskCommandRequest request, StreamObserver<CommandResponse> responseObserver) {
		log.info("PrepareTask for Edge SN: {}", request.getBase().getSn());
		handle(request.getBase(), edgeAdapterService.prepareTask(request.getTaskId(), request.getBase().getTid()), responseObserver);
	}

	@Override
	public void startTask(TaskCommandRequest request, StreamObserver<CommandResponse> responseObserver) {
		log.info("StartTask for Edge SN: {}", request.getBase().getSn());
		handle(request.getBase(), edgeAdapterService.startTask(request.getTaskId(), request.getBase().getTid()), responseObserver);
	}

	@Override
	public void stopTask(TaskCommandRequest request, StreamObserver<CommandResponse> responseObserver) {
		log.warn("StopTask (cancelExecution) for Edge SN: {}, externalExecutionId: {}",
				request.getBase().getSn(), request.getTaskId());
		handle(request.getBase(), edgeAdapterService.cancelExecution(
				request.getBase().getSn(), request.getTaskId()), responseObserver);
	}

	@Override
	public void pauseTask(TaskCommandRequest request, StreamObserver<CommandResponse> responseObserver) {
		log.info("PauseTask for Edge SN: {}", request.getBase().getSn());
		handle(request.getBase(), edgeAdapterService.pauseTask(request.getTaskId()), responseObserver);
	}

	@Override
	public void resumeTask(TaskCommandRequest request, StreamObserver<CommandResponse> responseObserver) {
		log.info("ResumeTask for Edge SN: {}", request.getBase().getSn());
		handle(request.getBase(), edgeAdapterService.resumeTask(request.getTaskId()), responseObserver);
	}

	private void handle(RequestBase base, CompletableFuture<CommandResult> future, StreamObserver<CommandResponse> responseObserver) {
		future.thenAccept(result -> {
					responseObserver.onNext(toCommandResponse(base, result));
					responseObserver.onCompleted();
				})
				.exceptionally(throwable -> {
					responseObserver.onNext(toErrorResponse(base, throwable));
					responseObserver.onCompleted();
					return null;
				});
	}

	protected CommandResponse toCommandResponse(RequestBase base, CommandResult result) {
		CommandResponse.Builder builder = CommandResponse.newBuilder()
				.setMeta(responseMeta(base, result));

		if (result.isSuccess()) {
			return builder
					.setHasErrors(false)
					.setEmpty(Empty.getDefaultInstance())
					.build();
		}

		ErrorCode errorCode = result.isNotImplemented() || result.isRejected() ? ErrorCode.ERROR_CODE_CLIENT : ErrorCode.ERROR_CODE_ASSET;
		if (result.isNotImplemented()) {
			log.warn("Command not implemented: {} for SN: {}", result.getMessage(), base.getSn());
		}

		return builder
				.setHasErrors(true)
				.setError(GlobalErrorMessage.newBuilder()
						.setErrorMessage(result.getMessage())
						.setErrorCode(errorCode)
						.setTimestamp(ProtobufHelpers.now())
						.build())
				.build();
	}

	protected CommandResponse toErrorResponse(RequestBase base, Throwable error) {
		Throwable cause = unwrap(error);
		log.error("Error processing command for SN: {}, TID: {}", base.getSn(), base.getTid(), cause);
		return CommandResponse.newBuilder()
				.setHasErrors(true)
				.setMeta(responseMeta(base, cause.getMessage()))
				.setError(toGlobalError(cause))
				.build();
	}

	private ResponseMeta responseMeta(RequestBase base, String message) {
		ResponseMeta.Builder builder = ResponseMeta.newBuilder()
				.setTid(base.getTid())
				.setSn(base.getSn())
				.setTimestamp(ProtobufHelpers.now());

		set(builder::setAssetId, valueOrNull(base.hasAssetId(), base.getAssetId()));
		set(builder::setExternalId, valueOrNull(base.hasExternalId(), base.getExternalId()));
		set(builder::setResponseMessage, message);
		return builder.build();
	}

	/**
	 * Same as {@link #responseMeta(RequestBase, String)}, but prefers the vendor execution id
	 * carried on an accepted/success {@link CommandResult} (its {@code tid}) over the request's
	 * own correlation id, so callers tracking async command progress (e.g. mission-autonomy's
	 * execution dispatcher) learn the vendor's real execution id instead of just their own.
	 */
	private ResponseMeta responseMeta(RequestBase base, CommandResult result) {
		ResponseMeta.Builder builder = ResponseMeta.newBuilder()
				.setTid(base.getTid())
				.setSn(base.getSn())
				.setTimestamp(ProtobufHelpers.now());

		set(builder::setAssetId, valueOrNull(base.hasAssetId(), base.getAssetId()));
		String vendorExecutionId = result.getExternalExecutionId();
		String externalId = vendorExecutionId != null && !vendorExecutionId.isBlank()
				? vendorExecutionId : valueOrNull(base.hasExternalId(), base.getExternalId());
		set(builder::setExternalId, externalId);
		set(builder::setResponseMessage, result.getMessage());
		return builder.build();
	}

	private GlobalErrorMessage toGlobalError(Throwable error) {
		Throwable cause = unwrap(error);
		return GlobalErrorMessage.newBuilder()
				.setErrorMessage(cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName())
				.setErrorCode(determineErrorCode(cause))
				.setTimestamp(ProtobufHelpers.now())
				.build();
	}

	private ErrorCode determineErrorCode(Throwable error) {
		if (error instanceof IllegalArgumentException || error instanceof UnsupportedOperationException) {
			return ErrorCode.ERROR_CODE_CLIENT;
		}
		if (error instanceof java.util.concurrent.TimeoutException) {
			return ErrorCode.ERROR_CODE_SYSTEM;
		}
		return ErrorCode.ERROR_CODE_SYSTEM;
	}

	private Throwable unwrap(Throwable error) {
		if ((error instanceof CompletionException || error instanceof ExecutionException) && error.getCause() != null) {
			return error.getCause();
		}
		return error;
	}

	protected RequestBase createBase(String sn, String tid) {
		return RequestBase.newBuilder()
				.setSn(sn)
				.setTid(tid)
				.setTimestamp(ProtobufHelpers.now())
				.build();
	}

	private static <T> void set(java.util.function.Consumer<T> setter, T value) {
		if (value != null) {
			setter.accept(value);
		}
	}

	private static <T> T valueOrNull(boolean condition, T value) {
		return condition ? value : null;
	}

	private static String valueOrDefault(String value) {
		return value != null ? value : "";
	}
}
