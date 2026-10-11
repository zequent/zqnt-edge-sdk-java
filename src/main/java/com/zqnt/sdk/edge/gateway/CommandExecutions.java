package com.zqnt.sdk.edge.gateway;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The platform's {@code command_execution_id} of every command this process accepted over v3,
 * found again by the adapter's own execution id (a DJI flight id) or, without one, by asset and
 * command. Adapters keep reporting progress with their own ids; the SDK puts the platform's id on
 * the v3 event and cancels by the adapter's id.
 */
public final class CommandExecutions {

	private static final CommandExecutions SHARED = new CommandExecutions(Duration.ofHours(24));

	public static CommandExecutions shared() {
		return SHARED;
	}

	public record Execution(String commandExecutionId, String sn, String commandId, String externalExecutionId,
			long startedAt) {
	}

	private final long retentionMillis;
	private final Map<String, Execution> byPlatformId = new ConcurrentHashMap<>();
	private final Map<String, String> byExternalId = new ConcurrentHashMap<>();
	private final Map<String, String> byAssetCommand = new ConcurrentHashMap<>();

	public CommandExecutions(Duration retention) {
		this.retentionMillis = retention.toMillis();
	}

	public void started(String commandExecutionId, String sn, String commandId, String externalExecutionId) {
		if (commandExecutionId == null || commandExecutionId.isBlank()) {
			return;
		}
		prune();
		Execution execution = new Execution(commandExecutionId, sn, commandId, externalExecutionId, System.currentTimeMillis());
		byPlatformId.put(commandExecutionId, execution);
		if (externalExecutionId != null && !externalExecutionId.isBlank()) {
			byExternalId.put(externalExecutionId, commandExecutionId);
		}
		if (sn != null && commandId != null) {
			byAssetCommand.put(key(sn, commandId), commandExecutionId);
		}
	}

	/**
	 * The platform's id for an event the adapter reports with {@code externalExecutionId}: its own
	 * id, the platform id itself, or the latest accepted run of {@code commandId} on {@code sn}.
	 */
	public Optional<Execution> resolve(String sn, String commandId, String externalExecutionId) {
		if (externalExecutionId != null && !externalExecutionId.isBlank()) {
			Execution direct = byPlatformId.get(externalExecutionId);
			if (direct != null) return Optional.of(direct);
			String platformId = byExternalId.get(externalExecutionId);
			if (platformId != null && byPlatformId.containsKey(platformId)) return Optional.of(byPlatformId.get(platformId));
		}
		if (sn != null && commandId != null) {
			String platformId = byAssetCommand.get(key(sn, commandId));
			if (platformId != null) {
				Execution execution = byPlatformId.get(platformId);
				boolean ownIdMatches = execution != null && (externalExecutionId == null || externalExecutionId.isBlank()
						|| execution.externalExecutionId() == null);
				if (ownIdMatches) return Optional.of(execution);
			}
		}
		return Optional.empty();
	}

	public Optional<Execution> get(String commandExecutionId) {
		return Optional.ofNullable(commandExecutionId == null ? null : byPlatformId.get(commandExecutionId));
	}

	public void finished(String commandExecutionId) {
		Execution execution = commandExecutionId == null ? null : byPlatformId.remove(commandExecutionId);
		if (execution == null) {
			return;
		}
		if (execution.externalExecutionId() != null) {
			byExternalId.remove(execution.externalExecutionId(), commandExecutionId);
		}
		if (execution.sn() != null && execution.commandId() != null) {
			byAssetCommand.remove(key(execution.sn(), execution.commandId()), commandExecutionId);
		}
	}

	private void prune() {
		long oldest = System.currentTimeMillis() - retentionMillis;
		byPlatformId.values().stream().filter(execution -> execution.startedAt() < oldest).toList()
				.forEach(execution -> finished(execution.commandExecutionId()));
	}

	private static String key(String sn, String commandId) {
		return sn + '\u0000' + commandId;
	}
}
