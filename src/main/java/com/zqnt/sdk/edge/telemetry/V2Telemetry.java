package com.zqnt.sdk.edge.telemetry;

import com.zqnt.protos.edge.v3.Detection;
import com.zqnt.protos.telemetry.v3.TelemetrySample;
import com.zqnt.utils.common.proto.BoundingBox;
import com.zqnt.utils.common.proto.DetectionBatch;
import com.zqnt.utils.common.proto.DetectionPosition;
import com.zqnt.utils.common.proto.DetectionResult;
import com.zqnt.utils.common.proto.RequestBase;
import com.zqnt.utils.livedata.proto.ProduceTelemetryRequest;
import com.zqnt.utils.livedata.proto.Telemetry;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * v3 live data in the v2 shape, for a platform without TelemetryIngestService. Only the shared
 * fields survive: v2 has no place for a sample's {@code details}. A sample with a speed is an
 * aircraft's (v2 sub-asset), one with only a battery a dock's or single device's (v2 asset), the
 * same split the platform makes.
 */
public final class V2Telemetry {

	private V2Telemetry() {
	}

	public static ProduceTelemetryRequest toV2(TelemetrySample sample) {
		RequestBase.Builder base = RequestBase.newBuilder()
				.setTid(UUID.randomUUID().toString())
				.setSn(sample.getAsset().getSn())
				.setTimestamp(sample.getObservedAt());
		if (!sample.getAsset().getId().isBlank()) base.setAssetId(sample.getAsset().getId());

		Telemetry.Builder telemetry = Telemetry.newBuilder()
				.setId(UUID.randomUUID().toString())
				.setSn(sample.getAsset().getSn())
				.setTimestamp(sample.getObservedAt());
		if (sample.hasPosition()) {
			telemetry.setLatitude(sample.getPosition().getLatitude()).setLongitude(sample.getPosition().getLongitude());
			if (sample.getPosition().hasAltitude()) telemetry.setAbsoluteAltitude((float) sample.getPosition().getAltitude());
		}
		if (sample.hasRelativeAltitude()) telemetry.setRelativeAltitude((float) sample.getRelativeAltitude());
		if (sample.hasHeadingDegrees()) telemetry.setHeading((float) sample.getHeadingDegrees());
		if (sample.hasHorizontalSpeed() || sample.hasVerticalSpeed()) {
			var aircraft = telemetry.getSubAssetBuilder();
			if (sample.hasHorizontalSpeed()) aircraft.setHorizontalSpeed((float) sample.getHorizontalSpeed());
			if (sample.hasVerticalSpeed()) aircraft.setVerticalSpeed((float) sample.getVerticalSpeed());
			if (sample.hasBatteryPercent()) {
				aircraft.getBatteryInformationBuilder().setPercentage(
						BigDecimal.valueOf(sample.getBatteryPercent()).stripTrailingZeros().toPlainString());
			}
		} else if (sample.hasBatteryPercent()) {
			telemetry.getAssetBuilder().setSubAssetPercentage((float) sample.getBatteryPercent());
		}
		return ProduceTelemetryRequest.newBuilder().setBase(base).setData(telemetry).build();
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
