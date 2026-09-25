package com.zqnt.sdk.edge.adapter.domains;

import com.zqnt.utils.events.proto.CommandExecutionStatus;
import com.zqnt.utils.events.proto.NotificationEventType;
import com.zqnt.utils.events.proto.NotificationSeverity;
import com.zqnt.utils.mission.proto.MissionStatus;
import com.zqnt.utils.mission.proto.MissionType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationRequestData {

	private String tid;
	private String sn;
	private LocalDateTime timestamp;

	// Only one of the following event fields should be set (oneof)
	private AssetStatusEventData assetStatusEvent;
	private CommandExecutionEventData commandExecutionEvent;
	private MissionEventData missionEvent;
	private NotificationSeverity severity;
	private NotificationEventType eventType;

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class AssetStatusEventData {
		private String sn;
		private String assetId;
		private boolean online;
		private String message;
	}

	/**
	 * Vendor-neutral lifecycle feedback for one physical command dispatched to an edge adapter.
	 * Replaces the retired {@code TaskEventData} now that Task/Mission execution has been
	 * superseded by the capability-execution model.
	 */
	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class CommandExecutionEventData {
		private String externalExecutionId;
		private String commandId;
		private CommandExecutionStatus status;
		private Float progress;
		private String message;
		private Map<String, Object> output;
		private String error;
		private LocalDateTime occurredAt;
		private String assetSn;
	}

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class MissionEventData {
		private String missionId;
		private MissionType missionType;
		private MissionStatus status;
		private String message;
	}
}
