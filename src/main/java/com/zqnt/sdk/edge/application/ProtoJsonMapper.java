package com.zqnt.sdk.edge.application;

import com.google.protobuf.Timestamp;
import com.zqnt.sdk.edge.adapter.domains.*;
// Explicit imports for domain classes whose short name also exists in com.zqnt.utils.devicecontrol.proto
// (wildcard-imported below) -- a single-class import takes precedence over a wildcard, resolving the
// ambiguity in favor of this SDK's own domain type rather than the raw proto-generated one.
import com.zqnt.sdk.edge.adapter.domains.ReturnToHomeRequest;
import com.zqnt.sdk.edge.adapter.domains.ManualControlInput;
import com.zqnt.utils.JsonUtils;
import com.zqnt.utils.asset.domains.AssetDTO;
import com.zqnt.utils.asset.domains.AssetPayloadDTO;
import com.zqnt.utils.asset.domains.SubAssetDTO;
import com.zqnt.utils.common.proto.AssetPayloadProtoDTO;
import com.zqnt.utils.common.proto.AssetProtoDTO;
import com.zqnt.utils.common.proto.OrganizationProtoDTO;
import com.zqnt.utils.common.proto.SubAssetProtoDTO;
import com.zqnt.utils.core.ProtoJsonUtils;
import com.zqnt.utils.devicecontrol.proto.*;
import com.zqnt.utils.mission.proto.MissionProtoDTO;
import com.zqnt.utils.mission.proto.SchedulerProtoDTO;
import com.zqnt.utils.mission.proto.TaskProtoDTO;
import com.zqnt.utils.mission.proto.WaypointProtoDTO;
import com.zqnt.utils.missionautonomy.domains.*;
import com.zqnt.utils.missionautonomy.domains.config.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

// Named, not wildcard: com.zqnt.utils.devicecontrol.proto and com.zqnt.sdk.edge.adapter.domains
// (wildcard-imported above) both declare a ReturnToHomeRequest/ManualControlInput — this SDK's own
// domain POJOs (the mapper's *output* type) are what every method signature below actually needs;
// only the proto *input* types below are pulled from devicecontrol.proto specifically.

/**
 * Simple mapper implementation for converting between Proto and POJO models
 */
public class ProtoJsonMapper {

    // Edge Request Mappings

    public Coordinates map(GeoCoordinate proto) {
        if (proto == null)
            return null;
        return coordinates(proto.getLatitude(), proto.getLongitude(), proto.getAltitude());
    }

    public TakeOffRequest mapTakeOff(CoordinateCommandRequest request) {
        if (request == null)
            return null;
        TakeOffRequest.TakeOffRequestBuilder builder = TakeOffRequest.builder();
        apply(builder, TakeOffRequest.TakeOffRequestBuilder::sn, request.getBase().getSn());
        apply(builder, TakeOffRequest.TakeOffRequestBuilder::tid, request.getBase().getTid());
        return builder
                .coordinates(map(request.getCoordinate()))
                .externalId(request.getBase().getExternalId())
                .build();
    }

    public GoToRequest mapGoTo(CoordinateCommandRequest request) {
        if (request == null)
            return null;
        GoToRequest.GoToRequestBuilder builder = GoToRequest.builder();
        apply(builder, GoToRequest.GoToRequestBuilder::sn, request.getBase().getSn());
        apply(builder, GoToRequest.GoToRequestBuilder::tid, request.getBase().getTid());
        return builder
                .coordinates(map(request.getCoordinate()))
                .externalId(request.getBase().getExternalId())
                .build();
    }

    public ReturnToHomeRequest map(ReturnToHomeCommandRequest request) {
        if (request == null)
            return null;
        ReturnToHomeRequest.ReturnToHomeRequestBuilder builder = ReturnToHomeRequest.builder();
        apply(builder, ReturnToHomeRequest.ReturnToHomeRequestBuilder::sn, request.getBase().getSn());
        apply(builder, ReturnToHomeRequest.ReturnToHomeRequestBuilder::tid, request.getBase().getTid());
        return builder
                .altitude(valueOrNull(request.hasRequest() && request.getRequest().hasAltitude(), request.getRequest().getAltitude()))
                .build();
    }

    public LiveStreamStartRequest map(LiveStreamStartCommandRequest request) {
        if (request == null)
            return null;
        LiveStreamStartRequest.LiveStreamStartRequestBuilder builder = LiveStreamStartRequest.builder();
        apply(builder, LiveStreamStartRequest.LiveStreamStartRequestBuilder::sn, request.getBase().getSn());
        apply(builder, LiveStreamStartRequest.LiveStreamStartRequestBuilder::tid, request.getBase().getTid());
        return builder
                .videoId(request.getRequest().getVideoId())
                .streamServer(request.getRequest().getStreamServer())
                .videoType(request.getRequest().getStreamType().name())
                .build();
    }

    public LiveStreamStopRequest map(LiveStreamStopCommandRequest request) {
        if (request == null)
            return null;
        LiveStreamStopRequest.LiveStreamStopRequestBuilder builder = LiveStreamStopRequest.builder();
        apply(builder, LiveStreamStopRequest.LiveStreamStopRequestBuilder::sn, request.getBase().getSn());
        apply(builder, LiveStreamStopRequest.LiveStreamStopRequestBuilder::tid, request.getBase().getTid());
        return builder
                .videoId(request.getRequest().getVideoId())
                .build();
    }

    public ChangeLensRequest map(ChangeCameraLensCommandRequest request) {
        if (request == null)
            return null;
        ChangeLensRequest.ChangeLensRequestBuilder builder = ChangeLensRequest.builder();
        apply(builder, ChangeLensRequest.ChangeLensRequestBuilder::sn, request.getBase().getSn());
        return builder
                .lens(valueOrNull(request.hasRequest() && request.getRequest().hasLens(), request.getRequest().getLens()))
                .build();
    }

    public ChangeZoomRequest map(ChangeCameraZoomCommandRequest request) {
        if (request == null)
            return null;
        ChangeZoomRequest.ChangeZoomRequestBuilder builder = ChangeZoomRequest.builder();
        apply(builder, ChangeZoomRequest.ChangeZoomRequestBuilder::sn, request.getBase().getSn());
        return builder
                .lens(valueOrNull(request.hasRequest() && request.getRequest().hasLens(), request.getRequest().getLens()))
                .zoom(valueOrNull(request.hasRequest() && request.getRequest().hasZoom(), (float) request.getRequest().getZoom()))
                .build();
    }

    public LookAtRequest map(LookAtCommandRequest request) {
        if (request == null)
            return null;
        LookAtRequest.LookAtRequestBuilder builder = LookAtRequest.builder();
        apply(builder, LookAtRequest.LookAtRequestBuilder::sn, request.getBase().getSn());
        return builder
                .latitude(request.getCoordinate().getLatitude())
                .longitude(request.getCoordinate().getLongitude())
                .altitude((float) request.getCoordinate().getAltitude())
                .locked(valueOrNull(request.hasLocked(), request.getLocked()))
                .payloadIndex(valueOrNull(request.hasPayloadIndex(), request.getPayloadIndex()))
                .build();
    }

    public TakePhotoRequest map(EmptyCommandRequest request) {
        if (request == null)
            return null;
        TakePhotoRequest.TakePhotoRequestBuilder builder = TakePhotoRequest.builder();
        apply(builder, TakePhotoRequest.TakePhotoRequestBuilder::sn, request.getBase().getSn());
        return builder.build();
    }

    public ManualControlInput map(ManualControlInputCommandRequest request) {
        if (request == null)
            return null;
        ManualControlInput.ManualControlInputBuilder builder = ManualControlInput.builder();
        apply(builder, ManualControlInput.ManualControlInputBuilder::sn, request.getBase().getSn());
        return builder
                .roll(valueOrNull(request.hasRequest() && request.getRequest().hasRoll(), request.getRequest().getRoll()))
                .pitch(valueOrNull(request.hasRequest() && request.getRequest().hasPitch(), request.getRequest().getPitch()))
                .yaw(valueOrNull(request.hasRequest() && request.getRequest().hasYaw(), request.getRequest().getYaw()))
                .throttle(valueOrNull(request.hasRequest() && request.getRequest().hasThrottle(), request.getRequest().getThrottle()))
                .gimbalPitch(valueOrNull(request.hasRequest() && request.getRequest().hasGimbalPitch(), request.getRequest().getGimbalPitch()))
                .build();
    }

    // Asset/Mission DTO Mappings

    public SubAssetDTO map(SubAssetProtoDTO proto) {
        if (proto == null)
            return null;

        SubAssetDTO.SubAssetDTOBuilder builder = SubAssetDTO.builder();

        set(builder::id, uuid(proto.getId()));
        set(builder::sn, proto.getSn());
        set(builder::name, proto.getName());
        set(builder::type, proto.getType());
        set(builder::vendor, proto.getVendor());
        set(builder::model, proto.getModel());
        set(builder::connection, proto.getConnection());
        set(builder::systemConnectionString, valueOrNull(proto.hasSystemConnectionString(), proto.getSystemConnectionString()));
        set(builder::liveStreamPushUrl, valueOrNull(proto.hasLiveStreamPushUrl(), proto.getLiveStreamPushUrl()));
        set(builder::liveStreamPullUrl, valueOrNull(proto.hasLiveStreamPullUrl(), proto.getLiveStreamPullUrl()));
        set(builder::externalDeviceType, valueOrNull(proto.hasExternalDeviceType(), proto.getExternalDeviceType()));
        set(builder::streamUrlPredefined, valueOrNull(proto.hasStreamUrlPredefined(), proto.getStreamUrlPredefined()));
        set(builder::externalDeviceSubType, valueOrNull(proto.hasExternalDeviceSubType(), proto.getExternalDeviceSubType()));
        set(builder::externalId, valueOrNull(proto.hasExternalId(), proto.getExternalId()));
        set(builder::createdAt, valueOrNull(proto.hasCreatedAt(), toLocalDateTime(proto.getCreatedAt())));
        set(builder::modifiedAt, valueOrNull(proto.hasModifiedAt(), toLocalDateTime(proto.getModifiedAt())));
        set(builder::modifiedFrom, valueOrNull(proto.hasModifiedFrom(), proto.getModifiedFrom()));
        builder.payloads(proto.getPayloadsList().stream().map(this::map).toList());

        return builder.build();
    }

    public SubAssetProtoDTO map(SubAssetDTO dto) {
        if (dto == null)
            return null;

        SubAssetProtoDTO.Builder builder = SubAssetProtoDTO.newBuilder();

        set(builder::setId, uuidString(dto.getId()));
        set(builder::setSn, dto.getSn());
        set(builder::setName, dto.getName());
        set(builder::setType, dto.getType());
        set(builder::setVendor, dto.getVendor());
        set(builder::setConnection, dto.getConnection());
        set(builder::setSystemConnectionString, dto.getSystemConnectionString());
        set(builder::setModel, dto.getModel());
        set(builder::setLiveStreamPushUrl, dto.getLiveStreamPushUrl());
        set(builder::setLiveStreamPullUrl, dto.getLiveStreamPullUrl());
        set(builder::setStreamUrlPredefined, dto.getStreamUrlPredefined());
        set(builder::setExternalDeviceType, dto.getExternalDeviceType());
        set(builder::setExternalDeviceSubType, dto.getExternalDeviceSubType());
        set(builder::setExternalId, dto.getExternalId());
        set(builder::setCreatedAt, dto.getCreatedAt() == null ? null : toTimestamp(dto.getCreatedAt()));
        set(builder::setModifiedAt, dto.getModifiedAt() == null ? null : toTimestamp(dto.getModifiedAt()));
        set(builder::setModifiedFrom, dto.getModifiedFrom());
        if (dto.getPayloads() != null) dto.getPayloads().stream().map(this::map).forEach(builder::addPayloads);

        return builder.build();
    }

    public AssetDTO map(AssetProtoDTO proto) {
        if (proto == null)
            return null;

        AssetDTO.AssetDTOBuilder builder = AssetDTO.builder();

        set(builder::id, uuid(proto.getId()));
        set(builder::sn, proto.getSn());
        set(builder::name, proto.getName());
        set(builder::type, proto.getType());
        set(builder::vendor, proto.getVendor());
        set(builder::connection, proto.getConnection());
        set(builder::model, proto.getModel());
        set(builder::systemConnectionString, valueOrNull(proto.hasSystemConnectionString(), proto.getSystemConnectionString()));
        set(builder::liveStreamPushUrl, valueOrNull(proto.hasLiveStreamPushUrl(), proto.getLiveStreamPushUrl()));
        set(builder::liveStreamPullUrl, valueOrNull(proto.hasLiveStreamPullUrl(), proto.getLiveStreamPullUrl()));
        set(builder::externalId, valueOrNull(proto.hasExternalId(), proto.getExternalId()));
        set(builder::externalDeviceType, valueOrNull(proto.hasExternalDeviceType(), proto.getExternalDeviceType()));
        set(builder::externalDeviceSubType, valueOrNull(proto.hasExternalDeviceSubType(), proto.getExternalDeviceSubType()));
        builder.subAssets(proto.getSubAssetsList().stream().map(this::map).toList());
        set(builder::organization, uuid(proto.getOrganization()));
        set(builder::createdAt, valueOrNull(proto.hasCreatedAt(), toLocalDateTime(proto.getCreatedAt())));
        set(builder::modifiedAt, valueOrNull(proto.hasModifiedAt(), toLocalDateTime(proto.getModifiedAt())));
        set(builder::modifiedFrom, valueOrNull(proto.hasModifiedFrom(), proto.getModifiedFrom()));
        builder.payloads(proto.getPayloadsList().stream().map(this::map).toList());

        return builder.build();
    }

    public AssetProtoDTO map(AssetDTO dto) {
        if (dto == null)
            return null;

        AssetProtoDTO.Builder builder = AssetProtoDTO.newBuilder();

        set(builder::setId, uuidString(dto.getId()));
        set(builder::setSn, dto.getSn());
        set(builder::setName, dto.getName());
        set(builder::setType, dto.getType());
        set(builder::setVendor, dto.getVendor());
        set(builder::setConnection, dto.getConnection());
        set(builder::setModel, dto.getModel());
        set(builder::setSystemConnectionString, dto.getSystemConnectionString());
        set(builder::setLiveStreamPushUrl, dto.getLiveStreamPushUrl());
        set(builder::setLiveStreamPullUrl, dto.getLiveStreamPullUrl());
        set(builder::setExternalId, dto.getExternalId());
        set(builder::setExternalDeviceType, dto.getExternalDeviceType());
        set(builder::setExternalDeviceSubType, dto.getExternalDeviceSubType());
        if (dto.getSubAssets() != null) dto.getSubAssets().stream().map(this::map).forEach(builder::addSubAssets);
        set(builder::setOrganization, uuidString(dto.getOrganization()));
        set(builder::setCreatedAt, dto.getCreatedAt() == null ? null : toTimestamp(dto.getCreatedAt()));
        set(builder::setModifiedAt, dto.getModifiedAt() == null ? null : toTimestamp(dto.getModifiedAt()));
        set(builder::setModifiedFrom, dto.getModifiedFrom());
        if (dto.getPayloads() != null) dto.getPayloads().stream().map(this::map).forEach(builder::addPayloads);

        return builder.build();
    }

    public AssetPayloadDTO map(AssetPayloadProtoDTO proto) {
        if (proto == null) return null;
        return AssetPayloadDTO.builder()
                .id(proto.hasId() ? uuid(proto.getId()) : null)
                .externalId(proto.hasExternalId() ? proto.getExternalId() : null)
                .externalType(proto.hasExternalType() ? proto.getExternalType() : null)
                .slotIndex(proto.hasSlotIndex() ? proto.getSlotIndex() : null)
                .name(proto.hasName() ? proto.getName() : null)
                .serialNumber(proto.hasSerialNumber() ? proto.getSerialNumber() : null)
                .kind(proto.hasKind() ? proto.getKind() : null)
                .vendor(proto.hasVendor() ? proto.getVendor() : null)
                .model(proto.hasModel() ? proto.getModel() : null)
                .firmwareVersion(proto.hasFirmwareVersion() ? proto.getFirmwareVersion() : null)
                .libraryVersion(proto.hasLibraryVersion() ? proto.getLibraryVersion() : null)
                .state(proto.hasStateJson() ? JsonUtils.fromJson(proto.getStateJson(), Map.class) : null)
                .active(proto.getActive())
                .lastSeenAt(proto.hasLastSeenAt() ? toLocalDateTime(proto.getLastSeenAt()) : null)
                .createdAt(proto.hasCreatedAt() ? toLocalDateTime(proto.getCreatedAt()) : null)
                .modifiedAt(proto.hasModifiedAt() ? toLocalDateTime(proto.getModifiedAt()) : null)
                .modifiedFrom(proto.hasModifiedFrom() ? proto.getModifiedFrom() : null)
                .build();
    }

    public AssetPayloadProtoDTO map(AssetPayloadDTO dto) {
        if (dto == null) return null;
        AssetPayloadProtoDTO.Builder builder = AssetPayloadProtoDTO.newBuilder()
                .setActive(Boolean.TRUE.equals(dto.getActive()));
        set(builder::setId, uuidString(dto.getId()));
        set(builder::setExternalId, dto.getExternalId());
        set(builder::setExternalType, dto.getExternalType());
        if (dto.getSlotIndex() != null) builder.setSlotIndex(dto.getSlotIndex());
        set(builder::setName, dto.getName());
        set(builder::setSerialNumber, dto.getSerialNumber());
        set(builder::setKind, dto.getKind());
        set(builder::setVendor, dto.getVendor());
        set(builder::setModel, dto.getModel());
        set(builder::setFirmwareVersion, dto.getFirmwareVersion());
        set(builder::setLibraryVersion, dto.getLibraryVersion());
        if (dto.getState() != null) builder.setStateJson(JsonUtils.toJson(dto.getState()));
        if (dto.getLastSeenAt() != null) builder.setLastSeenAt(toTimestamp(dto.getLastSeenAt()));
        if (dto.getCreatedAt() != null) builder.setCreatedAt(toTimestamp(dto.getCreatedAt()));
        if (dto.getModifiedAt() != null) builder.setModifiedAt(toTimestamp(dto.getModifiedAt()));
        set(builder::setModifiedFrom, dto.getModifiedFrom());
        return builder.build();
    }

    public OrganizationDTO map(OrganizationProtoDTO proto) {
        if (proto == null)
            return null;

        OrganizationDTO.OrganizationDTOBuilder builder = OrganizationDTO.builder();
        set(builder::id, uuid(proto.getId()));
        set(builder::name, proto.getName());
        set(builder::description, proto.getDescription());
        return builder.build();
    }

    public OrganizationProtoDTO map(OrganizationDTO dto) {
        if (dto == null)
            return null;

        OrganizationProtoDTO.Builder builder = OrganizationProtoDTO.newBuilder();

        set(builder::setId, uuidString(dto.getId()));
        set(builder::setName, dto.getName());
        set(builder::setDescription, dto.getDescription());
        // Note: assets Set<UUID> would need proto repeated field mapping if available

        return builder.build();
    }

    public MissionDTO map(MissionProtoDTO proto) {
        if (proto == null)
            return null;

        MissionDTO.MissionDTOBuilder builder = MissionDTO.builder();
        set(builder::name, proto.getName());
        set(builder::description, proto.getDescription());
        set(builder::status, proto.getStatus());
        set(builder::type, proto.getType());
        set(builder::id, uuid(proto.getId()));
        set(builder::createdAt, valueOrNull(proto.hasCreatedAt(), toLocalDateTime(proto.getCreatedAt())));
        set(builder::modifiedAt, valueOrNull(proto.hasModifiedAt(), toLocalDateTime(proto.getModifiedAt())));
        set(builder::modifiedFrom, valueOrNull(proto.hasUpdatedUser(), proto.getUpdatedUser()));
        set(builder::geoJson, valueOrNull(proto.hasGeoJson(), proto.getGeoJson()));
        set(builder::startDate, valueOrNull(proto.hasStartDate(), toLocalDateTime(proto.getStartDate())));
        set(builder::endDate, valueOrNull(proto.hasEndDate(), toLocalDateTime(proto.getEndDate())));
        // Note: assignedAssets Set<String> would need proto repeated field mapping if
        // available

        return builder.build();
    }

    public MissionProtoDTO map(MissionDTO dto) {
        if (dto == null)
            return null;

        MissionProtoDTO.Builder builder = MissionProtoDTO.newBuilder();

        set(builder::setId, uuidString(dto.getId()));
        set(builder::setCreatedAt, valueOrNull(dto.getCreatedAt() != null, toTimestamp(dto.getCreatedAt())));
        set(builder::setName, dto.getName());
        set(builder::setDescription, dto.getDescription());
        set(builder::setStatus, dto.getStatus());
        set(builder::setType, dto.getType());
        set(builder::setGeoJson, dto.getGeoJson());
        set(builder::setStartDate, valueOrNull(dto.getStartDate() != null, toTimestamp(dto.getStartDate())));
        set(builder::setEndDate, valueOrNull(dto.getEndDate() != null, toTimestamp(dto.getEndDate())));
        set(builder::setUpdatedUser, dto.getModifiedFrom());
        // Note: assignedAssets Set<String> would need proto repeated field mapping if
        // available

        return builder.build();
    }

    public TaskDTO map(TaskProtoDTO proto) {
        if (proto == null)
            return null;

        TaskDTO.TaskDTOBuilder builder = TaskDTO.builder();
        set(builder::status, proto.getStatus());
        set(builder::id, uuid(proto.getId()));
        set(builder::createdAt, valueOrNull(proto.hasCreatedAt(), toLocalDateTime(proto.getCreatedAt())));
        set(builder::missionId, uuid(proto.getMissionId()));
        set(builder::name, valueOrNull(proto.hasName(), proto.getName()));
        set(builder::description, valueOrNull(proto.hasDescription(), proto.getDescription()));
        set(builder::taskType, proto.getTaskType());
        set(builder::assetId, valueOrNull(proto.hasAssetId(), proto.getAssetId()));
        set(builder::snNumber, valueOrNull(proto.hasSnNumber(), proto.getSnNumber()));
        set(builder::externalTaskId, valueOrNull(proto.hasExternalTaskId(), proto.getExternalTaskId()));
        TaskConfigTemplate taskConfig = mapTypedTaskConfig(proto);
        set(builder::config, taskConfig != null
                ? taskConfig
                : valueOrNull(proto.hasConfig(), JsonUtils.fromJson(proto.getConfig(), TaskConfigTemplate.class)));
        set(builder::currentProgress, valueOrNull(proto.hasCurrentProgress(), proto.getCurrentProgress()));
        set(builder::currentStep, valueOrNull(proto.hasCurrentStep(), proto.getCurrentStep()));
        set(builder::breakReason, valueOrNull(proto.hasBreakReason(), proto.getBreakReason()));

        return builder.build();
    }

    public TaskProtoDTO map(TaskDTO dto) {
        if (dto == null)
            return null;

        TaskProtoDTO.Builder builder = TaskProtoDTO.newBuilder();

        set(builder::setId, uuidString(dto.getId()));
        set(builder::setCreatedAt, valueOrNull(dto.getCreatedAt() != null, toTimestamp(dto.getCreatedAt())));
        set(builder::setMissionId, uuidString(dto.getMissionId()));
        set(builder::setName, dto.getName());
        set(builder::setDescription, dto.getDescription());
        set(builder::setTaskType, dto.getTaskType());
        set(builder::setStatus, dto.getStatus());
        set(builder::setAssetId, dto.getAssetId());
        set(builder::setSnNumber, dto.getSnNumber());
        set(builder::setExternalTaskId, dto.getExternalTaskId());
        set(builder::setConfig, valueOrNull(dto.getConfig() != null, JsonUtils.toJson(dto.getConfig())));
        setTypedTaskConfig(builder, dto.getConfig());
        set(builder::setCurrentProgress, dto.getCurrentProgress());
        set(builder::setCurrentStep, dto.getCurrentStep());
        set(builder::setBreakReason, dto.getBreakReason());

        return builder.build();
    }

    private TaskConfigTemplate mapTypedTaskConfig(TaskProtoDTO proto) {
        return switch (proto.getTaskConfigCase()) {
            case WAYPOINT_CONFIG -> JsonUtils.fromJson(
                    ProtoJsonUtils.toJson(proto.getWaypointConfig()), WaypointTaskConfig.class);
            case DETECT_CONFIG -> JsonUtils.fromJson(
                    ProtoJsonUtils.toJson(proto.getDetectConfig()), DetectTaskConfig.class);
            case AREA_MAPPING_CONFIG -> JsonUtils.fromJson(
                    ProtoJsonUtils.toJson(proto.getAreaMappingConfig()), AreaMappingTaskConfig.class);
            case POI_CONFIG -> JsonUtils.fromJson(
                    ProtoJsonUtils.toJson(proto.getPoiConfig()), PoiTaskConfig.class);
            case FOLLOW_CONFIG -> JsonUtils.fromJson(
                    ProtoJsonUtils.toJson(proto.getFollowConfig()), FollowTaskConfig.class);
            case TRACK_CONFIG -> JsonUtils.fromJson(
                    ProtoJsonUtils.toJson(proto.getTrackConfig()), TrackTaskConfig.class);
            default -> null;
        };
    }

    private void setTypedTaskConfig(TaskProtoDTO.Builder builder, TaskConfigTemplate config) {
        if (config == null) {
            return;
        }
        String json = JsonUtils.toJson(config);
        switch (config) {
            case WaypointTaskConfig ignored -> builder.setWaypointConfig(
                    (com.zqnt.utils.mission.proto.WaypointTaskConfigProto) ProtoJsonUtils.fromJson(
                            json, com.zqnt.utils.mission.proto.WaypointTaskConfigProto.newBuilder()));
            case DetectTaskConfig ignored -> builder.setDetectConfig(
                    (com.zqnt.utils.mission.proto.DetectTaskConfigProto) ProtoJsonUtils.fromJson(
                            json, com.zqnt.utils.mission.proto.DetectTaskConfigProto.newBuilder()));
            case AreaMappingTaskConfig ignored -> builder.setAreaMappingConfig(
                    (com.zqnt.utils.mission.proto.AreaMappingTaskConfigProto) ProtoJsonUtils.fromJson(
                            json, com.zqnt.utils.mission.proto.AreaMappingTaskConfigProto.newBuilder()));
            case PoiTaskConfig ignored -> builder.setPoiConfig(
                    (com.zqnt.utils.mission.proto.PoiTaskConfigProto) ProtoJsonUtils.fromJson(
                            json, com.zqnt.utils.mission.proto.PoiTaskConfigProto.newBuilder()));
            case FollowTaskConfig ignored -> builder.setFollowConfig(
                    (com.zqnt.utils.mission.proto.FollowTaskConfigProto) ProtoJsonUtils.fromJson(
                            json, com.zqnt.utils.mission.proto.FollowTaskConfigProto.newBuilder()));
            case TrackTaskConfig ignored -> builder.setTrackConfig(
                    (com.zqnt.utils.mission.proto.TrackTaskConfigProto) ProtoJsonUtils.fromJson(
                            json, com.zqnt.utils.mission.proto.TrackTaskConfigProto.newBuilder()));
            default -> {
                // No typed proto representation is available for this config implementation.
            }
        }
    }

    public SchedulerDTO map(SchedulerProtoDTO proto) {
        if (proto == null)
            return null;

        SchedulerDTO.SchedulerDTOBuilder builder = SchedulerDTO.builder();

        set(builder::id, uuid(proto.getId()));
        set(builder::name, proto.getName());
        set(builder::cronExpression, proto.getCronExpression());
        set(builder::active, valueOrNull(proto.hasActive(), proto.getActive()));
        set(builder::type, proto.getType());
        set(builder::clientTimeZone, valueOrNull(proto.hasClientTimeZone(), proto.getClientTimeZone()));
        set(builder::assetSn, valueOrNull(proto.hasAssetSn(), proto.getAssetSn()));
        set(builder::commandId, valueOrNull(proto.hasCommandId(), proto.getCommandId()));
        set(builder::capabilityPackageId, valueOrNull(proto.hasApplicationId(), proto.getApplicationId()));
        set(builder::capabilityId, valueOrNull(proto.hasSkillId(), proto.getSkillId()));
        set(builder::executionParametersJson, proto.hasExecutionParameters()
                ? ProtoJsonUtils.toJson(proto.getExecutionParameters()) : null);
        set(builder::autoStart, valueOrNull(proto.hasAutoStart(), proto.getAutoStart()));

        return builder.build();
    }

    public SchedulerProtoDTO map(SchedulerDTO dto) {
        if (dto == null)
            return null;

        SchedulerProtoDTO.Builder builder = SchedulerProtoDTO.newBuilder();

        set(builder::setId, uuidString(dto.getId()));
        set(builder::setName, dto.getName());
        set(builder::setCronExpression, dto.getCronExpression());
        set(builder::setActive, dto.getActive());
        set(builder::setType, dto.getType());
        set(builder::setClientTimeZone, dto.getClientTimeZone());
        set(builder::setAssetSn, dto.getAssetSn());
        set(builder::setCommandId, dto.getCommandId());
        set(builder::setApplicationId, dto.getCapabilityPackageId());
        set(builder::setSkillId, dto.getCapabilityId());
        if (dto.getExecutionParametersJson() != null) {
            builder.setExecutionParameters((com.google.protobuf.Struct) ProtoJsonUtils.fromJson(
                    dto.getExecutionParametersJson(), com.google.protobuf.Struct.newBuilder()));
        }
        set(builder::setAutoStart, dto.getAutoStart());

        return builder.build();
    }

    public WaypointDTO map(WaypointProtoDTO proto) {
        if (proto == null)
            return null;

        WaypointDTO.WaypointDTOBuilder builder = WaypointDTO.builder();

        set(builder::latitude, proto.getLatitude());
        set(builder::longitude, proto.getLongitude());
        set(builder::altitude, valueOrNull(proto.hasAltitude(), proto.getAltitude()));
        set(builder::speed, valueOrNull(proto.hasSpeed(), proto.getSpeed()));
        set(builder::flyThrough, valueOrNull(proto.hasFlyTrough(), proto.getFlyTrough()));
        set(builder::vehicleAction, valueOrNull(proto.hasVehicleAction(), proto.getVehicleAction()));
        set(builder::wpOrder, valueOrNull(proto.hasWpOrder(), proto.getWpOrder()));
        set(builder::gimbalPitch, valueOrNull(proto.hasGimbalPitch(), proto.getGimbalPitch()));
  

        return builder.build();
    }

    public WaypointProtoDTO map(WaypointDTO dto) {
        if (dto == null)
            return null;

        WaypointProtoDTO.Builder builder = WaypointProtoDTO.newBuilder();

        set(builder::setLatitude, dto.getLatitude());
        set(builder::setLongitude, dto.getLongitude());
        set(builder::setAltitude, dto.getAltitude());
        set(builder::setSpeed, dto.getSpeed());
        set(builder::setFlyTrough, dto.getFlyThrough());
        set(builder::setVehicleAction, dto.getVehicleAction());
        set(builder::setWpOrder, dto.getWpOrder());
        set(builder::setGimbalPitch, dto.getGimbalPitch());

        return builder.build();
    }

    private static Coordinates coordinates(Double latitude, Double longitude, Double altitude) {
        Coordinates coordinates = new Coordinates();
        coordinates.setLatitude(latitude);
        coordinates.setLongitude(longitude);
        coordinates.setAltitude(altitude);
        return coordinates;
    }

    private static <T> void set(Consumer<T> setter, T value) {
        if (value != null) {
            setter.accept(value);
        }
    }

    private static <B, T> B apply(B builder, BiConsumer<B, T> setter, T value) {
        if (value != null) {
            setter.accept(builder, value);
        }
        return builder;
    }

    private static <T> T valueOrNull(boolean condition, T value) {
        return condition ? value : null;
    }

    private static UUID uuid(String value) {
        return value == null || value.isEmpty() ? null : UUID.fromString(value);
    }

    private static String uuidString(UUID value) {
        return value == null ? null : value.toString();
    }

    // Timestamp conversion utilities
    public LocalDateTime toLocalDateTime(Timestamp timestamp) {
        if (timestamp == null) {
            return null;
        }
        Instant instant = Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }

    public Timestamp toTimestamp(LocalDateTime localDateTime) {
        if (localDateTime == null) {
            return null;
        }
        Instant instant = localDateTime.atZone(ZoneId.systemDefault()).toInstant();
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }
}
