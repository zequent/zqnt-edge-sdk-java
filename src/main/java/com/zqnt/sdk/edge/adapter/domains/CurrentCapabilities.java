package com.zqnt.sdk.edge.adapter.domains;

import com.zqnt.protos.capability.v3.TelemetryField;
import com.zqnt.utils.common.proto.AssetTypeEnum;
import lombok.*;

import java.util.Collections;
import java.util.List;
import java.util.Set;


@Getter
@Setter
@NoArgsConstructor
@ToString
public class CurrentCapabilities {
	private String sn;
	private AssetTypeEnum assetType;
	private Set<Capability> capabilities;
	private long timestamp;
	/** The device-specific values this asset sends in a v3 TelemetrySample's {@code details}. */
	private List<TelemetryField> telemetryFields = List.of();

	public CurrentCapabilities(String sn, AssetTypeEnum assetType, Set<Capability> capabilities, long timestamp) {
		this.sn = sn;
		this.assetType = assetType;
		this.capabilities = capabilities;
		this.timestamp = timestamp;
	}

	/**
	 * Create an empty capabilities response
	 * Used when getCapabilities is not implemented
	 */
	public static CurrentCapabilities empty(String sn) {
		return new CurrentCapabilities(
			sn,
			AssetTypeEnum.ASSET_TYPE_UNKNOWN,
			Collections.emptySet(),
			System.currentTimeMillis()
		);
	}

	/**
	 * Create a capabilities response with specific asset type
	 */
	public static CurrentCapabilities of(String sn, AssetTypeEnum assetType, Set<Capability> capabilities) {
		return new CurrentCapabilities(
			sn,
			assetType,
			capabilities,
			System.currentTimeMillis()
		);
	}

}
