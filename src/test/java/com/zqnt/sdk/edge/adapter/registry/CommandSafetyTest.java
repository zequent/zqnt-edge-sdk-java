package com.zqnt.sdk.edge.adapter.registry;

import com.zqnt.protos.capability.v3.CommandRisk;
import com.zqnt.protos.capability.v3.CommandSafety;
import com.zqnt.sdk.edge.adapter.domains.CommandResult;
import com.zqnt.sdk.edge.adapter.domains.CurrentCapabilities;
import com.zqnt.sdk.edge.adapter.grpc.CapabilityMappers;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class CommandSafetyTest {

	private static final CommandHandler OK = request -> CompletableFuture.completedFuture(CommandResult.success("ok", request.sn()));

	private static com.zqnt.protos.capability.v3.Capability advertised(CommandRegistry registry, String id) {
		CurrentCapabilities current = new CurrentCapabilities();
		current.setSn("SN-1");
		current.setCapabilities(registry.capabilities());
		return CapabilityMappers.toV3("SN-1", current).getCapabilitiesList().stream()
				.filter(capability -> capability.getCommandId().equals(id)).findFirst().orElseThrow();
	}

	@Test
	void aStandardCommandIsAdvertisedWithItsCatalogRisk() {
		CommandRegistry registry = new CommandRegistry();
		registry.register("flight.takeoff", Map.of(), Map.of(), OK);
		registry.register("flight.land", Map.of(), Map.of(), OK);
		registry.register("camera.take_photo", Map.of(), Map.of(), OK);
		registry.register("dock.open_cover", Map.of(), Map.of(), OK);

		CommandSafety takeoff = advertised(registry, "flight.takeoff").getSafety();
		assertEquals(CommandRisk.COMMAND_RISK_MOVE, takeoff.getRisk());
		assertTrue(takeoff.getReversible());
		assertEquals("flight.return_to_home", takeoff.getUndoCommandId());
		assertEquals(CommandRisk.COMMAND_RISK_CRITICAL, advertised(registry, "flight.land").getSafety().getRisk());
		assertEquals(CommandRisk.COMMAND_RISK_OBSERVE, advertised(registry, "camera.take_photo").getSafety().getRisk());
		assertEquals(CommandRisk.COMMAND_RISK_ADJUST, advertised(registry, "dock.open_cover").getSafety().getRisk());
	}

	@Test
	void theAdaptersOwnSafetyWins() {
		CommandRegistry registry = new CommandRegistry();
		registry.register("dock.open_cover", Map.of(), Map.of(), OK)
				.setSafety(CommandSafety.newBuilder().setRisk(CommandRisk.COMMAND_RISK_MOVE)
						.addPreconditions("no aircraft above the dock").build());
		registry.register("vendor.acme.spray", Map.of(), Map.of(), OK)
				.setSafety(CommandSafety.newBuilder().setRisk(CommandRisk.COMMAND_RISK_CRITICAL).build());

		assertEquals(CommandRisk.COMMAND_RISK_MOVE, advertised(registry, "dock.open_cover").getSafety().getRisk());
		assertEquals(List.of("no aircraft above the dock"), advertised(registry, "dock.open_cover").getSafety().getPreconditionsList());
		assertEquals(CommandRisk.COMMAND_RISK_CRITICAL, advertised(registry, "vendor.acme.spray").getSafety().getRisk());
	}

	@Test
	void anUnknownIdDeclaresNothingAndAnUnratedCatalogIdCountsAsCritical() {
		CommandRegistry registry = new CommandRegistry();
		registry.register("vendor.acme.spray", Map.of(), Map.of(), OK);
		registry.register("vendor.betaflight.arm", Map.of(), Map.of(), OK);

		assertFalse(advertised(registry, "vendor.acme.spray").hasSafety());
		assertEquals(CommandRisk.COMMAND_RISK_CRITICAL, advertised(registry, "vendor.betaflight.arm").getSafety().getRisk());
	}

	@Test
	void aPropertySetterMatchesTheCatalogPattern() {
		assertEquals(CommandRisk.COMMAND_RISK_ADJUST,
				CommandCatalog.safety("asset.property.night_lights_state.set").orElseThrow().getRisk());
		assertTrue(CommandCatalog.safety("asset.property.set").isEmpty());
	}
}
