package com.zqnt.sdk.edge.gateway;

import com.zqnt.protos.capability.v3.CommandEvent;
import com.zqnt.protos.capability.v3.CommandState;
import com.zqnt.protos.common.v3.AssetRef;
import com.zqnt.protos.common.v3.Error;
import com.zqnt.protos.common.v3.ErrorCategory;
import com.zqnt.utils.core.ProtobufHelpers;
import com.zqnt.utils.events.proto.CommandExecutionEvent;

/**
 * An adapter's command progress (the v2 {@code CommandExecutionEvent} it reports today) as the v3
 * {@code CommandEvent}: the platform's {@code command_execution_id} when this process accepted the
 * command over v3, otherwise the adapter's own id, which the platform also matches. Every event
 * gets {@code occurred_at}; the platform refuses one without it.
 */
public final class CommandEventMapper {

	private CommandEventMapper() {
	}

	public static CommandEvent toV3(CommandExecutionEvent event, String fallbackSn, CommandExecutions executions) {
		String sn = event.getAssetSn().isBlank() ? (fallbackSn == null ? "" : fallbackSn) : event.getAssetSn();
		String commandId = event.hasCommandId() ? event.getCommandId() : null;
		var execution = executions.resolve(sn, commandId, event.getExternalExecutionId());
		CommandEvent.Builder builder = CommandEvent.newBuilder()
				.setCommandExecutionId(execution.map(CommandExecutions.Execution::commandExecutionId)
						.orElse(event.getExternalExecutionId()))
				.setAsset(AssetRef.newBuilder().setSn(sn))
				.setState(state(event))
				.setOccurredAt(event.hasOccurredAt() ? event.getOccurredAt() : ProtobufHelpers.now());
		if (commandId != null) {
			builder.setCommandId(commandId);
		} else {
			execution.map(CommandExecutions.Execution::commandId).ifPresent(builder::setCommandId);
		}
		if (event.hasProgress()) builder.setProgress(event.getProgress());
		if (event.hasMessage()) builder.setMessage(event.getMessage());
		if (event.hasOutput()) builder.setResult(event.getOutput());
		if (event.hasError()) {
			builder.setError(Error.newBuilder()
					.setCategory(ErrorCategory.ERROR_CATEGORY_ASSET)
					.setMessage(event.getError().getErrorMessage())
					.setOccurredAt(builder.getOccurredAt()));
		}
		return builder.build();
	}

	/** The v2 event as the platform requires it: with {@code occurred_at}. */
	public static CommandExecutionEvent withOccurredAt(CommandExecutionEvent event) {
		return event.hasOccurredAt() ? event : event.toBuilder().setOccurredAt(ProtobufHelpers.now()).build();
	}

	public static boolean isTerminal(CommandState state) {
		return switch (state) {
			case COMMAND_STATE_SUCCEEDED, COMMAND_STATE_FAILED, COMMAND_STATE_REJECTED,
				 COMMAND_STATE_CANCELLED, COMMAND_STATE_TIMED_OUT -> true;
			default -> false;
		};
	}

	private static CommandState state(CommandExecutionEvent event) {
		return switch (event.getStatus()) {
			case COMMAND_EXECUTION_STATUS_ACCEPTED -> CommandState.COMMAND_STATE_ACCEPTED;
			case COMMAND_EXECUTION_STATUS_RUNNING -> CommandState.COMMAND_STATE_RUNNING;
			case COMMAND_EXECUTION_STATUS_SUCCEEDED -> CommandState.COMMAND_STATE_SUCCEEDED;
			case COMMAND_EXECUTION_STATUS_FAILED -> CommandState.COMMAND_STATE_FAILED;
			case COMMAND_EXECUTION_STATUS_CANCELLED -> CommandState.COMMAND_STATE_CANCELLED;
			default -> event.hasError() ? CommandState.COMMAND_STATE_FAILED : CommandState.COMMAND_STATE_RUNNING;
		};
	}
}
