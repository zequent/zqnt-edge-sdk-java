package com.zqnt.sdk.edge.connector.application;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectorServicePairingTest {

    @Test
    void anAdapterPairsByClaimCodeAndCannotNameAnAssetIntoExistence() {
        var methods = Arrays.stream(ConnectorService.class.getMethods()).map(java.lang.reflect.Method::getName).toList();
        assertFalse(methods.contains("registerAsset"));
        assertTrue(methods.contains("ensureAsset"));
        assertTrue(methods.contains("redeemAssetClaim"));
    }
}
