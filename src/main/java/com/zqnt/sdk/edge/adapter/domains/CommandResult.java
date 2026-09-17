package com.zqnt.sdk.edge.adapter.domains;

import lombok.*;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class CommandResult {

	private boolean success;
	private String message;
	private String tid;
	private String sn;
	private CommandResultType resultType;
	/**
	 * Vendor-assigned execution id for a command still running asynchronously (e.g. a DJI
	 * flightId). Distinct from {@link #tid}, which is the platform's own request correlation id.
	 * Set via {@link #accepted(String, String, String)} and used to correlate async progress
	 * events (see {@code NotificationRequestData.CommandExecutionEventData}) and to route physical
	 * cancellation back to the right execution.
	 */
	private String externalExecutionId;

	/**
	 * Result type enum
	 */
	public enum CommandResultType {
		SUCCESS,
		/** Command was accepted by the vendor and is running asynchronously, tracked by
		 * {@link #getExternalExecutionId()} as the vendor's own execution/task id. */
		ACCEPTED,
		ERROR,
		NOT_IMPLEMENTED
	}

	/**
	 * Create a success result
	 */
	public static CommandResult success(String message, String sn) {
		return new CommandResult(true, message, null, sn, CommandResultType.SUCCESS, null);
	}

	/**
	 * Create a success result with transaction ID
	 */
	public static CommandResult success(String message, String transactionId, String sn) {
		return new CommandResult(true, message, transactionId, sn, CommandResultType.SUCCESS, null);
	}

	/**
	 * Create an "accepted" result for a command the vendor is still executing asynchronously.
	 * {@code externalExecutionId} is the vendor's own execution/task id, later used to correlate
	 * async progress events and physical cancellation (see {@link #getExternalExecutionId()}).
	 */
	public static CommandResult accepted(String message, String externalExecutionId, String sn) {
		return new CommandResult(true, message, null, sn, CommandResultType.ACCEPTED, externalExecutionId);
	}

	/**
	 * Create an error result
	 */
	public static CommandResult error(String message, String sn) {
		return new CommandResult(false, message, null, sn, CommandResultType.ERROR, null);
	}

	/**
	 * Create an error result with transaction ID
	 */
	public static CommandResult error(String message, String transactionId, String sn) {
		return new CommandResult(false, message, transactionId, sn, CommandResultType.ERROR, null);
	}

	/**
	 * Create a "not implemented" result
	 * Used for default implementations when customer hasn't implemented a method
	 */
	public static CommandResult notImplemented(String message, String sn) {
		return new CommandResult(false, message, null, sn, CommandResultType.NOT_IMPLEMENTED, null);
	}

	/**
	 * Check if this is a not-implemented result
	 */
	public boolean isNotImplemented() {
		return resultType == CommandResultType.NOT_IMPLEMENTED;
	}

	/**
	 * Check if this command was accepted and is still running, tracked by a vendor execution id.
	 */
	public boolean isAccepted() {
		return resultType == CommandResultType.ACCEPTED;
	}

}
