package com.javaclaw.nativehost.sandbox;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.ResourceLimits;
import com.javaclaw.api.SandboxMode;
import com.javaclaw.nativehost.network.SandboxNetworkAccess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MacInteractiveProfileTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void 可见策略只声明精确GUI服务和实例Unix路径且能由Seatbelt解析() throws Exception {
        Assumptions.assumeTrue(
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac"));
        Path executable = Path.of("/usr/bin/true").toRealPath();
        ValidatedSandboxCommand command = new ValidatedSandboxCommand(
                "profile-syntax-only",
                List.of(executable.toString()),
                executable,
                temporaryDirectory.toRealPath(),
                Map.of(),
                new byte[0],
                SandboxMode.BATCH,
                Duration.ofSeconds(5),
                new ResourceLimits(128L * 1024 * 1024, 1024, 1, 32),
                List.of(executable.getParent()),
                List.of(temporaryDirectory.toRealPath()),
                List.of(executable),
                true,
                Optional.empty(),
                SandboxNetworkAccess.offline());
        String profile = MacSandboxCommandBuilder.interactiveProfile(command);
        assertTrue(profile.contains("(global-name \"com.apple.windowserver.active\")"));
        assertTrue(profile.contains("(global-name \"com.apple.coreservices.launchservicesd\")"));
        assertTrue(profile.contains("(local-name-prefix \"org.chromium.Chromium.MachPortRendezvousServer.\")"));
        assertTrue(profile.contains(
                "(allow network-inbound network-outbound (subpath \"" + temporaryDirectory.toRealPath()));
        assertFalse(profile.contains("global-name-prefix"));
        assertFalse(profile.contains("(allow network*)"));
        assertFalse(profile.contains("(remote tcp"));
        // 只验证策略语法，true 不调用 GUI/Mach API；真正可见启动仍必须先通过私有 namespace 证明。
        Process parsed = new ProcessBuilder("/usr/bin/sandbox-exec", "-p", profile, executable.toString())
                .redirectErrorStream(true)
                .start();
        assertTrue(parsed.waitFor(5, TimeUnit.SECONDS));
        assertEquals(0, parsed.exitValue(), () -> {
            try {
                return new String(parsed.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            } catch (java.io.IOException failure) {
                return failure.getClass().getSimpleName();
            }
        });
    }
}
