package com.zqnt.sdk.edge.telemetry;

import com.zqnt.protos.edge.v3.Detection;
import com.zqnt.utils.common.proto.BoundingBox;
import com.zqnt.utils.common.proto.DetectionBatch;
import com.zqnt.utils.common.proto.DetectionPosition;
import com.zqnt.utils.common.proto.DetectionResult;
import com.zqnt.utils.common.proto.RequestBase;

import java.util.UUID;

/**
 * v3 detections in the v2 shape, for a platform without TelemetryIngestService. Samples go through
 * {@link com.zqnt.utils.telemetry.TelemetrySampleMapper}.
 */
public final class V2Telemetry {

	private V2Telemetry() {
	}

	public static DetectionBatch toV2(com.zqnt.protos.telemetry.v3.DetectionBatch batch) {
		RequestBase.Builder base = RequestBase.newBuilder()
				.setTid(UUID.randomUUID().toString())
				.setSn(batch.getAsset().getSn())
				.setTimestamp(batch.getObservedAt());
		if (!batch.getAsset().getId().isBlank()) base.setAssetId(batch.getAsset().getId());
		DetectionBatch.Builder v2 = DetectionBatch.newBuilder().setBase(base);
		if (!batch.getStreamUrl().isEmpty()) v2.setStreamUrl(batch.getStreamUrl());
		for (Detection detection : batch.getDetectionsList()) {
			DetectionResult.Builder result = DetectionResult.newBuilder()
					.setObjectId(detection.getObjectId())
					.setObjectType(detection.getObjectType())
					.setConfidence(detection.getConfidence());
			if (detection.hasBoundingBox()) {
				var box = detection.getBoundingBox();
				result.setBoundingBox(BoundingBox.newBuilder()
						.setX(box.getX()).setY(box.getY()).setWidth(box.getWidth()).setHeight(box.getHeight()));
			}
			if (detection.hasPosition()) {
				DetectionPosition.Builder position = DetectionPosition.newBuilder()
						.setLatitude(detection.getPosition().getLatitude())
						.setLongitude(detection.getPosition().getLongitude());
				if (detection.getPosition().hasAltitude()) position.setAltitude(detection.getPosition().getAltitude());
				result.setPosition(position);
			}
			v2.addDetections(result);
		}
		return v2.build();
	}
}
