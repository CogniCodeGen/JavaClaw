package com.javaclaw.infrastructure.serviceplugin;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServicePluginProcessLeaseStoreTest {
    @Test
    void recognizesOnlyCurrentPidRecordCommands() {
        ServicePluginProcessLeaseStore.PidRecord builtIn =
                new ServicePluginProcessLeaseStore.PidRecord(1, 2, "fixture", "1.0",
                        "aaa", 3,
                        "com.javaclaw.service.runner.ServicePluginProcessMain",
                        "/plugins/fixture.jar");

        assertTrue(ServicePluginProcessLeaseStore.matches(builtIn,
                List.of("-cp", "/host.jar", builtIn.runnerMainClass(), builtIn.pluginPath())));
        assertFalse(ServicePluginProcessLeaseStore.matches(builtIn,
                List.of("-cp", "/host.jar", builtIn.runnerMainClass(), "/plugins/other.jar")));
    }
}
