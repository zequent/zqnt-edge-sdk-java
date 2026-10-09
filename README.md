# Zqnt Framework Edge SDK

## Writing an adapter (v3)

An adapter registers each command once: dotted id, input and output JSON Schema, handler.

```java
public class AcmeDrone extends RegistryEdgeAdapter {

    public AcmeDrone(AcmeLink link) {
        super(AssetTypeEnum.ASSET_TYPE_AIRCRAFT);
        Capability takeoff = registerCommand("flight.takeoff",
                Map.of("type", "object", "properties", Map.of(
                        "altitude", Map.of("type", "number", "minimum", 2, "maximum", 120))),
                Map.of(),
                request -> link.takeOff(request.params())
                        .thenApply(flightId -> CommandResult.accepted("climbing", flightId, request.sn())));
        takeoff.setCompletion(CompletionMode.COMPLETION_MODE_ASYNCHRONOUS);
        commands().declareTelemetryField(TelemetryField.newBuilder().setKey("drone.gear")
                .setType(TelemetryValueType.TELEMETRY_VALUE_TYPE_NUMBER).build());
    }
}
```

- `GetCapabilities` (v2 and v3) is derived from the registry only. v2 typed RPCs (`takeOff`, ...),
  v2 `SendCustomCommand` and v3 `ExecuteCommand` all run the same handler. The typed methods of
  `EdgeAdapterService` are deprecated; adapters that still implement them keep working.
- Params are validated against the input schema before the handler runs, and numbers are converted
  to `Integer`/`Long` where the schema says `integer`. Invalid params are REJECTED with error code
  `command.invalid_params` and a readable message; an unknown id with `command.not_supported`.
- Serve both contracts: `EdgeAdapterGrpcServiceImpl` (v2) and `EdgeAdapterV3GrpcServiceImpl` (v3),
  e.g. as `@GrpcService` subclasses in Quarkus.

## Talking to the platform

| What | v3 (preferred) | Against an older platform (UNIMPLEMENTED) |
|---|---|---|
| Command progress/completion | `LiveDataServiceImpl` with an `EdgeGatewayClient` sends the events adapters already produce (`CommandExecutionEventData`) as `EdgeGatewayService.PublishCommandEvent`, under the platform's `command_execution_id` | v2 notification stream, as before |
| Capabilities | `CapabilityReporter.track(sn)`: `ReportCapabilities` at start and on every registry change, retried until it succeeds | `RemoteControlService.ReportAssetRuntime` (no telemetry fields) |
| Telemetry, detections, alerts | `TelemetryPublisher.publish(TelemetrySample / DetectionBatch / Alert)`: one long-lived `TelemetryIngestService` stream per kind, reconnected with backoff | `ProduceTelemetry`/`ProduceDetection` with the shared fields only: a sample's `details` and all alerts are dropped |

After UNIMPLEMENTED the SDK stays on v2 for 10 minutes, then tries v3 again. Every command event
carries `occurred_at` (now, when the adapter gave none). `EdgeGatewayClient` uses the
remote-control channel, `TelemetryPublisher` the live-data channel; the existing v2 `LiveDataService`
API is unchanged.

```java
EdgeGatewayClient gateway = new EdgeGatewayClient(remoteControlChannel);
LiveDataService liveData = new LiveDataServiceImpl(telemetryMapper, detectionMapper, notificationMapper,
        LiveDataServiceGrpc.newStub(liveDataChannel), gateway);
new CapabilityReporter(adapter, gateway).track(sn);
TelemetryPublisher telemetry = new TelemetryPublisher(liveDataChannel, liveData);
```

## Conformance kit

```java
@Test
void conformsToTheEdgeContract() {
    EdgeAdapterConformance.check(new AcmeDrone(fakeLink), "SN-1").assertPassed();
}
```

It checks that every advertised id is executable and every executable id advertised (probing
unregistered ids, so fake the device side), that schemas parse and that an ASYNCHRONOUS command
declares its completion event. `checkCommandEvents` / `checkCommandExecutionEvents` check that
command events carry execution id, asset, state and `occurred_at`.

## Authentication

Both directions of an adapter's gRPC traffic are authenticated (package `com.zqnt.sdk.edge.auth`):

- `EdgeCredentialsClientInterceptor` puts the adapter's edge credential (`ZQNT_EDGE_TOKEN`) on every
  call into the platform. Issue one in the console (Edge Credentials,
  `POST /api/admin-console/edge-credentials`) or with `core/scripts/mint-edge-credential.py`. The
  platform refuses calls without it.
- `PlatformAuthServerInterceptor` refuses every command not signed by the platform
  (`ZQNT_PLATFORM_PUBLIC_KEY`, alias `SERVICE_AUTH_PUBLIC_KEY`); without the key it refuses
  everything. `ZQNT_EDGE_AUTH_DISABLED=true` accepts unauthenticated commands (local SITL only).

`EdgeAuthConfig.fromEnv()` reads all three.
