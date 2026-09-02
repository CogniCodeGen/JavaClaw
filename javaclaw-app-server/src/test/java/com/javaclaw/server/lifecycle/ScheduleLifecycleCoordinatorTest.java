package com.javaclaw.server.lifecycle;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.WorkspaceId;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.LifecycleLeaseRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScheduleLifecycleCoordinatorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void allWorkspacesShareOneLeaseAndLastDisableRemovesLoginStartup() {
        H2Database database = new H2Database(temporaryDirectory.resolve("data-v5"));
        database.initialize();
        List<Boolean> startupChanges = new ArrayList<>();
        try (LifecycleCoordinator lifecycle =
                        new LifecycleCoordinator(new LifecycleLeaseRepository(database, Clock.systemUTC()));
                ScheduleLifecycleCoordinator schedules =
                        new ScheduleLifecycleCoordinator(lifecycle, startupChanges::add)) {
            WorkspaceId first = WorkspaceId.random();
            WorkspaceId second = WorkspaceId.random();

            schedules.synchronize(first, true);
            schedules.synchronize(second, true);
            schedules.synchronize(first, false);

            assertEquals(List.of(true), startupChanges);
            assertEquals(
                    new ScheduleLifecycleCoordinator.Status(true, true, 2, true, false, Optional.empty()),
                    schedules.status());
            assertEquals(1, lifecycle.status().activeLeases());

            schedules.synchronize(second, false);
            schedules.repairLoginStartup();

            assertEquals(List.of(true, false, false), startupChanges);
            assertEquals(
                    new ScheduleLifecycleCoordinator.Status(false, false, 2, true, false, Optional.empty()),
                    schedules.status());
            assertEquals(0, lifecycle.status().activeLeases());
        }
    }
}
