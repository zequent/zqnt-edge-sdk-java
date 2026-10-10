package com.zqnt.sdk.edge.adapter.grpc;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.zqnt.protos.capability.v3.CommandResult;
import com.zqnt.protos.capability.v3.CommandState;
import com.zqnt.protos.capability.v3.CompletionMode;
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
import com.zqnt.sdk.edge.adapter.domains.ManualControlInput;
import com.zqnt.sdk.edge.adapter.registry.CommandRegistry;
import com.zqnt.sdk.edge.adapter.registry.CommandRequest;
import com.zqnt.sdk.edge.adapter.registry.CommandSchemas;
import com.zqnt.sdk.edge.gateway.CommandExecutions;
import com.zqnt.sdk.edge.support.Structs;
import com.zqnt.utils.core.ProtobufHelpers;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The v3 edge contract ({@code zqnt.edge.v3.EdgeAdapterService}), served next to the v2
 * {@link EdgeAdapterGrpcServiceImpl} from the same {@link EdgeAdapterService}.
 *
 * <p>v3 has no per-command RPCs: every command is an {@code ExecuteCommand} with a dotted id. An id
 * in the adapter's {@link CommandRegistry} runs its registered handler. Otherwise it runs through
 * the adapter's own {@link EdgeAdapterService#sendCustomCommand} first and, when the adapter does not
 * place it there but implements it typed ({@code flight.takeoff} on {@code takeOff}, …), through
 * {@link BuiltInCommandDispatch}. Params are validated against the command's input schema before
 * anything runs; invalid params are REJECTED with {@code command.invalid_params}.
 *
 * <p>An ACCEPTED command is remembered under the platform's {@code command_execution_id}, so the
 * adapter's later progress events (reported with its own execution id) reach the platform as v3
 * {@code CommandEvent}s under that id, and a {@code CancelCommand} reaches the adapter with its own id.
 */
@Slf4j
public class EdgeAdapterV3GrpcServiceImpl extends EdgeAdapterServiceGrpc.EdgeAdapterServiceImplBase {

	static final String NOT_SUPPORTED_CODE = CommandRegistry.NOT_SUPPORTED;

	/** How long the adapter's capability list is reused to look up a command's schema and completion mode. */
	static final long CAPABILITY_CACHE_MILLIS = 30_000;

	private final EdgeAdapterService edgeAdapterService;
	private final CommandExecutions executions;
	private final Map<String, CachedCapabilities> capabilityCache = new ConcurrentHashMap<>();

	private record CachedCapabilities(Collection<com.zqnt.sdk.edge.adapter.domains.Capability> capabilities, long until) {
	}

	public EdgeAdapterV3GrpcServiceImpl(EdgeAdapterService edgeAdapterService) {
		this(edgeAdapterService, CommandExecutions.shared());
	}

	public EdgeAdapterV3GrpcServiceImpl(EdgeAdapterService edgeAdapterService, CommandExecutions executions) {
		this.edgeAdapterService = edgeAdapterService;
		this.executions = executions;
	}

	@Override
	public void getCapabilities(GetCapabilitiesRequest request, StreamObserver<GetCapabilitiesResponse> responseObserver) {
		edgeAdapterService.getCapabilities(request.getAsset().getSn())
				.thenAccept(current -> {
					responseObserver.onNext(GetCapabilitiesResponse.newBuilder()
							.setCapabilities(CapabilityMappers.toV3(request.getAsset().getSn(), current)).build());
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
		String commandId = command.getCommandId();
		String componentId = command.hasTarget() && !command.getTarget().getRef().isEmpty() ? command.getTarget().getRef() : null;
		CommandRequest commandRequest = new CommandRequest(sn, componentId, commandId, Structs.toMap(command.getParams()),
				request.getCommandExecutionId());
		execute(commandRequest)
				.thenCompose(result -> completionFor(sn, commandId, result)
						.thenApply(completion -> commandResult(commandId, request.getCommandExecutionId(), result, completion)))
				.thenAccept(result -> {
					if (result.getState() == CommandState.COMMAND_STATE_ACCEPTED) {
						executions.started(request.getCommandExecutionId(), sn, commandId, externalId(result));
					}
					responseObserver.onNext(ExecuteCommandResponse.newBuilder().setResult(result).build());
					responseObserver.onCompleted();
				})
				.exceptionally(error -> {
					Throwable cause = unwrap(error);
					log.error("v3 ExecuteCommand {} on {} failed", commandId, sn, cause);
					responseObserver.onNext(ExecuteCommandResponse.newBuilder().setResult(CommandResult.newBuilder()
							.setCommandExecutionId(request.getCommandExecutionId())
							.setCommandId(commandId)
							.setState(CommandState.COMMAND_STATE_FAILED)
							.setError(error(ErrorCategory.ERROR_CATEGORY_ASSET, "", cause.getMessage()))).build());
					responseObserver.onCompleted();
					return null;
				});
	}

	/** The registry when it has the id; else the adapter's own routing, then the built-in ids it implements typed. */
	CompletableFuture<com.zqnt.sdk.edge.adapter.domains.CommandResult> execute(CommandRequest request) {
		Optional<CommandRegistry> registry = edgeAdapterService.commandRegistry()
				.filter(candidate -> candidate.contains(request.commandId()));
		if (registry.isPresent()) {
			return registry.get().execute(request);
		}
		return capabilityFor(request.sn(), request.commandId()).thenCompose(capability -> {
			CommandSchemas.Validation validation = CommandSchemas.validate(
					capability.map(com.zqnt.sdk.edge.adapter.domains.Capability::getInputSchema).orElse(null), request.params());
			if (!validation.valid()) {
				return CompletableFuture.completedFuture(com.zqnt.sdk.edge.adapter.domains.CommandResult.rejected(
						CommandRegistry.INVALID_PARAMS, request.commandId() + ": " + validation.message(), request.sn()));
			}
			Map<String, Object> params = validation.params();
			return edgeAdapterService.sendCustomCommand(request.sn(), request.targetRef(), request.commandId(), params)
					.thenCompose(result -> {
						if (!result.isNotImplemented()) {
							return CompletableFuture.completedFuture(result);
						}
						return BuiltInCommandDispatch.dispatch(edgeAdapterService, request.sn(), request.commandId(), params)
								.orElse(CompletableFuture.completedFuture(result));
					});
		});
	}

	@Override
	public void cancelCommand(CancelCommandRequest request, StreamObserver<CancelCommandResponse> responseObserver) {
		String commandExecutionId = request.getCommandExecutionId();
		var execution = executions.get(commandExecutionId);
		String adapterId = execution.map(CommandExecutions.Execution::externalExecutionId).orElse(commandExecutionId);
		String sn = execution.map(CommandExecutions.Execution::sn).orElse(null);
		String commandId = execution.map(CommandExecutions.Execution::commandId).orElse("");
		edgeAdapterService.cancelExecution(sn, adapterId)
				.thenAccept(result -> {
					CommandResult.Builder outcome = commandResult(commandId, commandExecutionId, result).toBuilder();
					if (result.isSuccess()) {
						outcome.setState(CommandState.COMMAND_STATE_CANCELLED).clearResult();
						executions.finished(commandExecutionId);
					}
					responseObserver.onNext(CancelCommandResponse.newBuilder().setResult(outcome).build());
					responseObserver.onCompleted();
				})
				.exceptionally(error -> {
					responseObserver.onNext(CancelCommandResponse.newBuilder().setResult(CommandResult.newBuilder()
							.setCommandExecutionId(commandExecutionId)
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

	/**
	 * The command's declared completion mode -- looked up only when it decides something: a
	 * successful result that did not itself say "accepted". Never fails the command: an adapter
	 * whose capabilities cannot be read is left to its result, as before.
	 */
	CompletableFuture<CompletionMode> completionFor(String sn, String commandId,
			com.zqnt.sdk.edge.adapter.domains.CommandResult result) {
		if (!result.isSuccess() || (result.isAccepted() && result.getExternalExecutionId() != null)) {
			return CompletableFuture.completedFuture(CompletionMode.COMPLETION_MODE_UNSPECIFIED);
		}
		return capabilityFor(sn, commandId)
				.thenApply(capability -> capability.map(com.zqnt.sdk.edge.adapter.domains.Capability::getCompletion)
						.orElse(CompletionMode.COMPLETION_MODE_UNSPECIFIED));
	}

	/** The capability the adapter advertises for {@code commandId}; empty when there is none or it cannot be read. */
	private CompletableFuture<Optional<com.zqnt.sdk.edge.adapter.domains.Capability>> capabilityFor(String sn, String commandId) {
		Optional<com.zqnt.sdk.edge.adapter.domains.Capability> registered = edgeAdapterService.commandRegistry()
				.flatMap(registry -> registry.capability(commandId));
		if (registered.isPresent()) {
			return CompletableFuture.completedFuture(registered);
		}
		String key = sn == null ? "" : sn;
		CachedCapabilities cached = capabilityCache.get(key);
		CompletableFuture<Collection<com.zqnt.sdk.edge.adapter.domains.Capability>> capabilities;
		if (cached != null && cached.until() > System.currentTimeMillis()) {
			capabilities = CompletableFuture.completedFuture(cached.capabilities());
		} else {
			CompletableFuture<com.zqnt.sdk.edge.adapter.domains.CurrentCapabilities> current;
			try {
				current = edgeAdapterService.getCapabilities(sn);
			} catch (RuntimeException e) {
				current = CompletableFuture.failedFuture(e);
			}
			capabilities = current.thenApply(value -> {
				Collection<com.zqnt.sdk.edge.adapter.domains.Capability> list =
						value == null || value.getCapabilities() == null ? List.of() : List.copyOf(value.getCapabilities());
				capabilityCache.put(key, new CachedCapabilities(list, System.currentTimeMillis() + CAPABILITY_CACHE_MILLIS));
				return list;
			});
		}
		return capabilities
				.thenApply(list -> list.stream().filter(capability -> commandId.equals(capability.getCommand())).findFirst())
				.exceptionally(error -> Optional.empty());
	}

	static CommandResult commandResult(String commandId, String commandExecutionId,
			com.zqnt.sdk.edge.adapter.domains.CommandResult result) {
		return commandResult(commandId, commandExecutionId, result, CompletionMode.COMPLETION_MODE_UNSPECIFIED);
	}

	/**
	 * The adapter's result in v3 states. A success is ACCEPTED -- the outcome follows as an event --
	 * when the adapter said so (accepted, with its own execution id) or when the command's
	 * capability declares {@code ASYNCHRONOUS}; otherwise it is SUCCEEDED, with the handler's output.
	 * Without the declaration a take-off that answered a plain success would have counted as done
	 * while the aircraft climbed.
	 */
	static CommandResult commandResult(String commandId, String commandExecutionId,
			com.zqnt.sdk.edge.adapter.domains.CommandResult result, CompletionMode completion) {
		CommandResult.Builder builder = CommandResult.newBuilder()
				.setCommandExecutionId(commandExecutionId == null ? "" : commandExecutionId)
				.setCommandId(commandId == null ? "" : commandId);
		if (result.isSuccess()) {
			boolean adapterAccepted = result.isAccepted() && result.getExternalExecutionId() != null;
			if (adapterAccepted || completion == CompletionMode.COMPLETION_MODE_ASYNCHRONOUS) {
				builder.setState(CommandState.COMMAND_STATE_ACCEPTED);
				if (result.getExternalExecutionId() != null) {
					builder.setResult(Struct.newBuilder().putFields("external_execution_id",
							Value.newBuilder().setStringValue(result.getExternalExecutionId()).build()));
				}
				return builder.build();
			}
			if (result.getOutput() != null && !result.getOutput().isEmpty()) {
				builder.setResult(Structs.toStruct(result.getOutput()));
			}
			return builder.setState(CommandState.COMMAND_STATE_SUCCEEDED).build();
		}
		if (result.isNotImplemented()) {
			return builder.setState(CommandState.COMMAND_STATE_REJECTED)
					.setError(error(ErrorCategory.ERROR_CATEGORY_INVALID_ARGUMENT, NOT_SUPPORTED_CODE,
							commandId + " is not supported by this adapter"))
					.build();
		}
		if (result.isRejected()) {
			return builder.setState(CommandState.COMMAND_STATE_REJECTED)
					.setError(error(ErrorCategory.ERROR_CATEGORY_INVALID_ARGUMENT, result.getErrorCode(),
							result.getMessage() == null ? commandId + " was rejected" : result.getMessage()))
					.build();
		}
		return builder.setState(CommandState.COMMAND_STATE_FAILED)
				.setError(error(ErrorCategory.ERROR_CATEGORY_ASSET, result.getErrorCode(),
						result.getMessage() == null ? commandId + " failed" : result.getMessage()))
				.build();
	}

	private static String externalId(CommandResult result) {
		Value value = result.getResult().getFieldsMap().get("external_execution_id");
		return value == null || value.getStringValue().isBlank() ? null : value.getStringValue();
	}

	private static Error error(ErrorCategory category, String code, String message) {
		return Error.newBuilder().setCategory(category).setCode(code == null ? "" : code)
				.setMessage(message == null ? "" : message).setOccurredAt(ProtobufHelpers.now()).build();
	}

	private static Throwable unwrap(Throwable error) {
		Throwable current = error;
		while ((current instanceof CompletionException || current instanceof ExecutionException) && current.getCause() != null) {
			current = current.getCause();
		}
		return current;
	}
}
