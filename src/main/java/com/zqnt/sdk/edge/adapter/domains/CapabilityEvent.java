package com.zqnt.sdk.edge.adapter.domains;

import lombok.*;

import java.util.Map;

/** One event a command (or the skill it belongs to) can emit while or after executing. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class CapabilityEvent {
	private String name;
	private String description;
	/** JSON Schema describing the event payload. */
	private Map<String, Object> payloadSchema;
}
