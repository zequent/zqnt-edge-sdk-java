package com.zqnt.sdk.edge.adapter.grpc;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.google.protobuf.util.Timestamps;
import com.zqnt.protos.capability.v3.Capability;
import com.zqnt.protos.capability.v3.CapabilitySet;
import com.zqnt.protos.capability.v3.CapabilitySource;
import com.zqnt.protos.capability.v3.CapabilityState;
import com.zqnt.protos.capability.v3.CommandResult;
import com.zqnt.protos.capability.v3.CommandState;
import com.zqnt.protos.capability.v3.SnapshotState;
import com.zqnt.protos.capability.v3.Target;
import com.zqnt.protos.capability.v3.TargetType;
import com.zqnt.protos.common.v3.Error;
import com.zqnt.protos.common.v3.ErrorCategory;
import com.zqnt.protos.edge.v3.CancelCommandRequest;
import com.zqnt.protos.edge.v3.CancelCommandResponse;
import com.zqnt.protos.edge.v3.EdgeAdapterServiceGrpc;
import com.zqnt.protos.edge.v3.ExecuteCommandRequest;
import com.zqnt.protos.edge.v3.ExecuteCommandResponse;
import com.zqnt.protos.edge.v3.GetCapabilitiesRequest;
import com.zqnt.protos.edge.v3.GetCapabilitiesResponse;
import com.zqnt.protos.edge.v3.StreamManualControlRequest;
import com.zqnt.protos.edge.v3.StreamManualControlResponse;
import com.zqnt.sdk.edge.adapter.application.BuiltInCommandDispatch;
import com.zqnt.sdk.edge.adapter.application.EdgeAdapterService;
import com.zqnt.sdk.edge.adapter.domains.CurrentCapabilities;
import com.zqnt.sdk.edge.adapter.domains.ManualControlInput;
import com.zqnt.utils.core.ProtobufHelpers;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The v3 edge contract ({@code zqnt.edge.v3.EdgeAdapterService}), served next to the v2
 * {@link EdgeAdapterGrpcServiceImpl} from the same {@link EdgeAdapterService}.
 *
 * <p>v3 has no per-command RPCs: every command is an {@code ExecuteCommand} with a dotted id. It
 * runs through the adapter's own {@link EdgeAdapterService#sendCustomCommand} first; an id the
 * adapter does not place there but implements typed ({@code flight.takeoff} on {@code takeOff}, …)
 * is routed by {@link BuiltInCommandDispatch}. An existing adapter therefore answers v3 without a
 * change -- it only has to register this service too (a {@code @GrpcService} subclass in Quarkus).
 *
 * <p>A long command the adapter accepts with an external execution id is reported
 * {@code ACCEPTED}, with that id in {@code result.external_execution_id}; its completion still
 * arrives as the v2 {@code CommandExecutionEvent} until the platform serves
 * {@code zqnt.edge.v3.EdgeGatewayService} (zqnt-core#147).
 */
@Slf4j
public class EdgeAdapterV3GrpcServiceImpl extends EdgeAdapterServiceGrpc.EdgeAdapterServiceImplBase {

	static final String NOT_SUPPORTED_CODE = "command.not_supported";

	private final EdgeAdapterService edgeAdapterService;

	public EdgeAdapterV3GrpcServiceImpl(EdgeAdapterService edgeAdapterService) {
		this.edgeAdapterService = edgeAdapterService;
	}

	@Override
	public void getCapabilities(GetCapabilitiesRequest request, StreamObserver<GetCapabilitiesResponse> responseObserver) {
		edgeAdapterService.getCapabilities(request.getAsset().getSn())
				.thenAccept(current -> {
					responseObserver.onNext(GetCapabilitiesResponse.newBuilder()
							.setCapabilities(capabilitySet(request.getAsset().getSn(), current)).build());
					responseObserver.onCompleted();
				})
				.exceptionally(error -> {
					responseObserver.onError(io.grpc.Status.INTERNAL
							.withDescription(unwrap(error).getMessage()).asRuntimeException());
					return null;
				});
	}

	@Override
	public void executeCommand(ExecuteCommandRequest request, StreamObserver<ExecuteCommandResponse> responseObserver) {
		var command = request.getCommand();
		String sn = command.getAsset().getSn();
		String componentId = command.hasTarget() && !command.getTarget().getRef().isEmpty()
				? command.getTarget().getRef() : null;
		Map<String, Object> params = structToMap(command.getParams());
		execute(sn, componentId, command.getCommandId(), params)
				.thenAccept(result -> {
					responseObserver.onNext(ExecuteCommandResponse.newBuilder()
							.setResult(commandResult(command.getCommandId(), request.getCommandExecutionId(), result)).build());
					responseObserver.onCompleted();
				})
				.exceptionally(error -> {
					Throwable cause = unwrap(error);
					log.error("v3 ExecuteCommand {} on {} failed", command.getCommandId(), sn, cause);
					responseObserver.onNext(ExecuteCommandResponse.newBuilder().setResult(CommandResult.newBuilder()
							.setCommandExecutionId(request.getCommandExecutionId())
							.setCommandId(command.getCommandId())
							.setState(CommandState.COMMAND_STATE_FAILED)
							.setError(error(ErrorCategory.ERROR_CATEGORY_ASSET, "", cause.getMessage()))).build());
					responseObserver.onCompleted();
					return null;
				});
	}

	/** The adapter's own routing first, then the built-in ids it implements typed. */
	CompletableFuture<com.zqnt.sdk.edge.adapter.domains.CommandResult> execute(
			String sn, String componentId, String commandId, Map<String, Object> params) {
		return edgeAdapterService.sendCustomCommand(sn, componentId, commandId, params)
				.thenCompose(result -> {
					if (!result.isNotImplemented()) {
						return CompletableFuture.completedFuture(result);
					}
					return BuiltInCommandDispatch.dispatch(edgeAdapterService, sn, commandId, params)
							.orElse(CompletableFuture.completedFuture(result));
				});
	}

	@Override
	public void cancelCommand(CancelCommandRequest request, StreamObserver<CancelCommandResponse> responseObserver) {
		// The id the command was accepted under -- what v2's StopTask carries as its task id.
		edgeAdapterService.cancelExecution(null, request.getCommandExecutionId())
				.thenAccept(result -> {
					CommandResult.Builder outcome = commandResult("", request.getCommandExecutionId(), result).toBuilder();
					if (result.isSuccess()) {
						outcome.setState(CommandState.COMMAND_STATE_CANCELLED);
					}
					responseObserver.onNext(CancelCommandResponse.newBuilder().setResult(outcome).build());
					responseObserver.onCompleted();
				})
				.exceptionally(error -> {
					responseObserver.onNext(CancelCommandResponse.newBuilder().setResult(CommandResult.newBuilder()
							.setCommandExecutionId(request.getCommandExecutionId())
							.setState(CommandState.COMMAND_STATE_FAILED)
							.setError(error(ErrorCategory.ERROR_CATEGORY_ASSET, "", unwrap(error).getMessage()))).build());
					responseObserver.onCompleted();
					return null;
				});
	}

	@Override
	public StreamObserver<StreamManualControlRequest> streamManualControl(
			StreamObserver<StreamManualControlResponse> responseObserver) {
		AtomicLong accepted = new AtomicLong();
		return new StreamObserver<>() {
			@Override
			public void onNext(StreamManualControlRequest request) {
				accepted.incrementAndGet();
				var input = request.getInput();
				edgeAdapterService.manualControlInput(ManualControlInput.builder()
								.sn(request.getAsset().getSn())
								.roll(input.getRoll())
								.pitch(input.getPitch())
								.yaw(input.getYaw())
								.throttle(input.getThrottle())
								.gimbalPitch(input.hasGimbalPitch() ? input.getGimbalPitch() : null)
								.build())
						.exceptionally(error -> {
							log.error("Manual control input for {} failed", request.getAsset().getSn(), error);
							return null;
						});
			}

			@Override
			public void onError(Throwable t) {
				log.error("v3 manual control stream error", t);
			}

			@Override
			public void onCompleted() {
				responseObserver.onNext(StreamManualControlResponse.newBuilder().setAcceptedInputs(accepted.get()).build());
				responseObserver.onCompleted();
			}
		};
	}

	static CommandResult commandResult(String commandId, String commandExecutionId,
			com.zqnt.sdk.edge.adapter.domains.CommandResult result) {
		CommandResult.Builder builder = CommandResult.newBuilder()
				.setCommandExecutionId(commandExecutionId == null ? "" : commandExecutionId)
				.setCommandId(commandId == null ? "" : commandId);
		if (result.isSuccess()) {
			if (result.isAccepted() && result.getExternalExecutionId() != null) {
				return builder.setState(CommandState.COMMAND_STATE_ACCEPTED)
						.setResult(Struct.newBuilder().putFields("external_execution_id",
								Value.newBuilder().setStringValue(result.getExternalExecutionId()).build()))
						.build();
			}
			return builder.setState(CommandState.COMMAND_STATE_SUCCEEDED).build();
		}
		if (result.isNotImplemented()) {
			return builder.setState(CommandState.COMMAND_STATE_REJECTED)
					.setError(error(ErrorCategory.ERROR_CATEGORY_INVALID_ARGUMENT, NOT_SUPPORTED_CODE,
							commandId + " is not supported by this adapter"))
					.build();
		}
		return builder.setState(CommandState.COMMAND_STATE_FAILED)
				.setError(error(ErrorCategory.ERROR_CATEGORY_ASSET, "",
						result.getMessage() == null ? commandId + " failed" : result.getMessage()))
				.build();
	}

	static CapabilitySet capabilitySet(String requestedSn, CurrentCapabilities current) {
		CapabilitySet.Builder set = CapabilitySet.newBuilder()
				.setAssetSn(current.getSn() == null ? requestedSn : current.getSn())
				.setAssetType(current.getAssetType() == null ? "" : current.getAssetType().name())
				.setObservedAt(current.getTimestamp() > 0 ? Timestamps.fromMillis(current.getTimestamp()) : ProtobufHelpers.now())
				.setSnapshotState(SnapshotState.SNAPSHOT_STATE_CURRENT);
		if (current.getCapabilities() != null) {
			current.getCapabilities().stream().map(EdgeAdapterV3GrpcServiceImpl::capability).forEach(set::addCapabilities);
		}
		return set.build();
	}

	static Capability capability(com.zqnt.sdk.edge.adapter.domains.Capability value) {
		String id = value.getCommand() == null ? "" : value.getCommand();
		Capability.Builder builder = Capability.newBuilder()
				.setCommandId(id)
				.setDisplayName(id)
				// v2 and v3 share the numbering of these enums on purpose.
				.setStateValue(value.getState() == null ? CapabilityState.CAPABILITY_STATE_AVAILABLE_VALUE : value.getState().getNumber())
				.setTarget(Target.newBuilder()
						.setTypeValue(value.getTargetType() == null ? TargetType.TARGET_TYPE_ASSET_VALUE : value.getTargetType().getNumber())
						.setRef(value.getTargetRef() == null ? "" : value.getTargetRef()));
		if (value.getDescription() != null) builder.setDescription(value.getDescription());
		if (value.getUnavailableReason() != null) builder.setUnavailableReason(value.getUnavailableReason());
		if (value.getMetadata() != null) builder.putAllMetadata(value.getMetadata());
		if (value.getConstraints() != null && !value.getConstraints().isEmpty()) builder.setConstraints(mapToStruct(value.getConstraints()));
		if (value.getInputSchema() != null && !value.getInputSchema().isEmpty()) builder.setInputSchema(mapToStruct(value.getInputSchema()));
		if (value.getOutputSchema() != null && !value.getOutputSchema().isEmpty()) builder.setOutputSchema(mapToStruct(value.getOutputSchema()));
		if (value.getSchemaVersion() != null) builder.setSchemaVersion(value.getSchemaVersion());
		if (value.getSkillId() != null) builder.setSkillId(value.getSkillId());
		if (value.getProvider() != null) builder.setProvider(value.getProvider());
		builder.setSourceValue(value.getSource() == null ? CapabilitySource.CAPABILITY_SOURCE_EDGE_ADAPTER_VALUE : value.getSource().getNumber());
		return builder.build();
	}

	private static Error error(ErrorCategory category, String code, String message) {
		return Error.newBuilder().setCategory(category).setCode(code == null ? "" : code)
				.setMessage(message == null ? "" : message).setOccurredAt(ProtobufHelpers.now()).build();
	}

	private static Struct mapToStruct(Map<String, Object> map) {
		Struct.Builder builder = Struct.newBuilder();
		try {
			com.google.protobuf.util.JsonFormat.parser().merge(
					new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(map), builder);
		} catch (Exception e) {
			log.warn("Could not convert a capability schema to a Struct: {}", e.getMessage());
		}
		return builder.build();
	}

	private static Map<String, Object> structToMap(Struct struct) {
		if (struct == null || struct.getFieldsCount() == 0) {
			return Map.of();
		}
		try {
			@SuppressWarnings("unchecked")
			Map<String, Object> map = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
					com.google.protobuf.util.JsonFormat.printer().print(struct), Map.class);
			return map;
		} catch (Exception e) {
			throw new IllegalArgumentException("Invalid command params: " + e.getMessage(), e);
		}
	}

	private static Throwable unwrap(Throwable error) {
		Throwable current = error;
		while ((current instanceof CompletionException || current instanceof ExecutionException) && current.getCause() != null) {
			current = current.getCause();
		}
		return current;
	}
}
