package com.zqnt.sdk.edge.adapter.application;

import com.zqnt.sdk.edge.adapter.domains.*;
import com.zqnt.sdk.edge.adapter.registry.CommandHandler;
import com.zqnt.sdk.edge.adapter.registry.CommandRegistry;
import com.zqnt.sdk.edge.adapter.registry.CommandRequest;
import com.zqnt.utils.common.proto.AssetTypeEnum;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Base class for an adapter that writes each command once, in its {@link CommandRegistry}:
 *
 * <pre>{@code
 * public class AcmeAdapter extends RegistryEdgeAdapter {
 *     public AcmeAdapter() {
 *         super(AssetTypeEnum.ASSET_TYPE_AIRCRAFT);
 *         registerCommand("flight.takeoff", TAKEOFF_INPUT, Map.of(), this::takeOff);
 *     }
 * }
 * }</pre>
 *
 * Capabilities (v2 and v3) are derived from the registry only. The v2 typed RPCs and
 * {@code SendCustomCommand} are routed onto the same handlers, with the typed request turned into
 * the params of the matching built-in id ({@code takeOff} becomes {@code flight.takeoff} with
 * latitude/longitude/altitude), so a command behaves the same whichever contract the platform speaks.
 */
@SuppressWarnings("deprecation")
public abstract class RegistryEdgeAdapter implements EdgeAdapterService {

	private final CommandRegistry commands = new CommandRegistry();
	private final AssetTypeEnum assetType;

	protected RegistryEdgeAdapter(AssetTypeEnum assetType) {
		this.assetType = assetType == null ? AssetTypeEnum.ASSET_TYPE_UNKNOWN : assetType;
	}

	public CommandRegistry commands() {
		return commands;
	}

	@Override
	public final Optional<CommandRegistry> commandRegistry() {
		return Optional.of(commands);
	}

	protected Capability registerCommand(String id, Map<String, Object> inputSchema, Map<String, Object> outputSchema,
			CommandHandler handler) {
		return commands.register(id, inputSchema, outputSchema, handler);
	}

	@Override
	public CompletableFuture<CurrentCapabilities> getCapabilities(String sn) {
		CurrentCapabilities current = CurrentCapabilities.of(sn, assetType, commands.capabilities());
		current.setTelemetryFields(commands.telemetryFields());
		return CompletableFuture.completedFuture(current);
	}

	@Override
	public final CompletableFuture<CommandResult> sendCustomCommand(String sn, String componentId, String commandType,
			Map<String, Object> params) {
		return commands.execute(new CommandRequest(sn, componentId, commandType, params, null));
	}

	private CompletableFuture<CommandResult> run(String sn, String commandId, Map<String, Object> params) {
		return commands.execute(new CommandRequest(sn, null, commandId, params, null));
	}

	@Override
	public final CompletableFuture<CommandResult> takeOff(TakeOffRequest request) {
		return run(request.getSn(), "flight.takeoff", coordinates(request.getCoordinates()));
	}

	@Override
	public final CompletableFuture<CommandResult> goTo(GoToRequest request) {
		return run(request.getSn(), "navigation.go_to", coordinates(request.getCoordinates()));
	}

	@Override
	public final CompletableFuture<CommandResult> returnToHome(ReturnToHomeRequest request) {
		return run(request.getSn(), "flight.return_to_home", params("altitude", request.getAltitude()));
	}

	@Override
	public final CompletableFuture<CommandResult> enterManualControl(String sn) {
		return run(sn, "flight.manual.enter", Map.of());
	}

	@Override
	public final CompletableFuture<CommandResult> exitManualControl(String sn) {
		return run(sn, "flight.manual.exit", Map.of());
	}

	@Override
	public final CompletableFuture<CommandResult> lookAt(LookAtRequest request) {
		return run(request.getSn(), "gimbal.look_at", params("latitude", request.getLatitude(),
				"longitude", request.getLongitude(), "altitude", request.getAltitude(),
				"locked", request.getLocked(), "payloadIndex", request.getPayloadIndex()));
	}

	@Override
	public final CompletableFuture<CommandResult> enableGimbalTracking(String sn, boolean enabled) {
		return run(sn, "gimbal.tracking", params("enabled", enabled));
	}

	@Override
	public final CompletableFuture<CommandResult> takePhoto(TakePhotoRequest request) {
		return run(request.getSn(), "camera.take_photo", Map.of());
	}

	@Override
	public final CompletableFuture<CommandResult> changeLens(ChangeLensRequest request) {
		return run(request.getSn(), "camera.change_lens", params("lens", request.getLens(), "videoId", request.getVideoId()));
	}

	@Override
	public final CompletableFuture<CommandResult> changeZoom(ChangeZoomRequest request) {
		return run(request.getSn(), "camera.change_zoom", params("lens", request.getLens(),
				"payloadIndex", request.getPayloadIndex(), "zoom", request.getZoom()));
	}

	@Override
	public final CompletableFuture<CommandResult> startLiveStream(LiveStreamStartRequest request) {
		return run(request.getSn(), "stream.start", params("videoId", request.getVideoId(),
				"streamServer", request.getStreamServer(), "videoType", request.getVideoType()));
	}

	@Override
	public final CompletableFuture<CommandResult> stopLiveStream(LiveStreamStopRequest request) {
		return run(request.getSn(), "stream.stop", params("videoId", request.getVideoId()));
	}

	@Override
	public final CompletableFuture<CommandResult> liveStreamSplitScreen(String sn, boolean enabled) {
		return run(sn, "stream.split_screen", params("enabled", enabled));
	}

	@Override
	public final CompletableFuture<CommandResult> openCover(String sn) {
		return run(sn, "dock.open_cover", Map.of());
	}

	@Override
	public final CompletableFuture<CommandResult> closeCover(String sn, Boolean force) {
		return run(sn, "dock.close_cover", params("force", force));
	}

	@Override
	public final CompletableFuture<CommandResult> startCharging(String sn) {
		return run(sn, "dock.start_charging", Map.of());
	}

	@Override
	public final CompletableFuture<CommandResult> stopCharging(String sn) {
		return run(sn, "dock.stop_charging", Map.of());
	}

	@Override
	public final CompletableFuture<CommandResult> rebootAsset(String sn) {
		return run(sn, "asset.reboot", Map.of());
	}

	@Override
	public final CompletableFuture<CommandResult> bootUpSubAsset(String sn) {
		return run(sn, "asset.boot_sub_asset", params("enabled", true));
	}

	@Override
	public final CompletableFuture<CommandResult> bootDownSubAsset(String sn) {
		return run(sn, "asset.boot_sub_asset", params("enabled", false));
	}

	@Override
	public final CompletableFuture<CommandResult> enterRemoteDebugMode(String sn) {
		return run(sn, "asset.remote_debug", params("enabled", true));
	}

	@Override
	public final CompletableFuture<CommandResult> closeRemoteDebugMode(String sn) {
		return run(sn, "asset.remote_debug", params("enabled", false));
	}

	@Override
	public final CompletableFuture<CommandResult> changeAcMode(String sn, String mode) {
		return run(sn, "asset.change_ac_mode", params("mode", mode));
	}

	private static Map<String, Object> coordinates(Coordinates coordinates) {
		if (coordinates == null) {
			return Map.of();
		}
		return params("latitude", present(coordinates.getLatitude()), "longitude", present(coordinates.getLongitude()),
				"altitude", present(coordinates.getAltitude()));
	}

	/** Omitted coordinates arrive as NaN; they are absent params, not numbers. */
	private static Double present(Double value) {
		return value == null || value.isNaN() ? null : value;
	}

	private static Map<String, Object> params(Object... keysAndValues) {
		Map<String, Object> params = new LinkedHashMap<>();
		for (int i = 0; i < keysAndValues.length; i += 2) {
			Object value = keysAndValues[i + 1];
			if (value instanceof Float number && number.isNaN()) value = null;
			if (value != null) params.put((String) keysAndValues[i], value);
		}
		return params;
	}
}
