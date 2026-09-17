package com.zqnt.sdk.edge.adapter.application;

import com.zqnt.sdk.edge.adapter.domains.*;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Device-facing command API implemented by each vendor's Edge SDK adapter (DJI, MAVLink,
 * Betaflight, RNS, Sapient, ...). Built-in commands below map 1:1 to well-known capability
 * {@code command_id} strings dispatched by mission-autonomy's {@code EdgeExecutionNodeDispatcher};
 * vendor-specific or user-defined commands go through {@link #sendCustomCommand} instead, advertised
 * dynamically via {@link #getCapabilities}.
 *
 * <h2>Capability {@code command_id} naming convention</h2>
 * <ul>
 *   <li><b>Vendor-neutral.</b> Never encode a vendor name (no {@code dji.takeoff}) — the same id
 *   must be implementable by every adapter so capability graphs stay portable across vendors.</li>
 *   <li><b>{@code domain.action}, snake_case leaf.</b> Two dot-separated segments for a single
 *   atomic command: a domain ({@code flight}, {@code navigation}, {@code dock}, {@code asset},
 *   {@code camera}, {@code gimbal}, {@code stream}) and an underscore-separated action
 *   ({@code return_to_home}, {@code go_to}, {@code change_lens}) — never camelCase or a bare verb
 *   that reads like a Java method name.</li>
 *   <li><b>{@code domain.subtype.action} for domains with multiple execution modes.</b> Use three
 *   segments only when a domain genuinely branches into distinct variants that all need their own
 *   id, e.g. {@code mission.waypoint.execute} alongside future {@code mission.orbit.execute},
 *   {@code mission.follow.execute}. Don't use three segments just for grouping — that's what the
 *   two-segment domain prefix is already for.</li>
 *   <li><b>Reserve a {@code custom.} prefix for tenant/user-defined commands.</b> Any dynamically
 *   registered (e.g. database-backed) custom or payload command must live under {@code custom.}
 *   (or a tenant-scoped variant of it) so it can never collide with a future official built-in of
 *   the same name. Vendor payload commands already advertised through {@link #sendCustomCommand}
 *   (e.g. {@code parachute.led.set}) predate this rule and are grandfathered in, but new dynamic
 *   commands should follow it.</li>
 *   <li><b>Rename, don't overload.</b> If a command's shape changes incompatibly, give it a new id
 *   (or bump {@link Capability#getSchemaVersion()}) rather than silently changing what an existing
 *   id means — capability graphs may be persisted and reference ids by exact string.</li>
 *   <li><b>High-risk commands are prefix-matched for safety gating.</b> Anything starting with
 *   {@code flight.}, {@code navigation.}, {@code dock.}, {@code mission.}, or equal to
 *   {@code asset.reboot} is treated as high-risk by mission-autonomy's {@code ExecutionSafetyGate}
 *   (human-approval policy, live capability/telemetry checks before dispatch). A new domain that
 *   physically moves or affects the asset must either use one of those prefixes or be added to
 *   that gate explicitly — don't introduce a new movement-capable domain silently.</li>
 * </ul>
 */
public interface EdgeAdapterService {

	default CompletableFuture<CommandResult> sendCustomCommand(String sn, String componentId,
			String commandType, Map<String, Object> params) {
		return CompletableFuture.completedFuture(
				CommandResult.notImplemented("Custom command is not implemented for this asset", sn));
	}

	/**
	 * Execute takeoff command
	 * Default: Returns NOT_IMPLEMENTED error
	 */
	default CompletableFuture<CommandResult> takeOff(TakeOffRequest request) {
		return CompletableFuture.completedFuture(
				CommandResult.notImplemented("takeOff is not implemented for this asset", request.getSn())
		);
	}

	/**
	 * Return to home position
	 * Default: Returns NOT_IMPLEMENTED error
	 */
	default CompletableFuture<CommandResult> returnToHome(ReturnToHomeRequest request) {
		return CompletableFuture.completedFuture(
				CommandResult.notImplemented("returnToHome is not implemented for this asset", request.getSn())
		);
	}

	/**
	 * Navigate to coordinates
	 * Default: Returns NOT_IMPLEMENTED error
	 */
	default CompletableFuture<CommandResult> goTo(GoToRequest request) {
		return CompletableFuture.completedFuture(
				CommandResult.notImplemented("goTo is not implemented for this asset", request.getSn())
		);
	}

	/**
	 * Enter manual control mode
	 * Default: Returns NOT_IMPLEMENTED error
	 */
	default CompletableFuture<CommandResult> enterManualControl(String sn) {
		return CompletableFuture.completedFuture(
				CommandResult.notImplemented("enterManualControl is not implemented for this asset", sn)
		);
	}

	/**
	 * Exit manual control mode
	 * Default: Returns NOT_IMPLEMENTED error
	 */
	default CompletableFuture<CommandResult> exitManualControl(String sn) {
		return CompletableFuture.completedFuture(
				CommandResult.notImplemented("exitManualControl is not implemented for this asset", sn)
		);
	}

	/**
	 * Send manual control input stream
	 * This allows clients to stream manual control inputs (joystick commands) to the asset
	 * Default: Returns NOT_IMPLEMENTED error
	 */
	default CompletableFuture<CommandResult> manualControlInput(ManualControlInput inputStream) {
		return CompletableFuture.completedFuture(
				CommandResult.notImplemented("manualControlInput is not implemented for this asset", "unknown")
		);
	}

	/**
	 * Open dock cover
	 * Default: Returns NOT_IMPLEMENTED error
	 */
	default CompletableFuture<CommandResult> openCover(String sn) {
		return CompletableFuture.completedFuture(
				CommandResult.notImplemented("openCover is not implemented for this asset", sn)
		);
	}

	/**
	 * Close dock cover
	 * Default: Returns NOT_IMPLEMENTED error
	 */
	default CompletableFuture<CommandResult> closeCover(String sn, Boolean force) {
		return CompletableFuture.completedFuture(
				CommandResult.notImplemented("closeCover is not implemented for this asset", sn)
		);
	}

	/**
	 * Start charging the drone
	 * Default: Returns NOT_IMPLEMENTED error
	 */
	default CompletableFuture<CommandResult> startCharging(String sn) {
		return CompletableFuture.completedFuture(
				CommandResult.notImplemented("startCharging is not implemented for this asset", sn)
		);
	}

	/**
	 * Stop charging the drone
	 * Default: Returns NOT_IMPLEMENTED error
	 */
	default CompletableFuture<CommandResult> stopCharging(String sn) {
		return CompletableFuture.completedFuture(
				CommandResult.notImplemented("stopCharging is not implemented for this asset", sn)
		);
	}

	/**
	 * Reboot the asset
	 * Default: Returns NOT_IMPLEMENTED error
	 */
	default CompletableFuture<CommandResult> rebootAsset(String sn) {
		return CompletableFuture.completedFuture(
				CommandResult.notImplemented("rebootAsset is not implemented for this asset", sn)
		);
	}

	/**
	 * Boot up the sub-asset (drone)
	 * Default: Returns NOT_IMPLEMENTED error
	 */
	default CompletableFuture<CommandResult> bootUpSubAsset(String sn) {
		return CompletableFuture.completedFuture(
				CommandResult.notImplemented("bootUpSubAsset is not implemented for this asset", sn)
		);
	}

	/**
	 * Boot down the sub-asset (drone)
	 * Default: Returns NOT_IMPLEMENTED error
	 */
	default CompletableFuture<CommandResult> bootDownSubAsset(String sn) {
		return CompletableFuture.completedFuture(
				CommandResult.notImplemented("bootDownSubAsset is not implemented for this asset", sn)
		);
	}

	/**
	 * Point camera at coordinates
	 * Default: Returns NOT_IMPLEMENTED error
	 */
	default CompletableFuture<CommandResult> lookAt(LookAtRequest lookAtRequest) {
		return CompletableFuture.completedFuture(
				CommandResult.notImplemented("lookAt is not implemented for this asset", lookAtRequest.getSn())
		);
	}

	/**
	 * Take a photo (if supported)
	 * @param takePhotoRequest
	 * @return
	 */
	default CompletableFuture<CommandResult> takePhoto (TakePhotoRequest takePhotoRequest) {
		return CompletableFuture.completedFuture(
				CommandResult.notImplemented("takePhoto is not implemented for this asset", takePhotoRequest.getSn())
		);

	}

	/**
	 * Change camera lens
	 * Default: Returns NOT_IMPLEMENTED error
	 */
	default CompletableFuture<CommandResult> changeLens(ChangeLensRequest request) {
		return CompletableFuture.completedFuture(
			CommandResult.notImplemented("changeLens is not implemented for this asset", request.getSn())
		);
	}

	/**
	 * Change camera zoom
	 * Default: Returns NOT_IMPLEMENTED error
	 */
	default CompletableFuture<CommandResult> changeZoom(ChangeZoomRequest request) {
		return CompletableFuture.completedFuture(
			CommandResult.notImplemented("changeZoom is not implemented for this asset", request.getSn())
		);
	}

	/**
	 * Start live stream
	 * Default: Returns NOT_IMPLEMENTED error
	 */
	default CompletableFuture<CommandResult> startLiveStream(LiveStreamStartRequest request) {
		return CompletableFuture.completedFuture(
				CommandResult.notImplemented("startLiveStream is not implemented for this asset", request.getSn())
		);
	}

	/**
	 * Stop live stream
	 * Default: Returns NOT_IMPLEMENTED error
	 */
	default CompletableFuture<CommandResult> stopLiveStream(LiveStreamStopRequest request) {
		return CompletableFuture.completedFuture(
				CommandResult.notImplemented("stopLiveStream is not implemented for this asset", request.getSn())
		);
	}

	/**
	 * Enter remote debug mode
	 * Default: Returns NOT_IMPLEMENTED error
	 */
	default CompletableFuture<CommandResult> enterRemoteDebugMode(String sn) {
		return CompletableFuture.completedFuture(
			CommandResult.notImplemented("enterRemoteDebugMode is not implemented for this asset", sn)
		);
	}

	/**
	 * Close/Exit remote debug mode
	 * Default: Returns NOT_IMPLEMENTED error
	 */
	default CompletableFuture<CommandResult> closeRemoteDebugMode(String sn) {
		return CompletableFuture.completedFuture(
			CommandResult.notImplemented("closeRemoteDebugMode is not implemented for this asset", sn)
		);
	}

	/**
	 * Change air conditioner mode
	 * Default: Returns NOT_IMPLEMENTED error
	 */
	default CompletableFuture<CommandResult> changeAcMode(String sn, String mode) {
		return CompletableFuture.completedFuture(
			CommandResult.notImplemented("changeAcMode is not implemented for this asset", sn)
		);
	}

	/**
	 * Enable or disable the livestream split-screen view.
	 * Default: Returns NOT_IMPLEMENTED error
	 */
	default CompletableFuture<CommandResult> liveStreamSplitScreen(String sn, boolean enabled) {
		return CompletableFuture.completedFuture(
			CommandResult.notImplemented("liveStreamSplitScreen is not implemented for this asset", sn)
		);
	}
	/**
	 * Enable gimbal tracking
	 * Default: Returns NOT_IMPLEMENTED error
	 */
	default CompletableFuture<CommandResult> enableGimbalTracking(String sn, boolean enabled) {
		return CompletableFuture.completedFuture(
			CommandResult.notImplemented("enableGimbalTracking is not implemented for this asset", sn)
		);
	}

	/**
	 * Get current capabilities
	 * Default: Returns empty capabilities
	 */
	default CompletableFuture<CurrentCapabilities> getCapabilities(String sn) {
		return CompletableFuture.completedFuture(
			CurrentCapabilities.empty(sn)
		);
	}

	/**
	 * Start a task
	 * @param taskId
	 * @return {@link CommandResult}
	 */
	default CompletableFuture<CommandResult> startTask(String taskId, String tid) {
		return CompletableFuture.completedFuture(CommandResult.notImplemented("startTask is not implemented for this asset, taskId",taskId));
	}


	/**
	 * Pause a task
	 * @param taskId
	 * @return {@link CommandResult}
	 */
	default CompletableFuture<CommandResult> pauseTask(String taskId){
		return CompletableFuture.completedFuture(CommandResult.notImplemented("pauseTask is not implemented for this asset, taskId", taskId));
	}

	/**
	 * Resume a paused Task
	 * @param taskId
	 * @return {@link CommandResult}
	 */
	default CompletableFuture<CommandResult> resumeTask(String taskId){
		return CompletableFuture.completedFuture(CommandResult.notImplemented("resumeTask is not implemented for this asset, taskId", taskId));
	}

	/**
	 * Stop a task
	 * @param taskId
	 * @return {@link CommandResult}
	 * @deprecated superseded by {@link #cancelExecution(String, String)}; kept as the default
	 * fallback so adapters that have not migrated yet keep working. Mission Autonomy still
	 * dispatches physical cancellation over the {@code StopTask} RPC, now routed through
	 * {@code cancelExecution}.
	 */
	@Deprecated
	default CompletableFuture<CommandResult> stopTask(String taskId) {
		return CompletableFuture.completedFuture(CommandResult.notImplemented("stopTask is not implemented for this asset taskId", taskId));
	}

	/**
	 * Cancel a previously accepted, still-running command execution, identified by the vendor
	 * execution id returned in {@link CommandResult#accepted(String, String, String)}.
	 * Replaces the retired Task-lifecycle {@code stopTask}; default delegates to it so adapters
	 * that only implement {@code stopTask} keep working unchanged.
	 * Default: Returns NOT_IMPLEMENTED error
	 */
	default CompletableFuture<CommandResult> cancelExecution(String sn, String externalExecutionId) {
		return stopTask(externalExecutionId);
	}


	default CompletableFuture<CommandResult> prepareTask(String taskId, String tid) {
		return CompletableFuture.completedFuture(CommandResult.notImplemented("prepareTask is not implemented for this asset taskId", taskId));
	}
}
