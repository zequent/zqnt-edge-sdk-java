package com.zqnt.sdk.edge.application;

import com.zqnt.sdk.edge.adapter.application.BuiltInCommandDispatch;
import com.zqnt.sdk.edge.adapter.application.EdgeAdapterService;
import com.zqnt.sdk.edge.adapter.domains.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An adapter that advertises {@code flight.takeoff} has to be able to execute {@code flight.takeoff}.
 * Before this dispatch existed, edge-dji advertised 23 dotted ids and answered "not registered" to
 * every one of them sent through SendCustomCommand, because they were reachable only through the
 * typed RPCs.
 */
class BuiltInCommandDispatchTest {

	/** Records which typed method was invoked, with what. */
	private static final class RecordingAdapter implements EdgeAdapterService {
		private final List<String> calls = new ArrayList<>();
		private Object lastRequest;

		private CompletableFuture<CommandResult> record(String name, Object request) {
			calls.add(name);
			lastRequest = request;
			return CompletableFuture.completedFuture(CommandResult.success("ok", "SN-1"));
		}

		@Override
		public CompletableFuture<CommandResult> takeOff(TakeOffRequest request) {
			return record("takeOff", request);
		}

		@Override
		public CompletableFuture<CommandResult> goTo(GoToRequest request) {
			return record("goTo", request);
		}

		@Override
		public CompletableFuture<CommandResult> returnToHome(ReturnToHomeRequest request) {
			return record("returnToHome", request);
		}

		@Override
		public CompletableFuture<CommandResult> bootUpSubAsset(String sn) {
			return record("bootUpSubAsset", sn);
		}

		@Override
		public CompletableFuture<CommandResult> bootDownSubAsset(String sn) {
			return record("bootDownSubAsset", sn);
		}

		@Override
		public CompletableFuture<CommandResult> closeCover(String sn, Boolean force) {
			return record("closeCover", force);
		}

		@Override
		public CompletableFuture<CommandResult> changeZoom(ChangeZoomRequest request) {
			return record("changeZoom", request);
		}
	}

	@Test
	void routesAnAdvertisedIdOntoItsTypedMethod() {
		RecordingAdapter adapter = new RecordingAdapter();

		Optional<CompletableFuture<CommandResult>> result = BuiltInCommandDispatch.dispatch(
				adapter, "SN-1", "flight.takeoff",
				Map.of("latitude", 47.1, "longitude", 8.5, "altitude", 30.0));

		assertTrue(result.isPresent());
		assertEquals(List.of("takeOff"), adapter.calls);
		TakeOffRequest request = (TakeOffRequest) adapter.lastRequest;
		assertEquals("SN-1", request.getSn());
		assertEquals(47.1, request.getCoordinates().getLatitude());
		assertEquals(30.0, request.getCoordinates().getAltitude());
	}

	@Test
	void returnsEmptyForAnIdItDoesNotOwn() {
		RecordingAdapter adapter = new RecordingAdapter();

		Optional<CompletableFuture<CommandResult>> result =
				BuiltInCommandDispatch.dispatch(adapter, "SN-1", "vendor.acme.spray", Map.of());

		// Empty means "not mine, keep routing" — the caller still has payload and property commands
		// to try. It must never be confused with a failure.
		assertTrue(result.isEmpty());
		assertTrue(adapter.calls.isEmpty());
	}

	@Test
	void picksTheTypedMethodFromTheParamsWhenOneIdCoversTwo() {
		RecordingAdapter up = new RecordingAdapter();
		RecordingAdapter down = new RecordingAdapter();

		BuiltInCommandDispatch.dispatch(up, "SN-1", "asset.boot_sub_asset", Map.of("enabled", true));
		BuiltInCommandDispatch.dispatch(down, "SN-1", "asset.boot_sub_asset", Map.of("enabled", false));

		assertEquals(List.of("bootUpSubAsset"), up.calls);
		assertEquals(List.of("bootDownSubAsset"), down.calls);
	}

	@Test
	void toleratesMissingAndNullParams() {
		RecordingAdapter adapter = new RecordingAdapter();

		BuiltInCommandDispatch.dispatch(adapter, "SN-1", "dock.close_cover", null);

		assertEquals(List.of("closeCover"), adapter.calls);
		assertNull(adapter.lastRequest, "an absent 'force' must stay null rather than defaulting to false");
	}

	@Test
	void narrowsStructNumbersToTheFieldType() {
		RecordingAdapter adapter = new RecordingAdapter();

		// A decoded protobuf Struct hands every number over as a Double, including one the request
		// object declares as a Float.
		BuiltInCommandDispatch.dispatch(adapter, "SN-1", "camera.change_zoom",
				Map.of("lens", "zoom", "zoom", 4.5d));

		ChangeZoomRequest request = (ChangeZoomRequest) adapter.lastRequest;
		assertEquals(4.5f, request.getZoom());
		assertEquals("zoom", request.getLens());
	}

	@Test
	void anUnimplementedTypedMethodStillAnswersThroughTheNormalPath() {
		// gimbal.look_at is routable but this adapter does not implement lookAt: the caller must get
		// the interface's NOT_IMPLEMENTED result, not an empty Optional that looks like "unknown id".
		RecordingAdapter adapter = new RecordingAdapter();

		Optional<CompletableFuture<CommandResult>> result = BuiltInCommandDispatch.dispatch(
				adapter, "SN-1", "gimbal.look_at", Map.of("latitude", 1.0, "longitude", 2.0));

		assertTrue(result.isPresent());
		assertTrue(result.get().join().isNotImplemented());
	}

	@Test
	void everyRoutableIdIsReportedAsBuiltIn() {
		assertTrue(BuiltInCommandDispatch.isBuiltIn("flight.takeoff"));
		assertTrue(BuiltInCommandDispatch.isBuiltIn("asset.change_ac_mode"));
		assertFalse(BuiltInCommandDispatch.isBuiltIn("mission.waypoint.execute"));
		assertFalse(BuiltInCommandDispatch.isBuiltIn("vendor.acme.spray"));
	}
}
