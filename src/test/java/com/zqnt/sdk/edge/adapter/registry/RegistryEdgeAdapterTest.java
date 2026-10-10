package com.zqnt.sdk.edge.adapter.registry;

import com.zqnt.protos.capability.v3.Command;
import com.zqnt.protos.capability.v3.CommandState;
import com.zqnt.protos.common.v3.AssetRef;
import com.zqnt.protos.common.v3.ErrorCategory;
import com.zqnt.protos.edge.v3.ExecuteCommandRequest;
import com.zqnt.protos.edge.v3.ExecuteCommandResponse;
import com.zqnt.protos.edge.v3.GetCapabilitiesRequest;
import com.zqnt.protos.edge.v3.GetCapabilitiesResponse;
import com.zqnt.sdk.edge.adapter.application.RegistryEdgeAdapter;
import com.zqnt.sdk.edge.adapter.domains.CommandResult;
import com.zqnt.sdk.edge.adapter.domains.Coordinates;
import com.zqnt.sdk.edge.adapter.domains.TakeOffRequest;
import com.zqnt.sdk.edge.adapter.grpc.EdgeAdapterGrpcServiceImpl;
import com.zqnt.sdk.edge.adapter.grpc.EdgeAdapterV3GrpcServiceImpl;
import com.zqnt.sdk.edge.gateway.CommandExecutions;
import com.zqnt.sdk.edge.support.Structs;
import com.zqnt.utils.common.proto.AssetTypeEnum;
import com.zqnt.utils.devicecontrol.proto.AssetCapabilitiesRequest;
import com.zqnt.utils.devicecontrol.proto.AssetCapabilitiesResponse;
import com.zqnt.utils.devicecontrol.proto.CustomCommandRequest;
import com.zqnt.utils.devicecontrol.proto.CustomCommandResponse;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An adapter writes each command once, in its registry. v2 typed RPCs, v2 SendCustomCommand and v3
 * ExecuteCommand all reach the same handler, and what is advertised is exactly what is registered.
 */
public class RegistryEdgeAdapterTest {

	static final Map<String, Object> TAKEOFF_INPUT = Map.of("type", "object",
			"properties", Map.of(
					"latitude", Map.of("type", "number"),
					"longitude", Map.of("type", "number"),
					"altitude", Map.of("type", "number", "minimum", 2, "maximum", 500)));

	public static final class Drone extends RegistryEdgeAdapter {
		final List<CommandRequest> takeoffs = new ArrayList<>();

		public Drone() {
			super(AssetTypeEnum.ASSET_TYPE_AIRCRAFT);
			registerCommand("flight.takeoff", TAKEOFF_INPUT, Map.of(), request -> {
				takeoffs.add(request);
				return CompletableFuture.completedFuture(CommandResult.accepted("climbing", "flight-7", request.sn()));
			});
			registerCommand("vendor.acme.beep", Map.of(), Map.of("type", "object",
					"properties", Map.of("beeps", Map.of("type", "integer"))), request ->
					CompletableFuture.completedFuture(CommandResult.success("beeped", request.sn(), Map.of("beeps", 3))));
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

	private static ExecuteCommandResponse executeV3(EdgeAdapterV3GrpcServiceImpl service, String commandId,
			Map<String, Object> params) {
		Capture<ExecuteCommandResponse> capture = new Capture<>();
		service.executeCommand(ExecuteCommandRequest.newBuilder()
				.setCommandExecutionId("capexec:run-1:node-1")
				.setCommand(Command.newBuilder().setAsset(AssetRef.newBuilder().setSn("SN-1")).setCommandId(commandId)
						.setParams(Structs.toStruct(params)))
				.build(), capture);
		return capture.value;
	}

	@Test
	void theV2TypedTakeOffRunsTheRegisteredHandler() {
		Drone drone = new Drone();
		Coordinates coordinates = new Coordinates(52.5, 13.4, Double.NaN);

		CommandResult result = drone.takeOff(TakeOffRequest.builder().sn("SN-1").coordinates(coordinates).build()).join();

		assertTrue(result.isAccepted());
		assertEquals(Map.of("latitude", 52.5, "longitude", 13.4), drone.takeoffs.get(0).params());
		assertNull(drone.takeoffs.get(0).commandExecutionId());
	}

	@Test
	void v3ExecuteCommandReachesTheHandlerWithThePlatformsExecutionId() {
		Drone drone = new Drone();
		CommandExecutions executions = new CommandExecutions(Duration.ofMinutes(5));

		var response = executeV3(new EdgeAdapterV3GrpcServiceImpl(drone, executions), "flight.takeoff",
				Map.of("latitude", 52.5, "longitude", 13.4, "altitude", 40));

		assertEquals(CommandState.COMMAND_STATE_ACCEPTED, response.getResult().getState());
		assertEquals("capexec:run-1:node-1", drone.takeoffs.get(0).commandExecutionId());
		assertEquals("capexec:run-1:node-1", executions.resolve("SN-1", "flight.takeoff", "flight-7").orElseThrow().commandExecutionId());
	}

	@Test
	void v3InvalidParamsAreRejectedWithTheirReason() {
		Drone drone = new Drone();

		var response = executeV3(new EdgeAdapterV3GrpcServiceImpl(drone), "flight.takeoff", Map.of("altitude", 900));

		assertEquals(CommandState.COMMAND_STATE_REJECTED, response.getResult().getState());
		assertEquals(CommandRegistry.INVALID_PARAMS, response.getResult().getError().getCode());
		assertEquals(ErrorCategory.ERROR_CATEGORY_INVALID_ARGUMENT, response.getResult().getError().getCategory());
		assertEquals("flight.takeoff: altitude must be at most 500", response.getResult().getError().getMessage());
		assertTrue(drone.takeoffs.isEmpty());
	}

	@Test
	void aSucceededCommandCarriesItsOutput() {
		var response = executeV3(new EdgeAdapterV3GrpcServiceImpl(new Drone()), "vendor.acme.beep", Map.of());

		assertEquals(CommandState.COMMAND_STATE_SUCCEEDED, response.getResult().getState());
		assertEquals(3.0, response.getResult().getResult().getFieldsOrThrow("beeps").getNumberValue());
	}

	@Test
	void capabilitiesAreExactlyTheRegistry() {
		Capture<GetCapabilitiesResponse> v3 = new Capture<>();
		new EdgeAdapterV3GrpcServiceImpl(new Drone()).getCapabilities(GetCapabilitiesRequest.newBuilder()
				.setAsset(AssetRef.newBuilder().setSn("SN-1")).build(), v3);
		Capture<AssetCapabilitiesResponse> v2 = new Capture<>();
		new EdgeAdapterGrpcServiceImpl(new Drone(), null).getCapabilities(
				AssetCapabilitiesRequest.newBuilder().setSn("SN-1").build(), v2);

		assertEquals(List.of("flight.takeoff", "vendor.acme.beep"), v3.value.getCapabilities().getCapabilitiesList().stream()
				.map(com.zqnt.protos.capability.v3.Capability::getCommandId).toList());
		assertEquals("ASSET_TYPE_AIRCRAFT", v3.value.getCapabilities().getAssetType());
		assertEquals(List.of("flight.takeoff", "vendor.acme.beep"), v2.value.getCapabilities().getCapabilitiesList().stream()
				.map(com.zqnt.utils.devicecontrol.proto.Capability::getCommandId).toList());
	}

	@Test
	void v2SendCustomCommandRunsTheSameHandler() {
		Capture<CustomCommandResponse> capture = new Capture<>();
		new EdgeAdapterGrpcServiceImpl(new Drone(), null).sendCustomCommand(CustomCommandRequest.newBuilder()
				.setBase(com.zqnt.utils.common.proto.RequestBase.newBuilder().setSn("SN-1").setTid("t-1"))
				.setCommandId("vendor.acme.beep").build(), capture);

		assertFalse(capture.value.getHasErrors());
	}
}
