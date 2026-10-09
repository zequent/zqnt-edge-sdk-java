package com.zqnt.sdk.edge.gateway;

import com.google.protobuf.Timestamp;
import com.zqnt.protos.capability.v3.CapabilitySet;
import com.zqnt.protos.capability.v3.CommandEvent;
import com.zqnt.protos.edge.v3.EdgeGatewayServiceGrpc;
import com.zqnt.protos.edge.v3.PublishCommandEventRequest;
import com.zqnt.protos.edge.v3.ReportCapabilitiesRequest;
import com.zqnt.sdk.edge.adapter.domains.CurrentCapabilities;
import com.zqnt.sdk.edge.adapter.grpc.CapabilityMappers;
import com.zqnt.utils.common.proto.RequestBase;
import com.zqnt.utils.core.ProtobufHelpers;
import com.zqnt.utils.devicecontrol.proto.ReportAssetRuntimeRequest;
import com.zqnt.utils.remotecontrol.proto.RemoteControlServiceGrpc;
import io.grpc.Channel;
import io.grpc.stub.StreamObserver;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

/**
 * The adapter's calls into the platform's {@code zqnt.edge.v3.EdgeGatewayService}, served by
 * remote-control on the endpoint the adapter already uses for v2 {@code RemoteControlService}.
 * Against a platform without it (UNIMPLEMENTED) capabilities go to v2 {@code ReportAssetRuntime};
 * command events are left to the caller's v2 path ({@link #commandEventsAvailable()}).
 */
public class EdgeGatewayClient {

	private final EdgeGatewayServiceGrpc.EdgeGatewayServiceStub gateway;
	private final RemoteControlServiceGrpc.RemoteControlServiceStub remoteControl;
	private final V3Availability availability;

	/** {@code remoteControlChannel}: the channel to remote-control, with the edge credential interceptor. */
	public EdgeGatewayClient(Channel remoteControlChannel) {
		this(EdgeGatewayServiceGrpc.newStub(remoteControlChannel), RemoteControlServiceGrpc.newStub(remoteControlChannel),
				new V3Availability("zqnt.edge.v3.EdgeGatewayService"));
	}

	public EdgeGatewayClient(EdgeGatewayServiceGrpc.EdgeGatewayServiceStub gateway,
			RemoteControlServiceGrpc.RemoteControlServiceStub remoteControl, V3Availability availability) {
		this.gateway = gateway;
		this.remoteControl = remoteControl;
		this.availability = availability;
	}

	public boolean commandEventsAvailable() {
		return availability.available();
	}

	/**
	 * Publishes one command event over v3; {@code occurred_at} is set to now when missing. Fails with
	 * the gRPC status; UNIMPLEMENTED also switches {@link #commandEventsAvailable()} off for a while.
	 */
	public CompletableFuture<Void> publishCommandEvent(CommandEvent event) {
		CommandEvent complete = event.hasOccurredAt() ? event : event.toBuilder().setOccurredAt(ProtobufHelpers.now()).build();
		PublishCommandEventRequest request = PublishCommandEventRequest.newBuilder().setEvent(complete).build();
		return this.<PublishCommandEventRequest, com.zqnt.protos.edge.v3.PublishCommandEventResponse>call(
						gateway::publishCommandEvent, request)
				.whenComplete((ignored, error) -> {
					if (V3Availability.isUnimplemented(error)) availability.markUnavailable();
				})
				.thenApply(ignored -> null);
	}

	/**
	 * Reports the asset's full capability snapshot: v3 {@code ReportCapabilities}, or v2
	 * {@code ReportAssetRuntime} against an older platform (telemetry fields are v3 only).
	 *
	 * @return the revision the platform accepted
	 */
	public CompletableFuture<String> reportCapabilities(String sn, CurrentCapabilities current) {
		String revision = UUID.randomUUID().toString();
		if (!availability.available()) {
			return reportV2(sn, current, revision);
		}
		CapabilitySet set = CapabilityMappers.toV3(sn, current).toBuilder().setRevision(revision).build();
		return this.<ReportCapabilitiesRequest, com.zqnt.protos.edge.v3.ReportCapabilitiesResponse>call(
						gateway::reportCapabilities, ReportCapabilitiesRequest.newBuilder().setCapabilities(set).build())
				.thenApply(response -> response.getAcceptedRevision().isBlank() ? revision : response.getAcceptedRevision())
				.handle((accepted, error) -> {
					if (error == null) return CompletableFuture.completedFuture(accepted);
					if (V3Availability.isUnimplemented(error)) {
						availability.markUnavailable();
						return reportV2(sn, current, revision);
					}
					return CompletableFuture.<String>failedFuture(error);
				})
				.thenCompose(future -> future);
	}

	private CompletableFuture<String> reportV2(String sn, CurrentCapabilities current, String revision) {
		Timestamp now = ProtobufHelpers.now();
		ReportAssetRuntimeRequest.Builder request = ReportAssetRuntimeRequest.newBuilder()
				.setBase(RequestBase.newBuilder().setSn(sn).setTid(UUID.randomUUID().toString()).setTimestamp(now))
				.setAssetSn(sn)
				.setObservedAt(now)
				.setRevision(revision);
		if (current.getCapabilities() != null) {
			current.getCapabilities().stream().map(CapabilityMappers::toV2).forEach(request::addCapabilities);
		}
		return this.<ReportAssetRuntimeRequest, com.zqnt.utils.devicecontrol.proto.ReportAssetRuntimeResponse>call(
						remoteControl::reportAssetRuntime, request.build())
				.thenCompose(response -> response.getHasErrors()
						? CompletableFuture.failedFuture(new IllegalStateException(response.hasError()
						? response.getError().getErrorMessage() : "Runtime report rejected"))
						: CompletableFuture.completedFuture(response.hasAcceptedRevision() ? response.getAcceptedRevision() : revision));
	}

	private <Req, Resp> CompletableFuture<Resp> call(BiConsumer<Req, StreamObserver<Resp>> method, Req request) {
		CompletableFuture<Resp> result = new CompletableFuture<>();
		try {
			method.accept(request, new StreamObserver<>() {
				@Override
				public void onNext(Resp value) {
					result.complete(value);
				}

				@Override
				public void onError(Throwable t) {
					result.completeExceptionally(t);
				}

				@Override
				public void onCompleted() {
					if (!result.isDone()) result.completeExceptionally(new IllegalStateException("No response"));
				}
			});
		} catch (RuntimeException e) {
			result.completeExceptionally(e);
		}
		return result;
	}
}
