package com.zqnt.sdk.edge.auth;

import java.util.Locale;
import java.util.Set;

/**
 * An adapter's authentication settings (2026-09-30 security review, GRPC-1 / SAST-3). Both
 * directions of its gRPC traffic are authenticated:
 * <ul>
 *   <li><b>Adapter to platform</b>: core refuses calls without a credential. {@code edgeToken}
 *   ({@code ZQNT_EDGE_TOKEN}) is an edge credential issued in the console
 *   ({@code POST /api/admin-console/edge-credentials}) or by
 *   {@code core/scripts/mint-edge-credential.py}; {@link EdgeCredentialsClientInterceptor} puts it on
 *   every call.</li>
 *   <li><b>Platform to adapter</b>: the platform signs a short-lived service token (audience
 *   {@code zqnt-edge}) for every command; {@link PlatformAuthServerInterceptor} verifies it with
 *   {@code platformPublicKey} ({@code ZQNT_PLATFORM_PUBLIC_KEY}, alias
 *   {@code SERVICE_AUTH_PUBLIC_KEY}) and refuses everything else.</li>
 * </ul>
 * {@code disabled} ({@code ZQNT_EDGE_AUTH_DISABLED=true}) turns the inbound check off, for a local
 * SITL/simulator stack only. Without a key and without the switch every command is refused.
 */
public record EdgeAuthConfig(String edgeToken, String platformPublicKey, boolean disabled) {

    private static final Set<String> TRUE = Set.of("1", "true", "yes", "on");

    public static EdgeAuthConfig fromEnv() {
        String key = blankToNull(System.getenv("ZQNT_PLATFORM_PUBLIC_KEY"));
        if (key == null) {
            key = blankToNull(System.getenv("SERVICE_AUTH_PUBLIC_KEY"));
        }
        String disabled = System.getenv("ZQNT_EDGE_AUTH_DISABLED");
        return new EdgeAuthConfig(blankToNull(System.getenv("ZQNT_EDGE_TOKEN")), key,
                disabled != null && TRUE.contains(disabled.trim().toLowerCase(Locale.ROOT)));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
