package com.zqnt.sdk.edge.adapter.domains;

import lombok.*;

import java.util.List;
import java.util.Map;

/**
 * What a command needs in order to be usable at all — feeds future "can this Skill run on this
 * Asset/Site?" checks. Purely declarative here, no enforcement implied.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class CapabilityRequirements {
	private List<String> assetTypes;
	private List<String> payloads;
	private List<String> runtimeFeatures;
	/** Free-form property requirements, e.g. {"camera.resolution": {"supported": ["4K"]}}. */
	private Map<String, Object> properties;
}
