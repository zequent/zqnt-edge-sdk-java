package com.zqnt.sdk.edge.gateway;

import io.grpc.Status;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * Whether a v3 service answered at all. An older platform answers UNIMPLEMENTED; the SDK then uses
 * the v2 path and asks v3 again only after {@link #DEFAULT_RETRY_AFTER}, as the platform's own
 * EdgeCommandGateway does in the other direction.
 */
@Slf4j
public final class V3Availability {

	public static final Duration DEFAULT_RETRY_AFTER = Duration.ofMinutes(10);

	private final String service;
	private final long retryAfterMillis;
	private final LongSupplier clock;
	private volatile long unavailableUntil;

	public V3Availability(String service) {
		this(service, DEFAULT_RETRY_AFTER, System::currentTimeMillis);
	}

	public V3Availability(String service, Duration retryAfter, LongSupplier clock) {
		this.service = service;
		this.retryAfterMillis = retryAfter.toMillis();
		this.clock = clock;
	}

	public boolean available() {
		return clock.getAsLong() >= unavailableUntil;
	}

	public void markUnavailable() {
		boolean wasAvailable = available();
		unavailableUntil = clock.getAsLong() + retryAfterMillis;
		if (wasAvailable) {
			log.info("{} is not served by the platform (UNIMPLEMENTED); using v2 for {} s", service, retryAfterMillis / 1000);
		}
	}

	public static boolean isUnimplemented(Throwable error) {
		return error != null && Status.fromThrowable(error).getCode() == Status.Code.UNIMPLEMENTED;
	}
}
