package com.zqnt.sdk.edge.gateway;

import com.zqnt.sdk.edge.adapter.application.EdgeAdapterService;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Keeps the platform's view of an adapter's capabilities current: reports each tracked asset at
 * start and again whenever the adapter's {@link com.zqnt.sdk.edge.adapter.registry.CommandRegistry}
 * changes. A failed report is retried with backoff (the platform may still be starting); a newer
 * report replaces a pending retry.
 */
@Slf4j
public class CapabilityReporter implements AutoCloseable {

	private static final long INITIAL_RETRY_MILLIS = 2_000;
	private static final long MAX_RETRY_MILLIS = 60_000;

	private final EdgeAdapterService adapter;
	private final EdgeGatewayClient gateway;
	private final ScheduledExecutorService scheduler;
	private final long initialRetryMillis;
	private final Set<String> tracked = ConcurrentHashMap.newKeySet();
	private final Map<String, ScheduledFuture<?>> retries = new ConcurrentHashMap<>();
	private final Map<String, AtomicInteger> generations = new ConcurrentHashMap<>();
	private final Runnable unsubscribe;

	public CapabilityReporter(EdgeAdapterService adapter, EdgeGatewayClient gateway) {
		this(adapter, gateway, Executors.newSingleThreadScheduledExecutor(), INITIAL_RETRY_MILLIS);
	}

	CapabilityReporter(EdgeAdapterService adapter, EdgeGatewayClient gateway, ScheduledExecutorService scheduler,
			long initialRetryMillis) {
		this.adapter = adapter;
		this.gateway = gateway;
		this.scheduler = scheduler;
		this.initialRetryMillis = initialRetryMillis;
		this.unsubscribe = adapter.commandRegistry().map(registry -> registry.onChange(this::reportAll)).orElse(() -> { });
	}

	/** Reports {@code sn} now and again on every capability change. */
	public CompletableFuture<String> track(String sn) {
		tracked.add(sn);
		return report(sn);
	}

	public void untrack(String sn) {
		tracked.remove(sn);
		cancelRetry(sn);
	}

	public void reportAll() {
		tracked.forEach(this::report);
	}

	/** One report of {@code sn}; on failure it is retried until it succeeds or a newer one starts. */
	public CompletableFuture<String> report(String sn) {
		int generation = generations.computeIfAbsent(sn, ignored -> new AtomicInteger()).incrementAndGet();
		cancelRetry(sn);
		return attempt(sn, generation, 1);
	}

	private CompletableFuture<String> attempt(String sn, int generation, int attempt) {
		return adapter.getCapabilities(sn)
				.thenCompose(current -> gateway.reportCapabilities(sn, current))
				.whenComplete((revision, error) -> {
					if (error == null) {
						log.debug("Capabilities of {} reported, revision {}", sn, revision);
					} else if (generations.get(sn).get() == generation) {
						scheduleRetry(sn, generation, attempt, error);
					}
				});
	}

	private void scheduleRetry(String sn, int generation, int attempt, Throwable error) {
		long delay = Math.min(initialRetryMillis << Math.min(attempt - 1, 10), MAX_RETRY_MILLIS);
		log.warn("Could not report capabilities of {} (attempt {}): {}; retrying in {} ms", sn, attempt,
				error.getMessage(), delay);
		try {
			retries.put(sn, scheduler.schedule(() -> {
				if (generations.get(sn).get() == generation) {
					attempt(sn, generation, attempt + 1);
				}
			}, delay, TimeUnit.MILLISECONDS));
		} catch (RejectedExecutionException ignored) {
			// Closed meanwhile.
		}
	}

	private void cancelRetry(String sn) {
		ScheduledFuture<?> retry = retries.remove(sn);
		if (retry != null) retry.cancel(false);
	}

	@Override
	public void close() {
		unsubscribe.run();
		tracked.clear();
		scheduler.shutdownNow();
	}
}
