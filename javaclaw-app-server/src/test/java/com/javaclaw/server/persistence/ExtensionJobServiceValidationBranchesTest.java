package com.javaclaw.server.persistence;

import java.time.Clock;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.ExecutionState;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.protocol.CanonicalJson;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ExtensionJobServiceValidationBranchesTest {
    @TempDir
    java.nio.file.Path temporaryDirectory;

    private ExtensionJobService jobs;

    @BeforeEach
    void initializeDataV6() {
        H2Database database = new H2Database(temporaryDirectory.resolve("data-v6"));
        database.initialize();
        jobs = new ExtensionJobService(database, new CanonicalJson(), Clock.systemUTC());
    }

    @Test
    void page分别拒绝低于与超过平台上限的页大小() {
        Optional<WorkspaceId> workspace = Optional.empty();
        Optional<ExtensionId> extension = Optional.empty();
        Set<ExecutionState> states = Set.of();

        assertThrows(
                IllegalArgumentException.class, () -> jobs.page(workspace, extension, states, Optional.empty(), 0));
        assertThrows(
                IllegalArgumentException.class, () -> jobs.page(workspace, extension, states, Optional.empty(), 201));
    }

    @Test
    void jobCounts逐字段拒绝负数() {
        assertThrows(IllegalArgumentException.class, () -> new ExtensionJobService.JobCounts(-1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ExtensionJobService.JobCounts(0, -1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ExtensionJobService.JobCounts(0, 0, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new ExtensionJobService.JobCounts(0, 0, 0, -1));
    }
}
