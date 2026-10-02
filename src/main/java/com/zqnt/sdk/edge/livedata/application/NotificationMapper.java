package com.zqnt.sdk.edge.livedata.application;

import com.zqnt.sdk.edge.adapter.domains.NotificationRequestData;
import com.zqnt.sdk.edge.support.MapperSupport;
import com.zqnt.utils.JsonUtils;
import com.zqnt.utils.common.proto.GlobalErrorMessage;
import com.zqnt.utils.core.ProtoJsonUtils;
import com.zqnt.utils.events.proto.*;

import java.util.Map;

/**
 * Mapper for converting notification POJO data to Proto messages and vice versa.
 */
public class NotificationMapper {

	public NotificationRequestData map(ProduceNotificationRequest request) {
		if (request == null) return null;

		NotificationRequestData data = new NotificationRequestData();
		data.setSeverity(request.getSeverity());
		data.setEventType(request.getEventType());
		if (request.hasBase()) {
			data.setSn(request.getBase().getSn());
			data.setTid(request.getBase().getTid());
			data.setTimestamp(MapperSupport.toLocalDateTime(request.getBase().getTimestamp()));
		}

		NotificationEvent event = request.getEvent();
		switch (event.getEventCase()) {
			case ASSET_STATUS -> data.setAssetStatusEvent(map(event.getAssetStatus()));
			case COMMAND_EXECUTION -> data.setCommandExecutionEvent(map(event.getCommandExecution()));
			case MISSION -> data.setMissionEvent(map(event.getMission()));
			default -> { /* no-op for ERROR or EVENT_NOT_SET */ }
		}

		return data;
	}

	public ProduceNotificationRequest map(NotificationRequestData requestData) {
		if (requestData == null) return null;

		var baseBuilder = MapperSupport.requestBase(requestData.getSn(), requestData.getTid(), requestData.getTimestamp());

		ProduceNotificationRequest.Builder builder = ProduceNotificationRequest.newBuilder()
				.setBase(baseBuilder.build());
		builder.setSeverity(requestData.getSeverity());
		builder.setEventType(requestData.getEventType());
		NotificationEvent.Builder eventBuilder = NotificationEvent.newBuilder();
		if (requestData.getAssetStatusEvent() != null) {
			eventBuilder.setAssetStatus(map(requestData.getAssetStatusEvent()));
		} else if (requestData.getCommandExecutionEvent() != null) {
			eventBuilder.setCommandExecution(map(requestData.getCommandExecutionEvent()));
		} else if (requestData.getMissionEvent() != null) {
			eventBuilder.setMission(map(requestData.getMissionEvent()));
		}
		builder.setEvent(eventBuilder.build());

		return builder.build();
	}

	private NotificationRequestData.AssetStatusEventData map(AssetStatusEvent proto) {
		return NotificationRequestData.AssetStatusEventData.builder()
				.sn(proto.getSn())
				.assetId(proto.hasAssetId() ? proto.getAssetId() : null)
				.online(proto.getOnline())
				.message(proto.hasMessage() ? proto.getMessage() : null)
				.build();
	}

	private AssetStatusEvent map(NotificationRequestData.AssetStatusEventData data) {
		AssetStatusEvent.Builder builder = AssetStatusEvent.newBuilder()
				.setSn(MapperSupport.defaultString(data.getSn()))
				.setOnline(data.isOnline())
				.setMessage(data.getMessage());
		MapperSupport.set(builder::setAssetId, data.getAssetId());
		return builder.build();
	}

	private NotificationRequestData.CommandExecutionEventData map(CommandExecutionEvent proto) {
		return NotificationRequestData.CommandExecutionEventData.builder()
				.externalExecutionId(proto.getExternalExecutionId())
				.commandId(proto.hasCommandId() ? proto.getCommandId() : null)
				.status(proto.getStatus())
				.progress(proto.hasProgress() ? proto.getProgress() : null)
				.message(proto.hasMessage() ? proto.getMessage() : null)
				.output(proto.hasOutput() ? structToMap(proto.getOutput()) : null)
				.error(proto.hasError() ? proto.getError().getErrorMessage() : null)
				.occurredAt(proto.hasOccurredAt() ? MapperSupport.toLocalDateTime(proto.getOccurredAt()) : null)
				.assetSn(proto.getAssetSn())
				.build();
	}

	private CommandExecutionEvent map(NotificationRequestData.CommandExecutionEventData data) {
		CommandExecutionEvent.Builder builder = CommandExecutionEvent.newBuilder()
				.setExternalExecutionId(MapperSupport.defaultString(data.getExternalExecutionId()))
				.setAssetSn(MapperSupport.defaultString(data.getAssetSn()));
		MapperSupport.set(builder::setCommandId, data.getCommandId());
		MapperSupport.set(builder::setStatus, data.getStatus());
		MapperSupport.set(builder::setProgress, data.getProgress());
		MapperSupport.set(builder::setMessage, data.getMessage());
		if (data.getOutput() != null) builder.setOutput(mapToStruct(data.getOutput()));
		if (data.getError() != null) {
			builder.setError(GlobalErrorMessage.newBuilder().setErrorMessage(data.getError())
					.setTimestamp(MapperSupport.toTimestamp(
							data.getOccurredAt() != null ? data.getOccurredAt() : java.time.LocalDateTime.now())));
		}
		if (data.getOccurredAt() != null) builder.setOccurredAt(MapperSupport.toTimestamp(data.getOccurredAt()));
		return builder.build();
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> structToMap(com.google.protobuf.Struct struct) {
		return JsonUtils.fromJson(ProtoJsonUtils.toJson(struct), Map.class);
	}

	private com.google.protobuf.Struct mapToStruct(Map<String, Object> values) {
		com.google.protobuf.Struct.Builder builder = com.google.protobuf.Struct.newBuilder();
		ProtoJsonUtils.fromJson(JsonUtils.toJson(values), builder);
		return builder.build();
	}

	private NotificationRequestData.MissionEventData map(MissionEvent proto) {
		return NotificationRequestData.MissionEventData.builder()
				.missionId(proto.getMissionId())
				.missionType(proto.getMissionType())
				.status(proto.getStatus())
				.message(proto.hasMessage() ? proto.getMessage() : null)
				.build();
	}

	private MissionEvent map(NotificationRequestData.MissionEventData data) {
		MissionEvent.Builder builder = MissionEvent.newBuilder()
				.setMissionId(MapperSupport.defaultString(data.getMissionId()));
		MapperSupport.set(builder::setMissionType, data.getMissionType());
		MapperSupport.set(builder::setStatus, data.getStatus());
		MapperSupport.set(builder::setMessage, data.getMessage());
		return builder.build();
	}
}
