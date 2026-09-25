package com.zqnt.sdk.edge.missionautonomy.application;

import com.zqnt.utils.mission.proto.GetSchedulerRequest;
import com.zqnt.utils.missionautonomy.domains.SchedulerDTO;

import java.util.concurrent.CompletableFuture;

public interface MissionAutonomyService {

	// Mission/Task CRUD was retired from MissionAutonomyService in favor of the
	// capability-execution model (Application/SkillExecution); the underlying
	// gRPC methods no longer exist.

	CompletableFuture<SchedulerDTO> getScheduler(GetSchedulerRequest getSchedulerRequest);
}
