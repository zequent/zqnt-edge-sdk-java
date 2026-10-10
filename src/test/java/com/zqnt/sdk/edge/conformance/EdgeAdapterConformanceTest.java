package com.zqnt.sdk.edge.conformance;

import com.google.protobuf.Timestamp;
import com.zqnt.protos.capability.v3.CommandEvent;
import com.zqnt.protos.capability.v3.CommandState;
import com.zqnt.protos.common.v3.AssetRef;
import com.zqnt.sdk.edge.adapter.application.EdgeAdapterService;
import com.zqnt.sdk.edge.adapter.domains.Capability;
import com.zqnt.sdk.edge.adapter.domains.CommandResult;
import com.zqnt.sdk.edge.adapter.domains.CurrentCapabilities;
import com.zqnt.sdk.edge.adapter.domains.GoToRequest;
import com.zqnt.sdk.edge.adapter.registry.RegistryEdgeAdapterTest;
import com.zqnt.utils.events.proto.CommandExecutionEvent;
import com.zqnt.utils.events.proto.CommandExecutionStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class EdgeAdapterConformanceTest {

	@Test
	void aRegistryAdapterConformsByConstruction() {
		EdgeAdapterConformance.check(new RegistryEdgeAdapterTest.Drone(), "SN-1").assertPassed();
	}

	/** Advertises flight.takeoff but cannot run it; runs navigation.go_to but does not advertise it. */
	private static final class DriftingAdapter implements EdgeAdapterService {
		@Override
		public CompletableFuture<CurrentCapabilities> getCapabilities(String sn) {
			Capability takeoff = new Capability();
			takeoff.setCommand("flight.takeoff");
			Capability zoom = new Capability();
			zoom.setCommand("camera.change_zoom");
			zoom.setInputSchema(Map.of("type", "object", "required", List.of("zoom"),
					"properties", Map.of("zoom", Map.of("type", "integer", "minimum", 1, "maximum", 1)),
					"additionalProperties", Map.of("type", "strng")));
			Capability rth = new Capability();
			rth.setCommand("flight.return_to_home");
			rth.setCompletion(com.zqnt.protos.capability.v3.CompletionMode.COMPLETION_MODE_ASYNCHRONOUS);
			rth.setCompletionEvent("flight.return_to_home.completed");
			return CompletableFuture.completedFuture(new CurrentCapabilities(sn, null, Set.of(takeoff, zoom, rth), 0));
		}

		@Override
		@SuppressWarnings("deprecation")
		public CompletableFuture<CommandResult> goTo(GoToRequest request) {
			return CompletableFuture.completedFuture(CommandResult.success("flying", request.getSn()));
		}

		@Override
		public CompletableFuture<CommandResult> sendCustomCommand(String sn, String componentId, String commandType,
				Map<String, Object> params) {
			return "camera.change_zoom".equals(commandType) || "flight.return_to_home".equals(commandType)
					? CompletableFuture.completedFuture(CommandResult.success("ok", sn))
					: CompletableFuture.completedFuture(CommandResult.notImplemented("unknown", sn));
		}
	}

	@Test
	void advertisementAndExecutionDriftIsFound() {
		var report = EdgeAdapterConformance.check(new DriftingAdapter(), "SN-1");

		assertFalse(report.passed());
		assertTrue(report.problems().contains("flight.takeoff is advertised but not executable"), report.problems().toString());
		assertTrue(report.problems().contains("navigation.go_to is executable but not advertised"), report.problems().toString());
		assertTrue(report.problems().contains("camera.change_zoom input schema.additionalProperties.type has an unknown type \"strng\""),
				report.problems().toString());
		assertTrue(report.problems().contains(
				"flight.return_to_home completes with flight.return_to_home.completed but does not declare that event"),
				report.problems().toString());
		assertThrows(AssertionError.class, report::assertPassed);
	}

	@Test
	void aCommandEventWithoutOccurredAtIsFound() {
		var report = EdgeAdapterConformance.checkCommandEvents(List.of(
				CommandEvent.newBuilder().setCommandExecutionId("capexec:r:n").setAsset(AssetRef.newBuilder().setSn("SN-1"))
						.setState(CommandState.COMMAND_STATE_SUCCEEDED).setOccurredAt(Timestamp.newBuilder().setSeconds(1)).build(),
				CommandEvent.newBuilder().setCommandExecutionId("capexec:r:n").setCommandId("flight.takeoff")
						.setAsset(AssetRef.newBuilder().setSn("SN-1")).setState(CommandState.COMMAND_STATE_SUCCEEDED).build()));

		assertEquals(List.of("event 1 (flight.takeoff) has no occurred_at"), report.problems());
	}

	@Test
	void theSdkGivesAnAdaptersCompletionEventItsOccurredAt() {
		var report = EdgeAdapterConformance.checkCommandExecutionEvents("SN-1", List.of(CommandExecutionEvent.newBuilder()
				.setExternalExecutionId("flight-7").setCommandId("flight.takeoff")
				.setStatus(CommandExecutionStatus.COMMAND_EXECUTION_STATUS_SUCCEEDED).build()));

		report.assertPassed();
	}
}
