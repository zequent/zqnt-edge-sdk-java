package com.zqnt.sdk.edge.adapter.registry;

import com.zqnt.protos.capability.v3.TelemetryField;
import com.zqnt.sdk.edge.adapter.domains.Capability;
import com.zqnt.sdk.edge.adapter.domains.CommandResult;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The commands an adapter can execute, each written once: id, input and output JSON Schema, handler.
 * What the adapter advertises ({@code GetCapabilities}, v2 and v3) is derived from this registry
 * only, so an advertised id is always executable and an executable id is always advertised.
 *
 * <p>{@link #execute} validates params against the input schema before the handler runs and
 * converts numbers where the schema says {@code integer}; invalid params are
 * {@link CommandResult#rejected rejected} with {@link #INVALID_PARAMS}. An id that is not registered
 * is {@link CommandResult#notImplemented not implemented} (v3: REJECTED {@code command.not_supported}).
 */
@Slf4j
public final class CommandRegistry {

	public static final String INVALID_PARAMS = "command.invalid_params";
	public static final String NOT_SUPPORTED = "command.not_supported";

	private record Registered(Capability capability, CommandHandler handler) {
	}

	private final Map<String, Registered> commands = new LinkedHashMap<>();
	private final Map<String, TelemetryField> telemetryFields = new LinkedHashMap<>();
	private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

	/**
	 * Registers {@code id}; replaces an earlier registration of the same id.
	 *
	 * @return the capability as advertised, for adding a description, completion mode, events, ...
	 * (call {@link #changed()} after changing it later, so the platform learns about it)
	 * @throws IllegalArgumentException when a schema is not one {@link CommandSchemas} understands
	 */
	public Capability register(String id, Map<String, Object> inputSchema, Map<String, Object> outputSchema,
			CommandHandler handler) {
		Capability capability = new Capability();
		capability.setCommand(id);
		capability.setInputSchema(inputSchema == null ? new LinkedHashMap<>() : new LinkedHashMap<>(inputSchema));
		capability.setOutputSchema(outputSchema == null ? new LinkedHashMap<>() : new LinkedHashMap<>(outputSchema));
		return register(capability, handler);
	}

	/** Registers a fully described capability; its {@code command} is the id. */
	public Capability register(Capability capability, CommandHandler handler) {
		if (capability == null || capability.getCommand() == null || capability.getCommand().isBlank()) {
			throw new IllegalArgumentException("A command needs an id");
		}
		if (handler == null) {
			throw new IllegalArgumentException(capability.getCommand() + " needs a handler");
		}
		List<String> problems = new ArrayList<>(CommandSchemas.checkSchema(capability.getInputSchema()));
		problems.addAll(CommandSchemas.checkSchema(capability.getOutputSchema()));
		if (!problems.isEmpty()) {
			throw new IllegalArgumentException(capability.getCommand() + ": " + String.join("; ", problems));
		}
		synchronized (commands) {
			commands.put(capability.getCommand(), new Registered(capability, handler));
		}
		changed();
		return capability;
	}

	public boolean unregister(String id) {
		Registered removed;
		synchronized (commands) {
			removed = commands.remove(id);
		}
		if (removed != null) {
			changed();
		}
		return removed != null;
	}

	public boolean contains(String id) {
		synchronized (commands) {
			return id != null && commands.containsKey(id);
		}
	}

	public Optional<Capability> capability(String id) {
		synchronized (commands) {
			Registered registered = id == null ? null : commands.get(id);
			return Optional.ofNullable(registered == null ? null : registered.capability());
		}
	}

	/** Every registered command, in registration order. */
	public Set<Capability> capabilities() {
		synchronized (commands) {
			Set<Capability> capabilities = new LinkedHashSet<>();
			commands.values().forEach(registered -> capabilities.add(registered.capability()));
			return capabilities;
		}
	}

	public Set<String> commandIds() {
		synchronized (commands) {
			return new LinkedHashSet<>(commands.keySet());
		}
	}

	/** A device-specific value the adapter sends in v3 telemetry {@code details}. */
	public void declareTelemetryField(TelemetryField field) {
		if (field == null || field.getKey().isBlank()) {
			throw new IllegalArgumentException("A telemetry field needs a key");
		}
		synchronized (telemetryFields) {
			telemetryFields.put(field.getKey(), field);
		}
		changed();
	}

	public List<TelemetryField> telemetryFields() {
		synchronized (telemetryFields) {
			return List.copyOf(telemetryFields.values());
		}
	}

	/** Called on every change; returns the handle that removes the listener again. */
	public Runnable onChange(Runnable listener) {
		listeners.add(listener);
		return () -> listeners.remove(listener);
	}

	/** Tells the listeners that a capability changed, e.g. its state after a payload was detached. */
	public void changed() {
		for (Runnable listener : listeners) {
			try {
				listener.run();
			} catch (RuntimeException e) {
				log.warn("Capability change listener failed: {}", e.getMessage(), e);
			}
		}
	}

	public CompletableFuture<CommandResult> execute(CommandRequest request) {
		Registered registered;
		synchronized (commands) {
			registered = commands.get(request.commandId());
		}
		if (registered == null) {
			return CompletableFuture.completedFuture(CommandResult.notImplemented(
					request.commandId() + " is not supported by this adapter", request.sn()));
		}
		CommandSchemas.Validation validation = CommandSchemas.validate(registered.capability().getInputSchema(), request.params());
		if (!validation.valid()) {
			return CompletableFuture.completedFuture(CommandResult.rejected(INVALID_PARAMS,
					request.commandId() + ": " + validation.message(), request.sn()));
		}
		try {
			CompletableFuture<CommandResult> result = registered.handler().handle(request.withParams(validation.params()));
			return result == null
					? CompletableFuture.failedFuture(new IllegalStateException(request.commandId() + " handler returned no result"))
					: result;
		} catch (RuntimeException e) {
			return CompletableFuture.failedFuture(e);
		}
	}
}
