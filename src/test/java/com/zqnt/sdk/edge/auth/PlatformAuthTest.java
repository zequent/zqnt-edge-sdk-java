package com.zqnt.sdk.edge.auth;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Only the platform may command the device (2026-09-30 security review, SAST-3). */
class PlatformAuthTest {

    static final KeyPair KEYS = generate();
    static final KeyPair OTHER = generate();
    static final String PUBLIC = Base64.getEncoder().encodeToString(KEYS.getPublic().getEncoded());
    static final String METHOD = "zqnt.EdgeAdapterService/TakeOff";

    static KeyPair generate() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Mints a token the way core's ServiceTokenCodec does. */
    static String token(KeyPair keys, String alg, String iss, String aud, String scope, long ttlSeconds) throws Exception {
        long now = Instant.now().getEpochSecond();
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString(("{\"alg\":\"" + alg + "\",\"typ\":\"JWT\"}").getBytes(StandardCharsets.UTF_8));
        String payload = enc.encodeToString(("{\"iss\":\"" + iss + "\",\"sub\":\"svc:remote-control-service\",\"aud\":\""
                + aud + "\",\"scope\":[\"" + scope + "\"],\"iat\":" + now + ",\"exp\":" + (now + ttlSeconds)
                + ",\"jti\":\"j\"}").getBytes(StandardCharsets.UTF_8));
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keys.getPrivate());
        signer.update((header + "." + payload).getBytes(StandardCharsets.US_ASCII));
        return header + "." + payload + "." + enc.encodeToString(signer.sign());
    }

    static String platformToken(KeyPair keys) throws Exception {
        return token(keys, "EdDSA", "zqnt-service", "zqnt-edge", "platform", 300);
    }

    @Test
    void thePlatformsTokenVerifies() throws Exception {
        assertEquals("svc:remote-control-service", new PlatformTokenVerifier(PUBLIC).verify(platformToken(KEYS)));
    }

    @Test
    void everythingElseIsRefused() throws Exception {
        PlatformTokenVerifier verifier = new PlatformTokenVerifier(PUBLIC);
        String[] refused = {
                platformToken(OTHER),                                               // not the platform's key
                token(KEYS, "EdDSA", "zqnt-service", "zqnt-platform", "platform", 300), // meant for core
                token(KEYS, "EdDSA", "zqnt-service", "zqnt-edge", "edge", 300),         // an adapter's credential
                token(KEYS, "EdDSA", "zqnt-admin-console", "zqnt-edge", "platform", 300), // a user token
                token(KEYS, "EdDSA", "zqnt-service", "zqnt-edge", "platform", -120),    // expired
                token(KEYS, "none", "zqnt-service", "zqnt-edge", "platform", 300),      // alg none
                "a.b.c", "abc", null};
        for (String bad : refused) {
            assertThrows(PlatformTokenVerifier.EdgeAuthException.class, () -> verifier.verify(bad), String.valueOf(bad));
        }
    }

    @Test
    void aBadKeyIsAConfigurationError() {
        assertThrows(IllegalArgumentException.class, () -> new PlatformTokenVerifier("not-a-key"));
    }

    @Test
    void theServerInterceptorRefusesCommandsWithoutThePlatformsToken() throws Exception {
        PlatformAuthServerInterceptor interceptor = new PlatformAuthServerInterceptor(new EdgeAuthConfig(null, PUBLIC, false));
        assertEquals(Status.Code.UNAUTHENTICATED, call(interceptor, METHOD, null).getCode());
        assertEquals(Status.Code.UNAUTHENTICATED, call(interceptor, METHOD, "Bearer " + platformToken(OTHER)).getCode());
        assertNull(call(interceptor, METHOD, "Bearer " + platformToken(KEYS)), "the platform's token passes");
        assertNull(call(interceptor, "grpc.health.v1.Health/Check", null), "health probes need no token");
    }

    @Test
    void withoutAKeyEverythingIsRefusedAndTheDevSwitchOpensEverything() throws Exception {
        assertEquals(Status.Code.UNAUTHENTICATED, call(new PlatformAuthServerInterceptor(
                new EdgeAuthConfig(null, null, false)), METHOD, "Bearer " + platformToken(KEYS)).getCode());
        assertNull(call(new PlatformAuthServerInterceptor(new EdgeAuthConfig(null, null, true)), METHOD, null));
    }

    @Test
    void theClientInterceptorAttachesTheEdgeCredential() {
        AtomicReference<Metadata> sent = new AtomicReference<>();
        Channel channel = new Channel() {
            @Override
            public <Q, R> ClientCall<Q, R> newCall(MethodDescriptor<Q, R> method, CallOptions options) {
                return new ClientCall<>() {
                    @Override public void start(Listener<R> listener, Metadata headers) { sent.set(headers); }
                    @Override public void request(int n) { }
                    @Override public void cancel(String message, Throwable cause) { }
                    @Override public void halfClose() { }
                    @Override public void sendMessage(Q message) { }
                };
            }
            @Override public String authority() { return "test"; }
        };
        new EdgeCredentialsClientInterceptor("edge-token").interceptCall(descriptor(METHOD), CallOptions.DEFAULT, channel)
                .start(new ClientCall.Listener<>() { }, new Metadata());
        assertEquals("Bearer edge-token",
                sent.get().get(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)));
        assertFalse(new EdgeCredentialsClientInterceptor(" ").hasToken());
    }

    /** The status the call was closed with, or null if it reached the service. */
    static Status call(PlatformAuthServerInterceptor interceptor, String method, String authorization) {
        AtomicReference<Status> closed = new AtomicReference<>();
        AtomicReference<Boolean> reached = new AtomicReference<>(false);
        Metadata headers = new Metadata();
        if (authorization != null) {
            headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), authorization);
        }
        MethodDescriptor<String, String> descriptor = descriptor(method);
        interceptor.interceptCall(new io.grpc.ServerCall<String, String>() {
            @Override public void request(int n) { }
            @Override public void sendHeaders(Metadata h) { }
            @Override public void sendMessage(String m) { }
            @Override public void close(Status status, Metadata trailers) { closed.set(status); }
            @Override public boolean isCancelled() { return false; }
            @Override public MethodDescriptor<String, String> getMethodDescriptor() { return descriptor; }
        }, headers, (c, h) -> {
            reached.set(true);
            return new io.grpc.ServerCall.Listener<>() { };
        });
        return reached.get() ? null : closed.get();
    }

    static MethodDescriptor<String, String> descriptor(String method) {
        MethodDescriptor.Marshaller<String> marshaller = new MethodDescriptor.Marshaller<>() {
            @Override public InputStream stream(String value) { return new ByteArrayInputStream(value.getBytes()); }
            @Override public String parse(InputStream stream) { return ""; }
        };
        return MethodDescriptor.<String, String>newBuilder().setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName(method).setRequestMarshaller(marshaller).setResponseMarshaller(marshaller).build();
    }
}
