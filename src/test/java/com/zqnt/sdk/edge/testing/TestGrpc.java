package com.zqnt.sdk.edge.testing;

import io.grpc.BindableService;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** A real gRPC server on a free port serving only the given services; anything else is UNIMPLEMENTED. */
public final class TestGrpc implements AutoCloseable {

	private final Server server;
	private final ManagedChannel channel;

	private TestGrpc(Server server) {
		this.server = server;
		this.channel = ManagedChannelBuilder.forAddress("127.0.0.1", server.getPort()).usePlaintext().build();
	}

	public static TestGrpc serving(BindableService... services) throws IOException {
		ServerBuilder<?> builder = ServerBuilder.forPort(0);
		for (BindableService service : services) builder.addService(service);
		return new TestGrpc(builder.build().start());
	}

	public ManagedChannel channel() {
		return channel;
	}

	@Override
	public void close() throws InterruptedException {
		channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
		server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
	}

	public static void await(BooleanSupplier condition) throws InterruptedException {
		long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
		while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
			Thread.sleep(20);
		}
		assertTrue(condition.getAsBoolean(), "condition was not met within 5 s");
	}

	/** A client stream on the server side that records every item. */
	public static <T> StreamObserver<T> recording(List<T> items) {
		return new StreamObserver<>() {
			@Override
			public void onNext(T value) {
				items.add(value);
			}

			@Override
			public void onError(Throwable t) {
			}

			@Override
			public void onCompleted() {
			}
		};
	}

	public static <T> List<T> list() {
		return new CopyOnWriteArrayList<>();
	}
}
