package com.zqnt.sdk.edge.conformance;

import com.zqnt.protos.capability.v3.Capability;
import com.zqnt.protos.capability.v3.CapabilitySet;
import com.zqnt.protos.capability.v3.CommandEvent;
import com.zqnt.protos.capability.v3.CommandResult;
import com.zqnt.protos.capability.v3.CommandState;
import com.zqnt.protos.capability.v3.CompletionMode;
import com.zqnt.protos.common.v3.AssetRef;
import com.zqnt.protos.edge.v3.ExecuteCommandRequest;
import com.zqnt.protos.edge.v3.ExecuteCommandResponse;
import com.zqnt.protos.edge.v3.GetCapabilitiesRequest;
import com.zqnt.protos.edge.v3.GetCapabilitiesResponse;
import com.zqnt.sdk.edge.adapter.application.BuiltInCommandDispatch;
import com.zqnt.sdk.edge.adapter.application.EdgeAdapterService;
import com.zqnt.sdk.edge.adapter.grpc.EdgeAdapterV3GrpcServiceImpl;
import com.zqnt.sdk.edge.adapter.registry.CommandRegistry;
import com.zqnt.sdk.edge.adapter.registry.CommandSchemas;
import com.zqnt.sdk.edge.gateway.CommandEventMapper;
import com.zqnt.sdk.edge.gateway.CommandExecutions;
import com.zqnt.sdk.edge.support.Structs;
import com.zqnt.utils.events.proto.CommandExecutionEvent;
import io.grpc.stub.StreamObserver;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * A test kit for adapters: runs the adapter in-process through the SDK's v3 service and checks the
 * contract the platform relies on.
 *
 * <ul>
 *   <li>every advertised id is executable: registered, or executing it with a minimal value of its
 *   input schema is not refused as unsupported (nor as invalid by its own schema);</li>
 *   <li>every executable id is advertised: every registered id, and every built-in id the adapter
 *   implements typed ({@link Options#probeBuiltIns()});</li>
 *   <li>input and output schemas parse; an ASYNCHRONOUS command names a completion event it declares;</li>
 *   <li>{@link #checkCommandEvents}: every command event carries its execution id, asset, state and
 *   {@code occurred_at}.</li>
 * </ul>
 *
 * Probing executes commands, so run the kit against the adapter with its device side faked:
 * <pre>{@code
 * @Test
 * void conformsToTheEdgeContract() {
 *     EdgeAdapterConformance.check(new AcmeAdapter(fakeDevice), "SN-1").assertPassed();
 * }
 * }</pre>
 */
public final class EdgeAdapterConformance {

	private EdgeAdapterConformance() {
	}

	/**
	 * @param probeAdvertised execute every advertised, unregistered id with a minimal value of its schema
	 * @param probeBuiltIns   execute every built-in id the adapter does not advertise, to find typed
	 *                        methods it implements without advertising them
	 * @param timeout         how long one probe may take
	 */
	public record Options(boolean probeAdvertised, boolean probeBuiltIns, Duration timeout) {
		public static Options defaults() {
			return new Options(true, true, Duration.ofSeconds(5));
		}
	}

	public static ConformanceReport check(EdgeAdapterService adapter, String sn) {
		return check(adapter, sn, Options.defaults());
	}

	public static ConformanceReport check(EdgeAdapterService adapter, String sn, Options options) {
		List<String> problems = new ArrayList<>();
		EdgeAdapterV3GrpcServiceImpl service = new EdgeAdapterV3GrpcServiceImpl(adapter, new CommandExecutions(Duration.ofMinutes(1)));
		CapabilitySet capabilities;
		try {
			CompletableFuture<GetCapabilitiesResponse> response = new CompletableFuture<>();
			service.getCapabilities(GetCapabilitiesRequest.newBuilder().setAsset(AssetRef.newBuilder().setSn(sn)).build(),
					observer(response));
			capabilities = response.get(options.timeout().toMillis(), TimeUnit.MILLISECONDS).getCapabilities();
		} catch (Exception e) {
			return new ConformanceReport(List.of("GetCapabilities failed: " + message(e)));
		}

		CommandRegistry registry = adapter.commandRegistry().orElse(null);
		Set<String> advertised = new LinkedHashSet<>();
		Set<String> seen = new HashSet<>();
		for (Capability capability : capabilities.getCapabilitiesList()) {
			String id = capability.getCommandId();
			if (id.isBlank()) {
				problems.add("a capability has no command id");
				continue;
			}
			if (!seen.add(id + "@" + capability.getTarget().getRef())) {
				problems.add(id + " is advertised twice for the same target");
			}
			advertised.add(id);
			checkSchemas(capability, problems);
			checkCompletion(capability, problems);
			if (registry != null && registry.contains(id)) {
				continue;
			}
			if (options.probeAdvertised()) {
				Map<String, Object> sample = sampleParams(capability);
				CommandResult result = probe(service, sn, id, sample, options, problems);
				if (result != null && result.getState() == CommandState.COMMAND_STATE_REJECTED) {
					if (CommandRegistry.NOT_SUPPORTED.equals(result.getError().getCode())) {
						problems.add(id + " is advertised but not executable");
					} else if (CommandRegistry.INVALID_PARAMS.equals(result.getError().getCode())) {
						problems.add(id + " rejects params built from its own input schema: " + result.getError().getMessage());
					}
				}
			}
		}

		if (registry != null) {
			for (String id : registry.commandIds()) {
				if (!advertised.contains(id)) problems.add(id + " is registered but not advertised");
			}
		}
		if (options.probeBuiltIns()) {
			for (String id : BuiltInCommandDispatch.builtInIds()) {
				if (advertised.contains(id) || (registry != null && registry.contains(id))) continue;
				CommandResult result = probe(service, sn, id, Map.of(), options, problems);
				boolean unsupported = result == null || (result.getState() == CommandState.COMMAND_STATE_REJECTED
						&& CommandRegistry.NOT_SUPPORTED.equals(result.getError().getCode()));
				if (!unsupported) problems.add(id + " is executable but not advertised");
			}
		}
		return new ConformanceReport(problems);
	}

	/** Every command event must name its execution, asset and state, and carry {@code occurred_at}. */
	public static ConformanceReport checkCommandEvents(Collection<CommandEvent> events) {
		List<String> problems = new ArrayList<>();
		int index = 0;
		for (CommandEvent event : events) {
			String name = "event " + index++ + (event.getCommandId().isBlank() ? "" : " (" + event.getCommandId() + ")");
			if (event.getCommandExecutionId().isBlank()) problems.add(name + " has no command_execution_id");
			if (event.getAsset().getSn().isBlank()) problems.add(name + " has no asset.sn");
			if (event.getState() == CommandState.COMMAND_STATE_UNSPECIFIED) problems.add(name + " has no state");
			if (!event.hasOccurredAt()) problems.add(name + " has no occurred_at");
		}
		return new ConformanceReport(problems);
	}

	/** The adapter's v2 command events as the SDK sends them to the platform over v3. */
	public static ConformanceReport checkCommandExecutionEvents(String sn, Collection<CommandExecutionEvent> events) {
		CommandExecutions none = new CommandExecutions(Duration.ofMinutes(1));
		return checkCommandEvents(events.stream().map(event -> CommandEventMapper.toV3(event, sn, none)).toList());
	}

	private static void checkSchemas(Capability capability, List<String> problems) {
		String id = capability.getCommandId();
		if (capability.hasInputSchema()) {
			CommandSchemas.checkSchema(Structs.toMap(capability.getInputSchema()))
					.forEach(problem -> problems.add(id + " input " + problem));
		}
		if (capability.hasOutputSchema()) {
			CommandSchemas.checkSchema(Structs.toMap(capability.getOutputSchema()))
					.forEach(problem -> problems.add(id + " output " + problem));
		}
	}

	private static void checkCompletion(Capability capability, List<String> problems) {
		if (capability.getCompletion() != CompletionMode.COMPLETION_MODE_ASYNCHRONOUS
				|| capability.getCompletionEvent().isBlank()) {
			return;
		}
		boolean declared = capability.getEventsList().stream()
				.anyMatch(event -> event.getName().equals(capability.getCompletionEvent()));
		if (!declared) {
			problems.add(capability.getCommandId() + " completes with " + capability.getCompletionEvent()
					+ " but does not declare that event");
		}
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> sampleParams(Capability capability) {
		if (!capability.hasInputSchema()) return Map.of();
		Object sample = CommandSchemas.sample(Structs.toMap(capability.getInputSchema()));
		return sample instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
	}

	private static CommandResult probe(EdgeAdapterV3GrpcServiceImpl service, String sn, String id, Map<String, Object> params,
			Options options, List<String> problems) {
		CompletableFuture<ExecuteCommandResponse> response = new CompletableFuture<>();
		try {
			service.executeCommand(ExecuteCommandRequest.newBuilder()
					.setCommandExecutionId("conformance:" + id)
					.setCommand(com.zqnt.protos.capability.v3.Command.newBuilder()
							.setAsset(AssetRef.newBuilder().setSn(sn))
							.setCommandId(id)
							.setParams(Structs.toStruct(params)))
					.build(), observer(response));
			return response.get(options.timeout().toMillis(), TimeUnit.MILLISECONDS).getResult();
		} catch (Exception e) {
			problems.add(id + " could not be executed: " + message(e));
			return null;
		}
	}

	private static <T> StreamObserver<T> observer(CompletableFuture<T> result) {
		return new StreamObserver<>() {
			@Override
			public void onNext(T value) {
				result.complete(value);
			}

			@Override
			public void onError(Throwable t) {
				result.completeExceptionally(t);
			}

			@Override
			public void onCompleted() {
				if (!result.isDone()) result.completeExceptionally(new IllegalStateException("no response"));
			}
		};
	}

	private static String message(Throwable error) {
		Throwable cause = error.getCause() != null ? error.getCause() : error;
		return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
	}
}
