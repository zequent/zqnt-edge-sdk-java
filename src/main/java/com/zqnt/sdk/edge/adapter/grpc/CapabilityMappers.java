package com.zqnt.sdk.edge.adapter.grpc;

import com.google.protobuf.util.Timestamps;
import com.zqnt.protos.capability.v3.CapabilityErrorSpec;
import com.zqnt.protos.capability.v3.CapabilityEventSpec;
import com.zqnt.protos.capability.v3.CapabilitySet;
import com.zqnt.protos.capability.v3.CapabilitySource;
import com.zqnt.protos.capability.v3.CapabilityState;
import com.zqnt.protos.capability.v3.SnapshotState;
import com.zqnt.protos.capability.v3.Target;
import com.zqnt.protos.capability.v3.TargetType;
import com.zqnt.sdk.edge.adapter.domains.Capability;
import com.zqnt.sdk.edge.adapter.domains.CapabilityError;
import com.zqnt.sdk.edge.adapter.domains.CapabilityEvent;
import com.zqnt.sdk.edge.adapter.domains.CapabilityRequirements;
import com.zqnt.sdk.edge.adapter.domains.CurrentCapabilities;
import com.zqnt.sdk.edge.support.Structs;
import com.zqnt.utils.core.ProtobufHelpers;
import com.zqnt.utils.devicecontrol.proto.CapabilityErrorProto;
import com.zqnt.utils.devicecontrol.proto.CapabilityEventProto;
import com.zqnt.utils.devicecontrol.proto.CapabilityRequirementsProto;
import com.zqnt.utils.devicecontrol.proto.CapabilityTarget;
import com.zqnt.utils.devicecontrol.proto.CapabilityTargetType;

import java.util.List;
import java.util.Map;

/** An adapter's capabilities as the v2 and the v3 contract carry them. */
public final class CapabilityMappers {

	private CapabilityMappers() {
	}

	public static CapabilitySet toV3(String requestedSn, CurrentCapabilities current) {
		CapabilitySet.Builder set = CapabilitySet.newBuilder()
				.setAssetSn(current.getSn() == null ? requestedSn : current.getSn())
				.setAssetType(current.getAssetType() == null ? "" : current.getAssetType().name())
				.setObservedAt(current.getTimestamp() > 0 ? Timestamps.fromMillis(current.getTimestamp()) : ProtobufHelpers.now())
				.setSnapshotState(SnapshotState.SNAPSHOT_STATE_CURRENT);
		if (current.getCapabilities() != null) {
			current.getCapabilities().stream().map(CapabilityMappers::toV3).forEach(set::addCapabilities);
		}
		if (current.getTelemetryFields() != null) {
			set.addAllTelemetryFields(current.getTelemetryFields());
		}
		return set.build();
	}

	public static com.zqnt.protos.capability.v3.Capability toV3(Capability value) {
		String id = value.getCommand() == null ? "" : value.getCommand();
		var builder = com.zqnt.protos.capability.v3.Capability.newBuilder()
				.setCommandId(id)
				.setDisplayName(id)
				// v2 and v3 share the numbering of these enums on purpose.
				.setStateValue(value.getState() == null ? CapabilityState.CAPABILITY_STATE_AVAILABLE_VALUE : value.getState().getNumber())
				.setTarget(Target.newBuilder()
						.setTypeValue(value.getTargetType() == null ? TargetType.TARGET_TYPE_ASSET_VALUE : value.getTargetType().getNumber())
						.setRef(value.getTargetRef() == null ? "" : value.getTargetRef()));
		if (value.getDescription() != null) builder.setDescription(value.getDescription());
		if (value.getUnavailableReason() != null) builder.setUnavailableReason(value.getUnavailableReason());
		if (value.getMetadata() != null) builder.putAllMetadata(value.getMetadata());
		if (notEmpty(value.getConstraints())) builder.setConstraints(Structs.toStruct(value.getConstraints()));
		if (notEmpty(value.getInputSchema())) builder.setInputSchema(Structs.toStruct(value.getInputSchema()));
		if (notEmpty(value.getOutputSchema())) builder.setOutputSchema(Structs.toStruct(value.getOutputSchema()));
		if (value.getSchemaVersion() != null) builder.setSchemaVersion(value.getSchemaVersion());
		if (value.getSkillId() != null) builder.setSkillId(value.getSkillId());
		if (value.getProvider() != null) builder.setProvider(value.getProvider());
		builder.setSourceValue(value.getSource() == null ? CapabilitySource.CAPABILITY_SOURCE_EDGE_ADAPTER_VALUE : value.getSource().getNumber());
		if (value.getCompletion() != null) builder.setCompletion(value.getCompletion());
		if (value.getCompletionEvent() != null) builder.setCompletionEvent(value.getCompletionEvent());
		if (value.getErrors() != null) {
			value.getErrors().forEach(error -> builder.addErrors(CapabilityErrorSpec.newBuilder()
					.setCode(text(error.getCode()))
					.setDescription(text(error.getDescription()))));
		}
		if (value.getEvents() != null) {
			value.getEvents().forEach(event -> {
				var spec = CapabilityEventSpec.newBuilder()
						.setName(text(event.getName()))
						.setDescription(text(event.getDescription()));
				if (notEmpty(event.getPayloadSchema())) spec.setPayloadSchema(Structs.toStruct(event.getPayloadSchema()));
				builder.addEvents(spec);
			});
		}
		if (value.getRequirements() != null) {
			CapabilityRequirements requirements = value.getRequirements();
			var proto = com.zqnt.protos.capability.v3.CapabilityRequirements.newBuilder()
					.addAllAssetTypes(list(requirements.getAssetTypes()))
					.addAllPayloads(list(requirements.getPayloads()))
					.addAllRuntimeFeatures(list(requirements.getRuntimeFeatures()));
			if (notEmpty(requirements.getProperties())) proto.setProperties(Structs.toStruct(requirements.getProperties()));
			builder.setRequirements(proto);
		}
		return builder.build();
	}

	public static com.zqnt.utils.devicecontrol.proto.Capability toV2(Capability value) {
		var builder = com.zqnt.utils.devicecontrol.proto.Capability.newBuilder()
				.setCommandId(text(value.getCommand()))
				.setDisplayName(text(value.getCommand()))
				.setState(value.getState() == null
						? com.zqnt.utils.devicecontrol.proto.CapabilityState.CAPABILITY_STATE_AVAILABLE : value.getState());
		if (value.getDescription() != null) builder.setDescription(value.getDescription());
		if (value.getUnavailableReason() != null) builder.setUnavailableReason(value.getUnavailableReason());
		if (value.getMetadata() != null) builder.putAllMetadata(value.getMetadata());
		if (value.getConstraints() != null) builder.setConstraints(Structs.toStruct(value.getConstraints()));
		if (value.getInputSchema() != null) builder.setInputSchema(Structs.toStruct(value.getInputSchema()));
		if (value.getOutputSchema() != null) builder.setOutputSchema(Structs.toStruct(value.getOutputSchema()));
		if (value.getSchemaVersion() != null) builder.setSchemaVersion(value.getSchemaVersion());
		CapabilityTarget.Builder target = CapabilityTarget.newBuilder().setType(value.getTargetType() == null
				? CapabilityTargetType.CAPABILITY_TARGET_TYPE_ASSET : value.getTargetType());
		if (value.getTargetRef() != null) target.setTargetRef(value.getTargetRef());
		builder.setTarget(target);
		if (value.getErrors() != null) {
			value.getErrors().forEach(error -> builder.addErrors(toV2(error)));
		}
		if (value.getEvents() != null) {
			value.getEvents().forEach(event -> builder.addEvents(toV2(event)));
		}
		if (value.getRequirements() != null) builder.setRequirements(toV2(value.getRequirements()));
		if (value.getSkillId() != null) builder.setSkillId(value.getSkillId());
		if (value.getSource() != null) builder.setSource(value.getSource());
		if (value.getProvider() != null) builder.setProvider(value.getProvider());
		return builder.build();
	}

	private static CapabilityErrorProto toV2(CapabilityError value) {
		CapabilityErrorProto.Builder builder = CapabilityErrorProto.newBuilder().setCode(text(value.getCode()));
		if (value.getDescription() != null) builder.setDescription(value.getDescription());
		return builder.build();
	}

	private static CapabilityEventProto toV2(CapabilityEvent value) {
		CapabilityEventProto.Builder builder = CapabilityEventProto.newBuilder()
				.setName(text(value.getName()))
				.setPayloadSchema(Structs.toStruct(value.getPayloadSchema() == null ? Map.of() : value.getPayloadSchema()));
		if (value.getDescription() != null) builder.setDescription(value.getDescription());
		return builder.build();
	}

	private static CapabilityRequirementsProto toV2(CapabilityRequirements value) {
		return CapabilityRequirementsProto.newBuilder()
				.addAllAssetTypes(list(value.getAssetTypes()))
				.addAllPayloads(list(value.getPayloads()))
				.addAllRuntimeFeatures(list(value.getRuntimeFeatures()))
				.setProperties(Structs.toStruct(value.getProperties() == null ? Map.of() : value.getProperties()))
				.build();
	}

	private static boolean notEmpty(Map<String, Object> map) {
		return map != null && !map.isEmpty();
	}

	private static List<String> list(List<String> values) {
		return values == null ? List.of() : values;
	}

	private static String text(String value) {
		return value == null ? "" : value;
	}
}
