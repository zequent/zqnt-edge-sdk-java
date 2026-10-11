package com.zqnt.sdk.edge.adapter.registry;

import com.zqnt.protos.capability.v3.CommandRisk;
import com.zqnt.protos.capability.v3.CommandSafety;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The risk of the platform's standard command ids, from zqnt-api's {@code command_catalog.json}
 * (version 3.0.0). A capability that declares no {@link CommandSafety} is advertised with
 * its catalog entry; an id the catalog lists without a rating counts as CRITICAL, as the catalog
 * says. Ids the catalog does not list get no default: the adapter declares their risk.
 */
public final class CommandCatalog {

	public static final String VERSION = "3.0.0";

	private static final Map<String, CommandSafety> SAFETY = Map.ofEntries(
			entry("camera.take_photo", CommandRisk.COMMAND_RISK_OBSERVE, ""),
			entry("camera.capture_photo", CommandRisk.COMMAND_RISK_OBSERVE, ""),
			entry("camera.start_recording", CommandRisk.COMMAND_RISK_OBSERVE, "camera.stop_recording"),
			entry("camera.stop_recording", CommandRisk.COMMAND_RISK_OBSERVE, ""),
			entry("stream.start", CommandRisk.COMMAND_RISK_OBSERVE, "stream.stop"),
			entry("stream.stop", CommandRisk.COMMAND_RISK_OBSERVE, ""),
			entry("stream.split_screen", CommandRisk.COMMAND_RISK_OBSERVE, ""),
			entry("detection.start", CommandRisk.COMMAND_RISK_OBSERVE, "detection.stop"),
			entry("detection.stop", CommandRisk.COMMAND_RISK_OBSERVE, ""),
			entry("detection.status", CommandRisk.COMMAND_RISK_OBSERVE, ""),
			entry("detection.wait", CommandRisk.COMMAND_RISK_OBSERVE, ""),
			entry("detection.count", CommandRisk.COMMAND_RISK_OBSERVE, ""),
			entry("detection.control", CommandRisk.COMMAND_RISK_OBSERVE, ""),
			entry("gimbal.look_at", CommandRisk.COMMAND_RISK_ADJUST, "gimbal.reset"),
			entry("gimbal.rotate", CommandRisk.COMMAND_RISK_ADJUST, "gimbal.reset"),
			entry("gimbal.reset", CommandRisk.COMMAND_RISK_ADJUST, ""),
			entry("gimbal.tracking", CommandRisk.COMMAND_RISK_ADJUST, ""),
			entry("camera.change_lens", CommandRisk.COMMAND_RISK_ADJUST, ""),
			entry("camera.change_zoom", CommandRisk.COMMAND_RISK_ADJUST, ""),
			entry("camera.thermal_palette", CommandRisk.COMMAND_RISK_ADJUST, ""),
			entry("speaker.audio.play", CommandRisk.COMMAND_RISK_ADJUST, "speaker.audio.stop"),
			entry("speaker.audio.stop", CommandRisk.COMMAND_RISK_ADJUST, ""),
			entry("speaker.volume.set", CommandRisk.COMMAND_RISK_ADJUST, ""),
			entry("searchlight.mode.set", CommandRisk.COMMAND_RISK_ADJUST, ""),
			entry("searchlight.brightness.set", CommandRisk.COMMAND_RISK_ADJUST, ""),
			entry("widget.set", CommandRisk.COMMAND_RISK_ADJUST, ""),
			entry("parachute.led.set", CommandRisk.COMMAND_RISK_ADJUST, ""),
			entry("dock.light", CommandRisk.COMMAND_RISK_ADJUST, ""),
			entry("dock.open_cover", CommandRisk.COMMAND_RISK_ADJUST, "dock.close_cover"),
			entry("dock.close_cover", CommandRisk.COMMAND_RISK_ADJUST, "dock.open_cover"),
			entry("dock.putter", CommandRisk.COMMAND_RISK_ADJUST, ""),
			entry("dock.start_charging", CommandRisk.COMMAND_RISK_ADJUST, "dock.stop_charging"),
			entry("dock.stop_charging", CommandRisk.COMMAND_RISK_ADJUST, "dock.start_charging"),
			entry("asset.change_ac_mode", CommandRisk.COMMAND_RISK_ADJUST, ""),
			entry("asset.alarm", CommandRisk.COMMAND_RISK_ADJUST, ""),
			entry("asset.remote_debug", CommandRisk.COMMAND_RISK_ADJUST, ""),
			entry("asset.property.<name>.set", CommandRisk.COMMAND_RISK_ADJUST, ""),
			entry("battery.maintenance", CommandRisk.COMMAND_RISK_ADJUST, ""),
			entry("battery.store_mode", CommandRisk.COMMAND_RISK_ADJUST, ""),
			entry("radar.look_at", CommandRisk.COMMAND_RISK_ADJUST, ""),
			entry("radar.point", CommandRisk.COMMAND_RISK_ADJUST, ""),
			entry("radar.set_mode", CommandRisk.COMMAND_RISK_ADJUST, ""),
			entry("jammer.look_at", CommandRisk.COMMAND_RISK_ADJUST, ""),
			entry("jammer.set_bands", CommandRisk.COMMAND_RISK_ADJUST, ""),
			entry("mission.pause", CommandRisk.COMMAND_RISK_ADJUST, "mission.resume"),
			entry("mission.prepare", CommandRisk.COMMAND_RISK_ADJUST, ""),
			entry("flight.manual.enter", CommandRisk.COMMAND_RISK_ADJUST, "flight.manual.exit"),
			entry("flight.manual.exit", CommandRisk.COMMAND_RISK_ADJUST, ""),
			entry("flight.takeoff", CommandRisk.COMMAND_RISK_MOVE, "flight.return_to_home"),
			entry("navigation.go_to", CommandRisk.COMMAND_RISK_MOVE, "flight.return_to_home"),
			entry("navigation.update_target", CommandRisk.COMMAND_RISK_MOVE, ""),
			entry("flight.return_to_home", CommandRisk.COMMAND_RISK_MOVE, ""),
			entry("flight.manual.input", CommandRisk.COMMAND_RISK_MOVE, ""),
			entry("flight.emergency_stop", CommandRisk.COMMAND_RISK_MOVE, ""),
			entry("mission.waypoint.execute", CommandRisk.COMMAND_RISK_MOVE, "mission.stop"),
			entry("mission.start", CommandRisk.COMMAND_RISK_MOVE, "mission.stop"),
			entry("mission.resume", CommandRisk.COMMAND_RISK_MOVE, "mission.pause"),
			entry("mission.stop", CommandRisk.COMMAND_RISK_MOVE, ""),
			entry("flight.land", CommandRisk.COMMAND_RISK_CRITICAL, "flight.emergency_stop"),
			entry("asset.reboot", CommandRisk.COMMAND_RISK_CRITICAL, ""),
			entry("asset.boot_sub_asset", CommandRisk.COMMAND_RISK_CRITICAL, ""),
			entry("link.mode", CommandRisk.COMMAND_RISK_CRITICAL, ""),
			entry("jammer.enabled", CommandRisk.COMMAND_RISK_CRITICAL, ""),
			entry("vendor.rns.send_raw", CommandRisk.COMMAND_RISK_CRITICAL, ""),
			entry("vendor.sapient.mode_change", CommandRisk.COMMAND_RISK_CRITICAL, ""),
			entry("vendor.sapient.request", CommandRisk.COMMAND_RISK_CRITICAL, ""),
			entry("vendor.betaflight.arm", CommandRisk.COMMAND_RISK_CRITICAL, ""),
			entry("vendor.betaflight.disarm", CommandRisk.COMMAND_RISK_CRITICAL, ""));

	private static final Pattern PLACEHOLDER = Pattern.compile("<[a-z_]+>");

	private CommandCatalog() {
	}

	public static Optional<CommandSafety> safety(String commandId) {
		if (commandId == null || commandId.isBlank()) return Optional.empty();
		CommandSafety exact = SAFETY.get(commandId);
		if (exact != null) return Optional.of(exact);
		return SAFETY.entrySet().stream()
				.filter(entry -> PLACEHOLDER.matcher(entry.getKey()).find() && matches(entry.getKey(), commandId))
				.map(Map.Entry::getValue)
				.findFirst();
	}

	private static boolean matches(String pattern, String commandId) {
		String regex = PLACEHOLDER.splitAsStream(pattern).map(Pattern::quote)
				.reduce((left, right) -> left + "[A-Za-z0-9_]+" + right).orElse("");
		return commandId.matches(regex);
	}

	private static Map.Entry<String, CommandSafety> entry(String commandId, CommandRisk risk, String undoCommandId) {
		return Map.entry(commandId, CommandSafety.newBuilder()
				.setRisk(risk)
				.setReversible(!undoCommandId.isEmpty())
				.setUndoCommandId(undoCommandId)
				.build());
	}
}
