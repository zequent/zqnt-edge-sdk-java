package com.zqnt.sdk.edge.gateway;

import com.zqnt.protos.capability.v3.CapabilitySet;
import com.zqnt.protos.capability.v3.TelemetryField;
import com.zqnt.protos.capability.v3.TelemetryValueType;
import com.zqnt.protos.edge.v3.EdgeGatewayServiceGrpc;
import com.zqnt.protos.edge.v3.ReportCapabilitiesRequest;
import com.zqnt.protos.edge.v3.ReportCapabilitiesResponse;
import com.zqnt.sdk.edge.adapter.registry.RegistryEdgeAdapterTest;
import com.zqnt.sdk.edge.testing.TestGrpc;
import com.zqnt.utils.devicecontrol.proto.ReportAssetRuntimeRequest;
import com.zqnt.utils.devicecontrol.proto.ReportAssetRuntimeResponse;
import com.zqnt.utils.remotecontrol.proto.RemoteControlServiceGrpc;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static com.zqnt.sdk.edge.testing.TestGrpc.await;
import static org.junit.jupiter.api.Assertions.*;

/** The platform hears an adapter's capabilities at start and after every change of its registry. */
class CapabilityReporterTest {

	private final List<CapabilitySet> v3Reports = TestGrpc.list();
	private final List<ReportAssetRuntimeRequest> v2Reports = TestGrpc.list();
	private TestGrpc grpc;
	private CapabilityReporter reporter;

	private final class Gateway extends EdgeGatewayServiceGrpc.EdgeGatewayServiceImplBase {
		final AtomicInteger failuresLeft = new AtomicInteger();

		@Override
		public void reportCapabilities(ReportCapabilitiesRequest request, StreamObserver<ReportCapabilitiesResponse> observer) {
			if (failuresLeft.getAndDecrement() > 0) {
				observer.onError(Status.UNAVAILABLE.withDescription("starting").asRuntimeException());
				return;
			}
			v3Reports.add(request.getCapabilities());
			observer.onNext(ReportCapabilitiesResponse.newBuilder().setAcceptedRevision(request.getCapabilities().getRevision()).build());
			observer.onCompleted();
		}
	}

	private final class RemoteControl extends RemoteControlServiceGrpc.RemoteControlServiceImplBase {
		@Override
		public void reportAssetRuntime(ReportAssetRuntimeRequest request, StreamObserver<ReportAssetRuntimeResponse> observer) {
			v2Reports.add(request);
			observer.onNext(ReportAssetRuntimeResponse.newBuilder().setAcceptedRevision(request.getRevision()).build());
			observer.onCompleted();
		}
	}

	@AfterEach
	void tearDown() throws InterruptedException {
		if (reporter != null) reporter.close();
		if (grpc != null) grpc.close();
	}

	@Test
	void reportsAtStartAndOnEveryRegistryChange() throws Exception {
		grpc = TestGrpc.serving(new Gateway());
		var drone = new RegistryEdgeAdapterTest.Drone();
		reporter = new CapabilityReporter(drone, new EdgeGatewayClient(grpc.channel()));

		String revision = reporter.track("SN-1").get();
		drone.commands().declareTelemetryField(TelemetryField.newBuilder().setKey("drone.gear")
				.setType(TelemetryValueType.TELEMETRY_VALUE_TYPE_NUMBER).build());

		await(() -> v3Reports.size() == 2);
		assertEquals(revision, v3Reports.get(0).getRevision());
		assertEquals("SN-1", v3Reports.get(0).getAssetSn());
		assertEquals(2, v3Reports.get(0).getCapabilitiesCount());
		assertEquals("drone.gear", v3Reports.get(1).getTelemetryFields(0).getKey());
	}

	@Test
	void anOlderPlatformGetsTheV2RuntimeReport() throws Exception {
		grpc = TestGrpc.serving(new RemoteControl());
		reporter = new CapabilityReporter(new RegistryEdgeAdapterTest.Drone(), new EdgeGatewayClient(grpc.channel()));

		reporter.track("SN-1").get();

		assertEquals(1, v2Reports.size());
		assertEquals("SN-1", v2Reports.get(0).getAssetSn());
		assertEquals(List.of("flight.takeoff", "vendor.acme.beep"), v2Reports.get(0).getCapabilitiesList().stream()
				.map(com.zqnt.utils.devicecontrol.proto.Capability::getCommandId).toList());
		assertTrue(v3Reports.isEmpty());
	}

	@Test
	void aFailedReportIsRetried() throws Exception {
		Gateway gateway = new Gateway();
		gateway.failuresLeft.set(2);
		grpc = TestGrpc.serving(gateway);
		reporter = new CapabilityReporter(new RegistryEdgeAdapterTest.Drone(), new EdgeGatewayClient(grpc.channel()),
				Executors.newSingleThreadScheduledExecutor(), 10);

		assertThrows(Exception.class, () -> reporter.track("SN-1").get());

		await(() -> v3Reports.size() == 1);
	}

	@Test
	void anAdapterWithoutRegistryIsReportedToo() throws Exception {
		grpc = TestGrpc.serving(new Gateway());
		var legacy = new com.zqnt.sdk.edge.adapter.application.EdgeAdapterService() {
		};
		reporter = new CapabilityReporter(legacy, new EdgeGatewayClient(grpc.channel()));

		reporter.track("SN-2").get();

		assertEquals("SN-2", v3Reports.get(0).getAssetSn());
		assertEquals(0, v3Reports.get(0).getCapabilitiesCount());
	}
}
