package com.javaclaw.desktop.nativebridge;

import com.javaclaw.desktop.api.DesktopApplicationLaunch;
import com.javaclaw.platform.data.ApplicationHome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class NativeDesktopApplicationLifecycleTest {
    @TempDir Path temporary;

    @Test
    void aTerminatedWindowsLauncherCannotProveTheApplicationExited() throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java");
        Process child = new ProcessBuilder(java.toString(), "-version").redirectErrorStream(true).start();
        try {
            assertTrue(child.waitFor(5, TimeUnit.SECONDS), "the fixture launcher must actually exit");
            var launch = new DesktopApplicationLaunch(child.pid(), "com.example.reader", "accepted");
            var home = ApplicationHome.at(temporary);
            assertEquals(Optional.empty(), NativeDesktopProvider.windows(home)
                    .isLaunchedApplicationRunning(launch),
                    "ShellExecuteEx may hand off to an application whose window appears later");
            assertEquals(Optional.of(false), NativeDesktopProvider.macos(home)
                    .isLaunchedApplicationRunning(launch),
                    "macOS launch returns the NSRunningApplication primary PID");
        } finally { if (child.isAlive()) child.destroyForcibly(); }
    }

    @Test
    void anAliveLaunchProcessCannotAuthorizeAnotherLaunchOnEitherPlatform() throws Exception {
        var launch = new DesktopApplicationLaunch(ProcessHandle.current().pid(),
                "com.example.reader", "accepted");
        var home = ApplicationHome.at(temporary);
        assertEquals(Optional.of(true), NativeDesktopProvider.windows(home).isLaunchedApplicationRunning(launch));
        assertEquals(Optional.of(true), NativeDesktopProvider.macos(home).isLaunchedApplicationRunning(launch));
    }
}
