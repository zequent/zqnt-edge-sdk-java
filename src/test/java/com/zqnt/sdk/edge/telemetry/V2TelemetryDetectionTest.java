package com.zqnt.sdk.edge.telemetry;

import com.zqnt.protos.common.v3.AssetRef;
import com.zqnt.protos.common.v3.GeoPoint;
import com.zqnt.protos.edge.v3.Detection;
import com.zqnt.protos.telemetry.v3.DetectionBatch;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V2TelemetryDetectionTest {

	private static DetectionBatch batch(Detection detection) {
		return DetectionBatch.newBuilder().setAsset(AssetRef.newBuilder().setSn("RADAR-1")).addDetections(detection).build();
	}

	@Test
	void radarGeometryReachesTheV2Position() {
		var detection = Detection.newBuilder().setObjectId("track-7").setObjectType("UAV")
				.setPosition(GeoPoint.newBuilder().setLatitude(52.5).setLongitude(13.4).setAltitude(120))
				.setRangeMeters(900).setBearingDegrees(45).setElevationDegrees(3).setSpeed(12).setHeadingDegrees(270)
				.build();

		var position = V2Telemetry.toV2(batch(detection)).getDetections(0).getPosition();

		assertEquals(52.5, position.getLatitude());
		assertEquals(120, position.getAltitude());
		assertEquals(900, position.getRangeM());
		assertEquals(45, position.getBearingDeg());
		assertEquals(3, position.getElevationDeg());
		assertEquals(12, position.getSpeedMps());
		assertEquals(270, position.getHeadingDeg());
	}

	@Test
	void rangeAndBearingWithoutCoordinatesLeaveThemNaN() {
		var detection = Detection.newBuilder().setObjectType("UAV").setRangeMeters(900).setBearingDegrees(45).build();

		var position = V2Telemetry.toV2(batch(detection)).getDetections(0).getPosition();

		assertTrue(Double.isNaN(position.getLatitude()));
		assertTrue(Double.isNaN(position.getLongitude()));
		assertEquals(900, position.getRangeM());
		assertFalse(position.hasSpeedMps());
	}

	@Test
	void aCameraDetectionHasNoV2Position() {
		var detection = Detection.newBuilder().setObjectType("person").build();

		assertFalse(V2Telemetry.toV2(batch(detection)).getDetections(0).hasPosition());
	}
}
