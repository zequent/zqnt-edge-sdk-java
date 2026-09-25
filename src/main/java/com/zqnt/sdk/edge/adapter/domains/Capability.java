package com.zqnt.sdk.edge.adapter.domains;


import com.zqnt.utils.devicecontrol.proto.CapabilitySourceProto;
import com.zqnt.utils.devicecontrol.proto.CapabilityState;
import com.zqnt.utils.devicecontrol.proto.CapabilityTargetType;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
@ToString
public class Capability {

	private String command;
	private String description;
	/** Defaults to AVAILABLE — set TEMPORARILY_UNAVAILABLE/UNSUPPORTED/REQUIRES_AUTHORIZATION
	 * explicitly for anything else. Replaces the old {@code available} boolean, which could only
	 * ever express two of {@link CapabilityState}'s five values. */
	private CapabilityState state = CapabilityState.CAPABILITY_STATE_AVAILABLE;
	private String unavailableReason;
	private Map<String, String> metadata = new HashMap<>();
	private Map<String, Object> constraints = new HashMap<>();
	private Map<String, Object> inputSchema = new HashMap<>();
	private Map<String, Object> outputSchema = new HashMap<>();
	private CapabilityTargetType targetType = CapabilityTargetType.CAPABILITY_TARGET_TYPE_ASSET;
	private String targetRef;
	private String schemaVersion;
	/** Errors this command's execution can end in. */
	private List<CapabilityError> errors = new ArrayList<>();
	/** Events this command (or its skill) can emit while/after executing. */
	private List<CapabilityEvent> events = new ArrayList<>();
	private CapabilityRequirements requirements;
	/** Groups sibling commands into one logical Skill; falls back to the command id's leading
	 * namespace segment (e.g. "flight" from "flight.takeoff") when unset. */
	private String skillId;
	private CapabilitySourceProto source;
	/** Human-readable origin, e.g. "DJI Adapter" or "Zequent Platform" for built-ins. */
	private String provider;

	public Capability(String command, String description, CapabilityState state, String unavailableReason,
			Map<String, String> metadata) {
		this.command = command;
		this.description = description;
		this.state = state == null ? CapabilityState.CAPABILITY_STATE_AVAILABLE : state;
		this.unavailableReason = unavailableReason;
		this.metadata = metadata == null ? new HashMap<>() : metadata;
	}
}
