package com.zqnt.sdk.edge.adapter.registry;

import java.util.Map;

/**
 * One command for a registered handler.
 *
 * @param sn                 the asset the command is for
 * @param targetRef          the sub-asset, payload or component it addresses; null for the asset itself
 * @param commandId          the dotted command id, e.g. {@code flight.takeoff}
 * @param params             validated against the command's input schema, integers already converted
 * @param commandExecutionId the platform's id of this run (v3); null when the command came over v2.
 *                           Events of an ACCEPTED command carry it as their execution id.
 */
public record CommandRequest(String sn, String targetRef, String commandId, Map<String, Object> params,
		String commandExecutionId) {

	public CommandRequest {
		params = params == null ? Map.of() : params;
	}

	public CommandRequest withParams(Map<String, Object> validated) {
		return new CommandRequest(sn, targetRef, commandId, validated, commandExecutionId);
	}
}
