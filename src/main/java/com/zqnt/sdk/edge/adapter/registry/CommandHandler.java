package com.zqnt.sdk.edge.adapter.registry;

import com.zqnt.sdk.edge.adapter.domains.CommandResult;

import java.util.concurrent.CompletableFuture;

/** Runs one registered command. Params have been validated against its input schema already. */
@FunctionalInterface
public interface CommandHandler {

	CompletableFuture<CommandResult> handle(CommandRequest request);
}
