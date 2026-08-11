package com.javaclaw.system;

import com.javaclaw.platform.data.DataRoot;
import com.javaclaw.platform.spring.ApplicationContexts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class CommandSessionManagerTest {

    @Test
    void interactiveOutputUsesManagedVirtualThreadAndClosesProcess(
            @TempDir Path temporaryDirectory) throws Exception {
        assumeFalse(System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT).contains("win"));
        try (var root = ApplicationContexts.createRoot(
                new DataRoot(temporaryDirectory.resolve("data-v3")))) {
            CommandSessionManager sessions = root.getBean(CommandSessionManager.class);
            CommandSessionManager.ShellSession session =
                    sessions.open(temporaryDirectory.toString());

            session.sendInput("printf 'managed-output\\n'; printf '__DONE__\\n'\n");
            CommandSessionManager.MarkerHit hit =
                    session.waitForMarker("__DONE__", 3_000);

            assertNotNull(hit);
            assertTrue(hit.body().contains("managed-output"), hit.body());
            assertTrue(sessions.close(session.id()));
            assertEquals(0, sessions.count());
        }
    }
}
