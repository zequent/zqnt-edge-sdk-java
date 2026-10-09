package com.zqnt.sdk.edge.adapter.registry;

import com.zqnt.protos.capability.v3.TelemetryField;
import com.zqnt.protos.capability.v3.TelemetryValueType;
import com.zqnt.sdk.edge.adapter.domains.CommandResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CommandRegistryTest {

	private static final Map<String, Object> ZOOM_INPUT = Map.of("type", "object", "required", List.of("factor"),
			"properties", Map.of("factor", Map.of("type", "integer", "minimum", 1, "maximum", 56)));

	@Test
	void theHandlerSeesValidatedAndCoercedParams() {
		CommandRegistry registry = new CommandRegistry();
		List<Map<String, Object>> seen = new ArrayList<>();
		registry.register("camera.change_zoom", ZOOM_INPUT, Map.of(), request -> {
			seen.add(request.params());
			return CompletableFuture.completedFuture(CommandResult.success("zoomed", request.sn(), Map.of("factor", 4)));
		});

		CommandResult result = registry.execute(new CommandRequest("SN-1", null, "camera.change_zoom",
				Map.of("factor", 4.0), "capexec:e:n")).join();

		assertTrue(result.isSuccess());
		assertEquals(4, seen.get(0).get("factor"));
		assertEquals(Map.of("factor", 4), result.getOutput());
	}

	@Test
	void invalidParamsAreRejectedBeforeTheHandlerRuns() {
		CommandRegistry registry = new CommandRegistry();
		AtomicInteger calls = new AtomicInteger();
		registry.register("camera.change_zoom", ZOOM_INPUT, Map.of(), request -> {
			calls.incrementAndGet();
			return CompletableFuture.completedFuture(CommandResult.success("zoomed", request.sn()));
		});

		CommandResult result = registry.execute(new CommandRequest("SN-1", null, "camera.change_zoom",
				Map.of("factor", 99.0), null)).join();

		assertTrue(result.isRejected());
		assertEquals(CommandRegistry.INVALID_PARAMS, result.getErrorCode());
		assertEquals("camera.change_zoom: factor must be at most 56", result.getMessage());
		assertEquals(0, calls.get());
	}

	@Test
	void anUnregisteredIdIsNotImplemented() {
		CommandResult result = new CommandRegistry().execute(new CommandRequest("SN-1", null, "flight.takeoff", Map.of(), null)).join();

		assertTrue(result.isNotImplemented());
	}

	@Test
	void aSchemaThatDoesNotParseIsRefusedAtRegistration() {
		CommandRegistry registry = new CommandRegistry();

		var error = assertThrows(IllegalArgumentException.class, () -> registry.register("camera.change_zoom",
				Map.of("type", "integr"), Map.of(), request -> null));
		assertTrue(error.getMessage().startsWith("camera.change_zoom: schema.type has an unknown type"));
		assertFalse(registry.contains("camera.change_zoom"));
	}

	@Test
	void aHandlerThatThrowsFailsTheCommandInsteadOfTheCaller() {
		CommandRegistry registry = new CommandRegistry();
		registry.register("dock.open_cover", Map.of(), Map.of(), request -> {
			throw new IllegalStateException("cover jammed");
		});

		var future = registry.execute(new CommandRequest("SN-1", null, "dock.open_cover", Map.of(), null));

		assertTrue(future.isCompletedExceptionally());
	}

	@Test
	void listenersHearEveryChange() {
		CommandRegistry registry = new CommandRegistry();
		AtomicInteger changes = new AtomicInteger();
		Runnable unsubscribe = registry.onChange(changes::incrementAndGet);

		registry.register("dock.open_cover", Map.of(), Map.of(), request -> null);
		registry.declareTelemetryField(TelemetryField.newBuilder().setKey("dock.cover_state")
				.setType(TelemetryValueType.TELEMETRY_VALUE_TYPE_STRING).build());
		registry.unregister("dock.open_cover");
		unsubscribe.run();
		registry.changed();

		assertEquals(3, changes.get());
		assertEquals("dock.cover_state", registry.telemetryFields().get(0).getKey());
	}

	@Test
	void requestsAreValues() {
		var request = new CommandRequest("SN-1", null, "dock.open_cover", Map.of("force", true), "capexec:r:n");

		assertEquals(request, new CommandRequest("SN-1", null, "dock.open_cover", Map.of("force", true), "capexec:r:n"));
		assertEquals("CommandRequest[sn=SN-1, targetRef=null, commandId=dock.open_cover, params={force=true}, "
				+ "commandExecutionId=capexec:r:n]", request.toString());
		assertEquals(Map.of(), new CommandRequest("SN-1", null, "x", null, null).params());
	}
}
