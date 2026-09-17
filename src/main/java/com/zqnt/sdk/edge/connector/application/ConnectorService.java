package com.zqnt.sdk.edge.connector.application;


import com.zqnt.utils.asset.domains.AssetDTO;
import com.zqnt.utils.asset.domains.AssetPayloadDTO;
import com.zqnt.utils.asset.domains.SubAssetDTO;
import com.zqnt.utils.connector.proto.SkillContractProtoDTO;
import com.zqnt.utils.connector.proto.SkillContractStatus;
import com.zqnt.utils.missionautonomy.domains.OrganizationDTO;
import com.zqnt.utils.missionautonomy.domains.SchedulerDTO;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface ConnectorService {


	CompletableFuture<AssetDTO> getAssetBySn(String sn);

	CompletableFuture<AssetDTO> getAssetById(String id);

	CompletableFuture<SubAssetDTO> getSubAssetBySn(String sn);

	CompletableFuture<AssetPayloadDTO> upsertAssetPayload(String assetSn, String subAssetSn,
			AssetPayloadDTO payload);

	CompletableFuture<AssetDTO> updateAsset(String id, AssetDTO assetDTO);

	CompletableFuture<AssetDTO> registerAsset(AssetDTO assetDTO);

	CompletableFuture<Boolean> deRegisterAsset(String id);

	/**
	 * Trade a one-time claim code for an asset, and return it.
	 *
	 * <p>The only ConnectorService call an adapter makes with no platform identity: the code
	 * <em>is</em> the credential. The organization the created asset lands in comes from the claim,
	 * never from {@code asset} — whose organization field connector ignores — because that is the
	 * one thing an adapter cannot decide for itself and nobody can correct afterwards.</p>
	 *
	 * <p>Completes with {@code null} when the code is refused. Every refusal answers identically —
	 * unknown, expired, revoked, exhausted, or not valid for this kind of device — so that the call
	 * cannot be used to discover which codes exist. Do not guess which one it was.</p>
	 */
	CompletableFuture<AssetDTO> redeemAssetClaim(String code, AssetDTO asset);

	/**
	 * The name of the organization a claim code would provision into, without spending the code.
	 *
	 * <p>For the moment before anything is created: a device shows its operator which tenant they
	 * are about to bind into and waits for them to confirm. Redeeming to answer that would consume
	 * the claim before the operator had agreed to anything, and needs a serial number the flow does
	 * not have yet.</p>
	 *
	 * <p>Completes with {@code null} for every unusable code alike — unknown, expired, revoked,
	 * exhausted. It returns the organization's name and nothing else, deliberately.</p>
	 */
	CompletableFuture<String> describeAssetClaim(String code);

	/**
	 * Look {@code asset}'s serial number up, and redeem {@code claimCode} for it only if it is
	 * unknown.
	 *
	 * <p>The lookup comes first for a reason beyond saving a call: a claim is single-use, so after
	 * the first successful pairing there is nothing left to redeem, and an adapter that tried anyway
	 * would log a refusal on every restart. With no code supplied this reports what it found and
	 * creates nothing — the resting state for devices provisioned in the console.</p>
	 */
	CompletableFuture<AssetDTO> ensureAsset(AssetDTO asset, String claimCode);

	// Mission/Task CRUD was retired from ConnectorService in favor of the capability-execution
	// model (Application/SkillExecution). Use MissionAutonomyService's capability
	// execution APIs (via the client SDK) instead.

	CompletableFuture<SchedulerDTO> getSchedulerById(String id);

	CompletableFuture<SchedulerDTO> createScheduler(SchedulerDTO schedulerDTO);

	CompletableFuture<SchedulerDTO> updateScheduler(String id, SchedulerDTO schedulerDTO);

	CompletableFuture<Boolean> deleteScheduler(String id);

	CompletableFuture<OrganizationDTO> getOrganizationById(String id);

	// Skill Registry: lets an adapter self-report its own command contracts directly, instead of
	// only ever being polled indirectly via EdgeAdapterService#getCapabilities.

	/** Upserts {@code contract} into the persisted Skill Registry — new for a never-seen
	 * (command_id, schema_version) pair, or refreshed content/last-seen for one already known. */
	CompletableFuture<SkillContractProtoDTO> observeSkillContract(SkillContractProtoDTO contract);

	/** {@code commandId}, when set, returns that command's full version history instead of the
	 * whole registry (status is then ignored, matching the RPC's own semantics). Either argument
	 * may be null. */
	CompletableFuture<List<SkillContractProtoDTO>> listSkillContracts(SkillContractStatus status, String commandId);

	CompletableFuture<SkillContractProtoDTO> setSkillContractStatus(String id, SkillContractStatus status);

	/** Full replacement, not a merge. */
	CompletableFuture<SkillContractProtoDTO> setSkillContractPermissions(String id, List<String> requiredPermissions);
}
