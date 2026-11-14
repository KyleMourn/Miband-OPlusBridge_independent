// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.kylemourn.mibandoplusbridge.service;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class HostKeepAliveTest {
    @Test public void rootStartOnlyAllowsTheBridgeForegroundService() {
        assertEquals("am start-foreground-service --user 0 -n "
                        + io.github.kylemourn.mibandoplusbridge.BuildConfig.APPLICATION_ID
                        + "/io.github.kylemourn.mibandoplusbridge.service.BandLiveService",
                OwnershipController.rootStartCommand(
                        io.github.kylemourn.mibandoplusbridge.BuildConfig.APPLICATION_ID,
                        "io.github.kylemourn.mibandoplusbridge.service.BandLiveService", true));
        // Standalone build: OPPO Health stays outside the module, so no host service is started as root.
        assertNull(OwnershipController.rootStartCommand("com.heytap.health",
                "com.heytap.health.rpc.host.HealthRpcMsgService", false));
        assertNull(OwnershipController.rootStartCommand("com.heytap.health",
                "com.heytap.health.rpc.host.HealthRpcMsgService", true));
        assertNull(OwnershipController.rootStartCommand(
                io.github.kylemourn.mibandoplusbridge.BuildConfig.APPLICATION_ID,
                "io.github.kylemourn.mibandoplusbridge.service.BandLiveService", false));
        assertNull(OwnershipController.rootStartCommand("com.example", "com.example.Evil", true));
    }

    @Test public void rootWakeClaimsOncePerGap() {
        AtomicLong last = new AtomicLong();
        assertTrue(HostKeepAlive.claim(last, 1_000, HostKeepAlive.ROOT_GAP_MS));
        assertFalse(HostKeepAlive.claim(last, 1_000 + HostKeepAlive.ROOT_GAP_MS - 1, HostKeepAlive.ROOT_GAP_MS));
        assertTrue(HostKeepAlive.claim(last, 1_000 + HostKeepAlive.ROOT_GAP_MS, HostKeepAlive.ROOT_GAP_MS));
    }
}
