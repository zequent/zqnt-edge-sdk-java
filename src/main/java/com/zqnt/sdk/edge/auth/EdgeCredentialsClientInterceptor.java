package com.zqnt.sdk.edge.auth;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;

/**
 * Puts the adapter's edge credential ({@code ZQNT_EDGE_TOKEN}) on every call into the platform.
 * Attach it to each channel to connector / live-data / mission-autonomy / remote-control, e.g.
 * {@code ManagedChannelBuilder.forAddress(host, port).usePlaintext().intercept(interceptor)}.
 * With no token it attaches nothing, and the platform refuses the calls.
 */
public class EdgeCredentialsClientInterceptor implements ClientInterceptor {

    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private final String token;

    public EdgeCredentialsClientInterceptor(String token) {
        this.token = token == null || token.isBlank() ? null : token;
    }

    public boolean hasToken() {
        return token != null;
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method,
            CallOptions callOptions, Channel next) {
        return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                if (token != null) {
                    headers.put(AUTHORIZATION, "Bearer " + token);
                }
                super.start(responseListener, headers);
            }
        };
    }
}
