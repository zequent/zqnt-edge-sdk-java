package com.zqnt.sdk.edge.gateway;

import io.grpc.Status;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class V3AvailabilityTest {

	@Test
	void unimplementedSwitchesV3OffForTenMinutes() {
		AtomicLong now = new AtomicLong(1_000);
		V3Availability availability = new V3Availability("test", V3Availability.DEFAULT_RETRY_AFTER, now::get);

		availability.markUnavailable();
		assertFalse(availability.available());
		now.addAndGet(Duration.ofMinutes(10).toMillis() - 1);
		assertFalse(availability.available());
		now.incrementAndGet();
		assertTrue(availability.available());
	}

	@Test
	void onlyUnimplementedCountsAsNotServed() {
		assertTrue(V3Availability.isUnimplemented(new CompletionException(Status.UNIMPLEMENTED.asRuntimeException())));
		assertFalse(V3Availability.isUnimplemented(Status.UNAVAILABLE.asRuntimeException()));
		assertFalse(V3Availability.isUnimplemented(null));
	}
}
