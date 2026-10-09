package com.zqnt.sdk.edge.adapter.grpc;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.zqnt.protos.capability.v3.Command;
import com.zqnt.protos.capability.v3.CommandState;
import com.zqnt.protos.common.v3.AssetRef;
import com.zqnt.protos.common.v3.ErrorCategory;
import com.zqnt.protos.edge.v3.CancelCommandRequest;
import com.zqnt.protos.edge.v3.CancelCommandResponse;
import com.zqnt.protos.edge.v3.ExecuteCommandRequest;
import com.zqnt.protos.edge.v3.ExecuteCommandResponse;
import com.zqnt.sdk.edge.adapter.application.EdgeAdapterService;
import com.zqnt.sdk.edge.adapter.domains.CommandResult;
import com.zqnt.sdk.edge.adapter.domains.TakeOffRequest;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v3 has no per-command RPCs. An adapter written for v2 -- typed {@code takeOff}, its own
 * {@code sendCustomCommand} for vendor ids -- must answer v3 {@code ExecuteCommand} unchanged, and an
 * id it cannot place is REJECTED, never an aborted call.
 */
class EdgeAdapterV3GrpcServiceImplTest {

	private static final class Adapter implements EdgeAdapterService {
		final List<TakeOffRequest> takeoffs = new ArrayList<>();
		final List<Map<String, Object>> sprays = new ArrayList<>();
		final List<String> cancelled = new ArrayList<>();

		@Override
		public CompletableFuture<CommandResult> takeOff(TakeOffRequest request) {
			takeoffs.add(request);
			return CompletableFuture.completedFuture(CommandResult.success("airborne", request.getSn()));
		}

		@Override
		public CompletableFuture<CommandResult> sendCustomCommand(String sn, String componentId, String commandType,
				Map<String, Object> params) {
			return switch (commandType) {
				case "vendor.acme.spray" -> {
					sprays.add(params);
					yield CompletableFuture.completedFuture(CommandResult.success("sprayed", sn));
				}
				case "mission.waypoint.execute" ->
						CompletableFuture.completedFuture(CommandResult.accepted("flying", "dji-77", sn));
				case "vendor.acme.broken" -> CompletableFuture.completedFuture(CommandResult.error("nozzle blocked", sn));
				default -> CompletableFuture.completedFuture(CommandResult.notImplemented("unknown " + commandType, sn));
			};
		}

		@Override
		public CompletableFuture<CommandResult> cancelExecution(String sn, String externalExecutionId) {
			cancelled.add(externalExecutionId);
			return CompletableFuture.completedFuture(CommandResult.success("stopped", sn));
		}
	}

	private static final class Capture<T> implements StreamObserver<T> {
		T value;

		@Override
		public void onNext(T value) {
			this.value = value;
		}

		@Override
		public void onError(Throwable t) {
			fail("unexpected error: " + t);
		}

		@Override
		public void onCompleted() {
		}
	}

	private static ExecuteCommandRequest request(String commandId, Map<String, Object> params) {
		Struct.Builder struct = Struct.newBuilder();
		params.forEach((key, value) -> struct.putFields(key, value instanceof Number number
				? Value.newBuilder().setNumberValue(number.doubleValue()).build()
				: Value.newBuilder().setStringValue(String.valueOf(value)).build()));
		return ExecuteCommandRequest.newBuilder()
				.setCommandExecutionId("cx-1")
				.setCommand(Command.newBuilder().setAsset(AssetRef.newBuilder().setSn("SN-1")).setCommandId(commandId)
						.setParams(struct))
				.build();
	}

	private static ExecuteCommandResponse execute(EdgeAdapterService adapter, String commandId, Map<String, Object> params) {
		Capture<ExecuteCommandResponse> capture = new Capture<>();
		new EdgeAdapterV3GrpcServiceImpl(adapter).executeCommand(request(commandId, params), capture);
		assertNotNull(capture.value, "a response");
		return capture.value;
	}

	@Test
	void theAdaptersOwnRoutingRunsAVendorCommand() {
		Adapter adapter = new Adapter();
		var response = execute(adapter, "vendor.acme.spray", Map.of("seconds", 3));

		assertEquals(CommandState.COMMAND_STATE_SUCCEEDED, response.getResult().getState());
		assertEquals("cx-1", response.getResult().getCommandExecutionId());
		assertEquals(3.0, ((Number) adapter.sprays.get(0).get("seconds")).doubleValue());
	}

	@Test
	void aBuiltInIdReachesTheTypedMethodWhenTheAdapterDoesNotPlaceIt() {
		Adapter adapter = new Adapter();
		var response = execute(adapter, "flight.takeoff", Map.of("latitude", 52.5, "longitude", 13.4, "altitude", 40));

		assertEquals(CommandState.COMMAND_STATE_SUCCEEDED, response.getResult().getState());
		assertEquals(1, adapter.takeoffs.size());
		assertEquals("SN-1", adapter.takeoffs.get(0).getSn());
	}

	@Test
	void anUnknownIdIsRejectedNotAborted() {
		var response = execute(new Adapter(), "vendor.nope.nothing", Map.of());

		assertEquals(CommandState.COMMAND_STATE_REJECTED, response.getResult().getState());
		assertEquals(EdgeAdapterV3GrpcServiceImpl.NOT_SUPPORTED_CODE, response.getResult().getError().getCode());
		assertEquals(ErrorCategory.ERROR_CATEGORY_INVALID_ARGUMENT, response.getResult().getError().getCategory());
	}

	/** An adapter that declares take-off ASYNCHRONOUS: its typed takeOff answers a plain success. */
	private static final class DeclaringAdapter implements EdgeAdapterService {
		@Override
		public CompletableFuture<CommandResult> takeOff(TakeOffRequest request) {
			return CompletableFuture.completedFuture(CommandResult.success("climbing", request.getSn()));
		}

		@Override
		public CompletableFuture<com.zqnt.sdk.edge.adapter.domains.CurrentCapabilities> getCapabilities(String sn) {
			var takeoff = new com.zqnt.sdk.edge.adapter.domains.Capability();
			takeoff.setCommand("flight.takeoff");
			takeoff.setCompletion(com.zqnt.protos.capability.v3.CompletionMode.COMPLETION_MODE_ASYNCHRONOUS);
			takeoff.setCompletionEvent("flight.takeoff.completed");
			var light = new com.zqnt.sdk.edge.adapter.domains.Capability();
			light.setCommand("dock.light");
			light.setCompletion(com.zqnt.protos.capability.v3.CompletionMode.COMPLETION_MODE_ON_REPLY);
			return CompletableFuture.completedFuture(new com.zqnt.sdk.edge.adapter.domains.CurrentCapabilities(
					sn, null, java.util.Set.of(takeoff, light), System.currentTimeMillis()));
		}

		@Override
		public CompletableFuture<CommandResult> sendCustomCommand(String sn, String componentId, String commandType,
				Map<String, Object> params) {
			return "dock.light".equals(commandType)
					? CompletableFuture.completedFuture(CommandResult.success("on", sn))
					: CompletableFuture.completedFuture(CommandResult.notImplemented("unknown " + commandType, sn));
		}
	}

	@Test
	void aDeclaredAsynchronousCommandWaitsEvenWhenItsHandlerAnsweredAPlainSuccess() {
		var response = execute(new DeclaringAdapter(), "flight.takeoff",
				Map.of("latitude", 52.5, "longitude", 13.4, "altitude", 40));

		assertEquals(CommandState.COMMAND_STATE_ACCEPTED, response.getResult().getState());
		assertEquals("cx-1", response.getResult().getCommandExecutionId());
	}

	@Test
	void aDeclaredOnReplyCommandIsDoneOnItsReply() {
		var response = execute(new DeclaringAdapter(), "dock.light", Map.of("on", "true"));

		assertEquals(CommandState.COMMAND_STATE_SUCCEEDED, response.getResult().getState());
	}

	@Test
	void theCompletionModeIsPublishedWithTheCapability() {
		var takeoff = new com.zqnt.sdk.edge.adapter.domains.Capability();
		takeoff.setCommand("flight.takeoff");
		takeoff.setCompletion(com.zqnt.protos.capability.v3.CompletionMode.COMPLETION_MODE_ASYNCHRONOUS);
		takeoff.setCompletionEvent("flight.takeoff.completed");
		takeoff.getEvents().add(new com.zqnt.sdk.edge.adapter.domains.CapabilityEvent("flight.takeoff.completed", "airborne", null));

		var v3 = CapabilityMappers.toV3(takeoff);

		assertEquals(com.zqnt.protos.capability.v3.CompletionMode.COMPLETION_MODE_ASYNCHRONOUS, v3.getCompletion());
		assertEquals("flight.takeoff.completed", v3.getCompletionEvent());
		assertEquals("flight.takeoff.completed", v3.getEvents(0).getName());
	}

	@Test
	void aLongCommandIsAcceptedUnderItsExternalId() {
		var response = execute(new Adapter(), "mission.waypoint.execute", Map.of());

		assertEquals(CommandState.COMMAND_STATE_ACCEPTED, response.getResult().getState());
		assertEquals("dji-77", response.getResult().getResult().getFieldsOrThrow("external_execution_id").getStringValue());
	}

	@Test
	void anAdapterFailureIsFailedWithItsMessage() {
		var response = execute(new Adapter(), "vendor.acme.broken", Map.of());

		assertEquals(CommandState.COMMAND_STATE_FAILED, response.getResult().getState());
		assertEquals("nozzle blocked", response.getResult().getError().getMessage());
	}

	@Test
	void cancelStopsTheExecutionItWasAcceptedUnder() {
		Adapter adapter = new Adapter();
		Capture<CancelCommandResponse> capture = new Capture<>();
		new EdgeAdapterV3GrpcServiceImpl(adapter).cancelCommand(
				CancelCommandRequest.newBuilder().setCommandExecutionId("dji-77").build(), capture);

		assertEquals(CommandState.COMMAND_STATE_CANCELLED, capture.value.getResult().getState());
		assertEquals(List.of("dji-77"), adapter.cancelled);
	}

	private static final class SchemaAdapter implements EdgeAdapterService {
		final List<Map<String, Object>> zooms = new ArrayList<>();

		@Override
		public CompletableFuture<com.zqnt.sdk.edge.adapter.domains.CurrentCapabilities> getCapabilities(String sn) {
			var zoom = new com.zqnt.sdk.edge.adapter.domains.Capability();
			zoom.setCommand("camera.change_zoom");
			zoom.setInputSchema(Map.of("type", "object", "required", List.of("zoom"),
					"properties", Map.of("zoom", Map.of("type", "integer", "minimum", 1))));
			return CompletableFuture.completedFuture(new com.zqnt.sdk.edge.adapter.domains.CurrentCapabilities(
					sn, null, java.util.Set.of(zoom), System.currentTimeMillis()));
		}

		@Override
		public CompletableFuture<CommandResult> sendCustomCommand(String sn, String componentId, String commandType,
				Map<String, Object> params) {
			zooms.add(params);
			return CompletableFuture.completedFuture(CommandResult.success("zoomed", sn));
		}
	}

	@Test
	void anAdvertisedSchemaValidatesParamsOfALegacyAdapterToo() {
		SchemaAdapter adapter = new SchemaAdapter();

		var rejected = execute(adapter, "camera.change_zoom", Map.of("zoom", 0));
		var accepted = execute(adapter, "camera.change_zoom", Map.of("zoom", 4));

		assertEquals(CommandState.COMMAND_STATE_REJECTED, rejected.getResult().getState());
		assertEquals("command.invalid_params", rejected.getResult().getError().getCode());
		assertEquals(CommandState.COMMAND_STATE_SUCCEEDED, accepted.getResult().getState());
		assertEquals(List.of(Map.of("zoom", 4)), adapter.zooms);
	}

	@Test
	void cancelReachesTheAdapterUnderItsOwnIdForACommandAcceptedOverV3() {
		Adapter adapter = new Adapter();
		var executions = new com.zqnt.sdk.edge.gateway.CommandExecutions(java.time.Duration.ofMinutes(5));
		var service = new EdgeAdapterV3GrpcServiceImpl(adapter, executions);
		Capture<ExecuteCommandResponse> accepted = new Capture<>();
		service.executeCommand(request("mission.waypoint.execute", Map.of()).toBuilder()
				.setCommandExecutionId("capexec:run-9:node-2").build(), accepted);

		Capture<CancelCommandResponse> cancelled = new Capture<>();
		service.cancelCommand(CancelCommandRequest.newBuilder().setCommandExecutionId("capexec:run-9:node-2").build(), cancelled);

		assertEquals(CommandState.COMMAND_STATE_CANCELLED, cancelled.value.getResult().getState());
		assertEquals("mission.waypoint.execute", cancelled.value.getResult().getCommandId());
		assertEquals(List.of("dji-77"), adapter.cancelled);
		assertTrue(executions.get("capexec:run-9:node-2").isEmpty());
	}
}
