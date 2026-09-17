package com.zqnt.sdk.edge.adapter.application;

import com.zqnt.sdk.edge.adapter.domains.*;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Routes a vendor-neutral {@code command_id} onto the typed {@link EdgeAdapterService} method that
 * already implements it.
 *
 * <h2>Why this exists</h2>
 * Adapters advertise dotted command ids through {@code getCapabilities} — edge-dji publishes 23 of
 * them — but those ids were only ever executable through the typed RPCs. Send the same adapter a
 * {@code SendCustomCommand{command_id: "flight.takeoff"}} and it answered "not registered", even
 * though it had just advertised {@code flight.takeoff} and implements {@code takeOff}. Advertisement
 * and execution disagreed, which is the same defect in the opposite direction from an adapter
 * answering a command it never advertises.
 *
 * <p>Wiring the ids to the methods here closes that gap without a proto change and without touching
 * any adapter's existing implementations: a vendor adapter keeps writing {@code takeOff(...)} and
 * automatically answers the advertised id too. It is also the prerequisite for deleting core's
 * hand-maintained dotted-id-to-typed-stub switch in {@code EdgeExecutionNodeDispatcher} — once every
 * adapter answers its own advertised ids, the platform no longer needs a translation table.
 *
 * <p>Params follow the same JSON Schemas the adapters publish for these commands (see edge-dji's
 * {@code builtInSchema} and the Python SDK's command catalog), so one command means one thing across
 * every SDK.
 */
public final class BuiltInCommandDispatch {

	private BuiltInCommandDispatch() {
	}

	/**
	 * Execute {@code commandType} against {@code adapter}'s typed implementation.
	 *
	 * @return the pending result, or {@link Optional#empty()} when the id is not a built-in — the
	 * caller then continues with its own routing (payload commands, property setters, vendor ids).
	 * Empty means "not mine", never "failed": an adapter that has not implemented the underlying
	 * typed method still returns a NOT_IMPLEMENTED result through the normal path.
	 */
	public static Optional<CompletableFuture<CommandResult>> dispatch(
			EdgeAdapterService adapter, String sn, String commandType, Map<String, Object> params) {
		Map<String, Object> safeParams = params == null ? Map.of() : params;
		return Optional.ofNullable(switch (commandType) {
			case "flight.takeoff" -> adapter.takeOff(TakeOffRequest.builder()
					.sn(sn)
					.coordinates(coordinates(safeParams))
					.build());
			case "navigation.go_to" -> adapter.goTo(GoToRequest.builder()
					.sn(sn)
					.coordinates(coordinates(safeParams))
					.build());
			case "flight.return_to_home" -> adapter.returnToHome(ReturnToHomeRequest.builder()
					.sn(sn)
					.altitude(floatValue(safeParams, "altitude"))
					.build());
			case "flight.manual.enter" -> adapter.enterManualControl(sn);
			case "flight.manual.exit" -> adapter.exitManualControl(sn);
			case "gimbal.look_at" -> adapter.lookAt(LookAtRequest.builder()
					.sn(sn)
					.latitude(doubleValue(safeParams, "latitude"))
					.longitude(doubleValue(safeParams, "longitude"))
					.altitude(floatValue(safeParams, "altitude"))
					.locked(boolValue(safeParams, "locked"))
					.payloadIndex(stringValue(safeParams, "payloadIndex"))
					.build());
			case "gimbal.tracking" -> adapter.enableGimbalTracking(sn, Boolean.TRUE.equals(boolValue(safeParams, "enabled")));
			case "camera.take_photo" -> adapter.takePhoto(TakePhotoRequest.builder().sn(sn).build());
			case "camera.change_lens" -> adapter.changeLens(ChangeLensRequest.builder()
					.sn(sn)
					.lens(stringValue(safeParams, "lens"))
					.videoId(stringValue(safeParams, "videoId"))
					.build());
			case "camera.change_zoom" -> adapter.changeZoom(ChangeZoomRequest.builder()
					.sn(sn)
					.lens(stringValue(safeParams, "lens"))
					.payloadIndex(stringValue(safeParams, "payloadIndex"))
					.zoom(floatValue(safeParams, "zoom"))
					.build());
			case "stream.start" -> adapter.startLiveStream(LiveStreamStartRequest.builder()
					.sn(sn)
					.videoId(stringValue(safeParams, "videoId"))
					.streamServer(stringValue(safeParams, "streamServer"))
					.videoType(stringValue(safeParams, "videoType"))
					.build());
			case "stream.stop" -> adapter.stopLiveStream(LiveStreamStopRequest.builder()
					.sn(sn)
					.videoId(stringValue(safeParams, "videoId"))
					.build());
			case "stream.split_screen" -> adapter.liveStreamSplitScreen(sn, Boolean.TRUE.equals(boolValue(safeParams, "enabled")));
			case "dock.open_cover" -> adapter.openCover(sn);
			case "dock.close_cover" -> adapter.closeCover(sn, boolValue(safeParams, "force"));
			case "dock.start_charging" -> adapter.startCharging(sn);
			case "dock.stop_charging" -> adapter.stopCharging(sn);
			case "asset.reboot" -> adapter.rebootAsset(sn);
			// One id, two typed methods — the params decide which, exactly as the published
			// schema ({"enabled": boolean}) says.
			case "asset.boot_sub_asset" -> Boolean.TRUE.equals(boolValue(safeParams, "enabled"))
					? adapter.bootUpSubAsset(sn)
					: adapter.bootDownSubAsset(sn);
			case "asset.remote_debug" -> Boolean.TRUE.equals(boolValue(safeParams, "enabled"))
					? adapter.enterRemoteDebugMode(sn)
					: adapter.closeRemoteDebugMode(sn);
			case "asset.change_ac_mode" -> adapter.changeAcMode(sn, stringValue(safeParams, "mode"));
			default -> null;
		});
	}

	/** True when {@code commandType} is one of the ids {@link #dispatch} can route. */
	public static boolean isBuiltIn(String commandType) {
		return BUILT_IN_IDS.contains(commandType);
	}

	private static final java.util.Set<String> BUILT_IN_IDS = java.util.Set.of(
			"flight.takeoff", "navigation.go_to", "flight.return_to_home",
			"flight.manual.enter", "flight.manual.exit",
			"gimbal.look_at", "gimbal.tracking",
			"camera.take_photo", "camera.change_lens", "camera.change_zoom",
			"stream.start", "stream.stop", "stream.split_screen",
			"dock.open_cover", "dock.close_cover", "dock.start_charging", "dock.stop_charging",
			"asset.reboot", "asset.boot_sub_asset", "asset.remote_debug", "asset.change_ac_mode");

	private static Coordinates coordinates(Map<String, Object> params) {
		Coordinates coordinates = new Coordinates();
		coordinates.setLatitude(doubleValue(params, "latitude"));
		coordinates.setLongitude(doubleValue(params, "longitude"));
		coordinates.setAltitude(doubleValue(params, "altitude"));
		return coordinates;
	}

	// Params arrive as a decoded protobuf Struct, so every number is a Double regardless of what
	// the caller wrote; accept any Number rather than assuming one.
	private static Double doubleValue(Map<String, Object> params, String key) {
		Object value = params.get(key);
		return value instanceof Number number ? number.doubleValue() : null;
	}

	private static Float floatValue(Map<String, Object> params, String key) {
		Object value = params.get(key);
		return value instanceof Number number ? number.floatValue() : null;
	}

	private static Boolean boolValue(Map<String, Object> params, String key) {
		Object value = params.get(key);
		return value instanceof Boolean bool ? bool : null;
	}

	private static String stringValue(Map<String, Object> params, String key) {
		Object value = params.get(key);
		return value == null ? null : String.valueOf(value);
	}
}
