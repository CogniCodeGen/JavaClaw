package com.javaclaw.browser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PlaywrightBrowserManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void rebindWorkspaceUpdatesPathsWithoutLaunchingBrowser() {
        PlaywrightBrowserManager manager = new PlaywrightBrowserManager(
                true, tempDir.resolve("browser-a"), tempDir.resolve("shots-a"));

        Path newBrowserDir = tempDir.resolve("browser-b");
        Path newScreenshotsDir = tempDir.resolve("shots-b");
        manager.rebindWorkspace(newBrowserDir, newScreenshotsDir);

        assertFalse(manager.isRunning());
        assertEquals(newScreenshotsDir, manager.getScreenshotDir());
    }
}
