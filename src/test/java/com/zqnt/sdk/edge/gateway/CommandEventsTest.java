package com.zqnt.sdk.edge.gateway;

import com.zqnt.protos.capability.v3.CommandEvent;
import com.zqnt.protos.capability.v3.CommandState;
import com.zqnt.protos.edge.v3.EdgeGatewayServiceGrpc;
import com.zqnt.protos.edge.v3.PublishCommandEventRequest;
import com.zqnt.protos.edge.v3.PublishCommandEventResponse;
import com.zqnt.sdk.edge.adapter.domains.NotificationRequestData;
import com.zqnt.sdk.edge.livedata.application.NotificationMapper;
import com.zqnt.sdk.edge.livedata.application.impl.LiveDataServiceImpl;
import com.zqnt.sdk.edge.testing.TestGrpc;
import com.zqnt.utils.events.proto.CommandExecutionStatus;
import com.zqnt.utils.events.proto.NotificationEventType;
import com.zqnt.utils.events.proto.NotificationSeverity;
import com.zqnt.utils.events.proto.ProduceNotificationRequest;
import com.zqnt.utils.livedata.proto.LiveDataResponse;
import com.zqnt.utils.livedata.proto.LiveDataServiceGrpc;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.zqnt.sdk.edge.testing.TestGrpc.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Adapters keep reporting command progress the way they do today (a notification with a
 * CommandExecutionEventData under their own execution id). Against a v3 platform it arrives as
 * EdgeGatewayService.PublishCommandEvent under the platform's command_execution_id, always with
 * occurred_at; against an older one it stays on the v2 notification stream.
 */
class CommandEventsTest {

	private final List<CommandEvent> v3Events = TestGrpc.list();
	private final List<ProduceNotificationRequest> v2Notifications = TestGrpc.list();
	private TestGrpc grpc;
	private LiveDataServiceImpl liveData;

	private final class Gateway extends EdgeGatewayServiceGrpc.EdgeGatewayServiceImplBase {
		@Override
		public void publishCommandEvent(PublishCommandEventRequest request, StreamObserver<PublishCommandEventResponse> observer) {
			v3Events.add(request.getEvent());
			observer.onNext(PublishCommandEventResponse.getDefaultInstance());
			observer.onCompleted();
		}
	}

	private final class LiveData extends LiveDataServiceGrpc.LiveDataServiceImplBase {
		@Override
		public StreamObserver<ProduceNotificationRequest> produceNotification(StreamObserver<LiveDataResponse> observer) {
			return TestGrpc.recording(v2Notifications);
		}
	}

	@AfterEach
	void tearDown() throws InterruptedException {
		if (liveData != null) liveData.shutdown();
		if (grpc != null) grpc.close();
	}

	private LiveDataServiceImpl liveData() {
		return new LiveDataServiceImpl(null, null, new NotificationMapper(),
				LiveDataServiceGrpc.newStub(grpc.channel()), new EdgeGatewayClient(grpc.channel()));
	}

	private static NotificationRequestData completed(String externalId) {
		return NotificationRequestData.builder()
				.sn("SN-1")
				.severity(NotificationSeverity.NOTIFICATION_SEVERITY_INFO)
				.eventType(NotificationEventType.NOTIFICATION_EVENT_COMMAND_EXECUTION)
				.commandExecutionEvent(NotificationRequestData.CommandExecutionEventData.builder()
						.externalExecutionId(externalId)
						.commandId("flight.takeoff")
						.status(CommandExecutionStatus.COMMAND_EXECUTION_STATUS_SUCCEEDED)
						.output(Map.of("altitude", 40))
						.assetSn("SN-1")
						.build())
				.build();
	}

	@Test
	void anAcceptedCommandsCompletionReachesThePlatformUnderItsExecutionId() throws Exception {
		grpc = TestGrpc.serving(new Gateway(), new LiveData());
		CommandExecutions.shared().started("capexec:run-1:takeoff", "SN-1", "flight.takeoff", "flight-7");
		liveData = liveData();

		liveData.produceNotificationData(completed("flight-7")).get();

		await(() -> v3Events.size() == 1);
		CommandEvent event = v3Events.get(0);
		assertEquals("capexec:run-1:takeoff", event.getCommandExecutionId());
		assertEquals("flight.takeoff", event.getCommandId());
		assertEquals("SN-1", event.getAsset().getSn());
		assertEquals(CommandState.COMMAND_STATE_SUCCEEDED, event.getState());
		assertTrue(event.hasOccurredAt());
		assertEquals(40.0, event.getResult().getFieldsOrThrow("altitude").getNumberValue());
		assertTrue(v2Notifications.isEmpty());
		assertTrue(CommandExecutions.shared().get("capexec:run-1:takeoff").isEmpty(), "a finished run is forgotten");
	}

	@Test
	void anOlderPlatformGetsTheV2EventWithOccurredAtAndIsAskedAgainOnlyLater() throws Exception {
		grpc = TestGrpc.serving(new LiveData());
		liveData = liveData();

		liveData.produceNotificationData(completed("flight-8")).get();
		liveData.produceNotificationData(completed("flight-9")).get();

		await(() -> v2Notifications.size() == 2);
		var event = v2Notifications.get(0).getEvent().getCommandExecution();
		assertEquals("flight-8", event.getExternalExecutionId());
		assertTrue(event.hasOccurredAt());
		assertTrue(v3Events.isEmpty());
	}

	@Test
	void theMappingKeepsTheAdaptersIdWhenTheCommandDidNotComeOverV3() {
		var event = CommandEventMapper.toV3(com.zqnt.utils.events.proto.CommandExecutionEvent.newBuilder()
				.setExternalExecutionId("flight-10").setAssetSn("SN-1")
				.setStatus(CommandExecutionStatus.COMMAND_EXECUTION_STATUS_RUNNING).setProgress(0.5f).build(),
				"SN-1", new CommandExecutions(Duration.ofMinutes(5)));

		assertEquals("flight-10", event.getCommandExecutionId());
		assertEquals(CommandState.COMMAND_STATE_RUNNING, event.getState());
		assertEquals(0.5f, event.getProgress());
		assertTrue(event.hasOccurredAt());
	}

	@Test
	void anAsynchronousCommandWithoutAnAdapterIdIsFoundByAssetAndCommand() {
		CommandExecutions executions = new CommandExecutions(Duration.ofMinutes(5));
		executions.started("capexec:run-2:cover", "SN-1", "dock.open_cover", null);

		var event = CommandEventMapper.toV3(com.zqnt.utils.events.proto.CommandExecutionEvent.newBuilder()
				.setExternalExecutionId("dji-bid-55").setAssetSn("SN-1").setCommandId("dock.open_cover")
				.setStatus(CommandExecutionStatus.COMMAND_EXECUTION_STATUS_SUCCEEDED).build(), "SN-1", executions);

		assertEquals("capexec:run-2:cover", event.getCommandExecutionId());
	}
}
