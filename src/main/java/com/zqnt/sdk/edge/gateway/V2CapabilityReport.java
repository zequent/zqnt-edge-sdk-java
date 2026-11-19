package com.zqnt.sdk.edge.gateway;

import com.zqnt.sdk.edge.adapter.domains.CurrentCapabilities;

import java.util.concurrent.CompletableFuture;

/**
 * How capabilities reach a platform without {@code EdgeGatewayService}. {@code ReportAssetRuntime}
 * replaces the asset's whole runtime snapshot, so an adapter that also reports detected payloads
 * supplies its own report carrying them.
 */
@FunctionalInterface
public interface V2CapabilityReport {

	/** @return the revision the platform accepted */
	CompletableFuture<String> report(String sn, CurrentCapabilities current, String revision);
}
