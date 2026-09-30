package com.zqnt.sdk.edge.auth;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import lombok.extern.slf4j.Slf4j;

/**
 * Refuses every call into the adapter that does not carry a valid platform service token — only
 * the platform may command the device. Health probes ({@code grpc.health.v1.Health}) stay open.
 * Register it on the adapter's gRPC server (in Quarkus: a {@code @GlobalInterceptor} bean that
 * delegates to this, see edge-dji).
 */
@Slf4j
public class PlatformAuthServerInterceptor implements ServerInterceptor {

    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    private static final String BEARER = "Bearer ";

    private final boolean disabled;
    private final PlatformTokenVerifier verifier;
    private final String misconfigured;

    public PlatformAuthServerInterceptor(EdgeAuthConfig config) {
        this.disabled = config.disabled();
        if (disabled) {
            log.warn("ZQNT_EDGE_AUTH_DISABLED is set: this adapter accepts commands from ANYONE who can reach "
                    + "its port. Local SITL/simulator use only.");
            this.verifier = null;
            this.misconfigured = null;
        } else if (config.platformPublicKey() == null || config.platformPublicKey().isBlank()) {
            log.error("ZQNT_PLATFORM_PUBLIC_KEY is not set: every platform command will be refused. Set it to "
                    + "the platform's SERVICE_AUTH_PUBLIC_KEY (or ZQNT_EDGE_AUTH_DISABLED=true for local SITL).");
            this.verifier = null;
            this.misconfigured = "ZQNT_PLATFORM_PUBLIC_KEY is not configured";
        } else {
            this.verifier = new PlatformTokenVerifier(config.platformPublicKey());
            this.misconfigured = null;
        }
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call, Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {
        Status refusal = check(call.getMethodDescriptor().getFullMethodName(), headers.get(AUTHORIZATION));
        if (refusal == null) {
            return next.startCall(call, headers);
        }
        call.close(refusal, new Metadata());
        return new ServerCall.Listener<>() { };
    }

    /** {@code null} when the call may proceed, else the status to refuse it with. */
    Status check(String fullMethodName, String authorization) {
        if (disabled || fullMethodName.startsWith("grpc.health.v1.Health/")) {
            return null;
        }
        if (verifier == null) {
            return Status.UNAUTHENTICATED.withDescription(misconfigured);
        }
        if (authorization == null || !authorization.startsWith(BEARER)) {
            return Status.UNAUTHENTICATED.withDescription("authentication required");
        }
        try {
            verifier.verify(authorization.substring(BEARER.length()));
            return null;
        } catch (PlatformTokenVerifier.EdgeAuthException e) {
            log.warn("Refused platform command {}: {}", fullMethodName, e.getMessage());
            return Status.UNAUTHENTICATED.withDescription(e.getMessage());
        }
    }
}
