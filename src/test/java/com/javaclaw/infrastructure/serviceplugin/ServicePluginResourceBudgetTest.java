package com.javaclaw.infrastructure.serviceplugin;

import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService.EndpointConfiguration;
import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService.Protocol;
import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService.ResourceConfiguration;
import com.javaclaw.platform.system.SystemMemoryProbe;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServicePluginResourceBudgetTest {

    @Test
    void accountsMinimumProcessEndpointAndDescriptorCostsAndReleasesThem() {
        ServicePluginResourceBudget budget = budget(false);
        try (ServicePluginResourceBudget.Lease ignored = budget.reserve("light",
                new ResourceConfiguration(1, 0, 0, 1, 1), List.of())) {
            ServicePluginResourceBudget.Snapshot snapshot = budget.snapshot();
            assertEquals(256, snapshot.memoryMiB());
            assertEquals(1, snapshot.computeThreads());
            assertEquals(1, snapshot.connections());
            assertEquals(1, snapshot.endpoints());
            assertEquals(64, snapshot.fileDescriptors());
        }
        assertEquals(0, budget.snapshot().memoryMiB());
    }

    @Test
    void refusesMemoryThreadsConnectionsEndpointsAndDescriptorsBeyondGlobalBudget() {
        ServicePluginResourceBudget budget = budget(false);
        assertThrows(ServicePluginResourceBudget.ResourceExhaustedException.class,
                () -> budget.reserve("memory",
                        new ResourceConfiguration(1_025, 0, 1, 1, 64), List.of()));
        assertThrows(ServicePluginResourceBudget.ResourceExhaustedException.class,
                () -> budget.reserve("threads",
                        new ResourceConfiguration(256, 0, 5, 1, 64), List.of()));
        assertThrows(ServicePluginResourceBudget.ResourceExhaustedException.class,
                () -> budget.reserve("connections",
                        new ResourceConfiguration(256, 0, 1, 1, 64),
                        List.of(endpoint("a", 9))));
        assertThrows(ServicePluginResourceBudget.ResourceExhaustedException.class,
                () -> budget.reserve("endpoints",
                        new ResourceConfiguration(256, 0, 1, 1, 64),
                        List.of(endpoint("a", 1), endpoint("b", 1), endpoint("c", 1))));
        assertThrows(ServicePluginResourceBudget.ResourceExhaustedException.class,
                () -> budget.reserve("fds",
                        new ResourceConfiguration(256, 0, 1, 1, 257), List.of()));
    }

    @Test
    void softMemoryLimitOnlyBlocksDuringSeverePressure() {
        ServicePluginResourceBudget normal = budget(false);
        try (ServicePluginResourceBudget.Lease ignored = normal.reserve("normal",
                new ResourceConfiguration(900, 0, 1, 1, 64), List.of())) {
            assertEquals(900, normal.snapshot().memoryMiB());
        }
        ServicePluginResourceBudget pressured = budget(true);
        assertThrows(ServicePluginResourceBudget.ResourceExhaustedException.class,
                () -> pressured.reserve("pressured",
                        new ResourceConfiguration(900, 0, 1, 1, 64), List.of()));
    }

    @Test
    void onlyUsesReliableAvailableMemoryForSystemPressure() {
        long total = 16L * 1024 * 1024 * 1024;
        long low = 128L * 1024 * 1024;

        assertFalse(ServicePluginResourceBudget.isSevereMemoryPressure(
                new SystemMemoryProbe.Snapshot(total, low, false)));
        assertTrue(ServicePluginResourceBudget.isSevereMemoryPressure(
                new SystemMemoryProbe.Snapshot(total, low, true)));
        assertFalse(ServicePluginResourceBudget.isSevereMemoryPressure(
                new SystemMemoryProbe.Snapshot(total, 2L * 1024 * 1024 * 1024, true)));
    }

    private static ServicePluginResourceBudget budget(boolean pressure) {
        return new ServicePluginResourceBudget(
                new ServicePluginResourceBudget.Limits(768, 1_024, 4, 8, 2, 256),
                () -> pressure);
    }

    private static EndpointConfiguration endpoint(String id, int connections) {
        return new EndpointConfiguration(id, Protocol.HTTP, "127.0.0.1", 0,
                false, false, null, "", "abcdefghijklmnopqrstuvwxyz123456",
                60, 100_000, 1, connections, 1024);
    }
}
