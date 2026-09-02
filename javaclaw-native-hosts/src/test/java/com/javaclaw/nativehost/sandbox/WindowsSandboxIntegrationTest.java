package com.javaclaw.nativehost.sandbox;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.ApprovalRequirement;
import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.FilePermission;
import com.javaclaw.api.NetworkPermission;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.ProcessPermission;
import com.javaclaw.api.ResourceLimits;
import com.javaclaw.api.SandboxCommand;
import com.javaclaw.api.SandboxFrame;
import com.javaclaw.api.SandboxMode;
import com.javaclaw.api.SandboxSession;
import com.javaclaw.api.ToolPermission;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.nativehost.ffm.WindowsSandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs(OS.WINDOWS)
class WindowsSandboxIntegrationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void appContainerAllowsDeclaredWriteButDeniesDeleteAndEnforcesWallTimeout() throws Exception {
        assertTrue(WindowsSandbox.isSupported(), "required Windows Sandbox APIs must load on the release runner");
        Path protectedFile = temporaryDirectory.resolve("protected.txt");
        Files.writeString(protectedFile, "before", StandardCharsets.UTF_8);
        PermissionProfile permission = permission(false, false);
        String changeAndDelete = "echo changed>protected.txt & del /q protected.txt";

        var writeResult = new PlatformSandboxExecutor()
                .execute(batch(changeAndDelete, Duration.ofSeconds(5)), permission, new CancellationSource());

        assertFalse(writeResult.timedOut());
        assertTrue(Files.isRegularFile(protectedFile));
        assertTrue(Files.readString(protectedFile, StandardCharsets.UTF_8).contains("changed"));

        String busyLoop = "for /L %i in (1,1,2147483647) do @rem";
        var timeoutResult = new PlatformSandboxExecutor()
                .execute(batch(busyLoop, Duration.ofMillis(150)), permission, new CancellationSource());

        assertTrue(timeoutResult.timedOut());
        assertEquals(-1, timeoutResult.exitCode());
    }

    @Test
    void conPtySupportsBackpressuredOutputResizeAndInput() throws Exception {
        assertTrue(WindowsSandbox.isSupported(), "required Windows Sandbox APIs must load on the release runner");
        PermissionProfile permission = permission(true, true);
        SandboxCommand command = command(List.of(cmd().toString(), "/d", "/q"), SandboxMode.PTY, Duration.ofSeconds(5));
        CollectingSubscriber subscriber = new CollectingSubscriber();

        try (SandboxSession session =
                new PlatformSandboxExecutor().open(command, permission, new CancellationSource())) {
            session.frames().subscribe(subscriber);
            session.resize(100, 36).toCompletableFuture().get(2, TimeUnit.SECONDS);
            session.send("echo conpty-v5\r\nexit\r\n".getBytes(StandardCharsets.UTF_8))
                    .toCompletableFuture()
                    .get(2, TimeUnit.SECONDS);
            var result = session.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals(0, result.exitCode(), subscriber.text());
            assertTrue(subscriber.text().contains("conpty-v5"));
        }
    }

    private SandboxCommand batch(String source, Duration timeout) {
        return command(List.of(cmd().toString(), "/d", "/s", "/c", source), SandboxMode.BATCH, timeout);
    }

    private SandboxCommand command(List<String> arguments, SandboxMode mode, Duration timeout) {
        String systemRoot = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");
        return new SandboxCommand(
                "windows-sandbox-test",
                arguments,
                temporaryDirectory,
                Map.of("SystemRoot", systemRoot, "ComSpec", cmd().toString()),
                new byte[0],
                mode,
                timeout);
    }

    private PermissionProfile permission(boolean allowPty, boolean allowDelete) {
        return new PermissionProfile(
                "windows-sandbox-test",
                1,
                new FilePermission(List.of(temporaryDirectory), List.of(temporaryDirectory), allowDelete, false),
                new NetworkPermission(Set.of(), Set.of(), true),
                new ProcessPermission(Set.of("cmd.exe"), allowPty, Duration.ofSeconds(10)),
                new ToolPermission(Set.of(), ToolRisk.EXTERNAL_EFFECT, ApprovalRequirement.EVERY_CALL),
                new ResourceLimits(256L * 1024 * 1024, 32 * 1024, 2, 64));
    }

    private static Path cmd() {
        String systemRoot = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");
        return Path.of(systemRoot, "System32", "cmd.exe").toAbsolutePath().normalize();
    }

    private static final class CollectingSubscriber implements Flow.Subscriber<SandboxFrame> {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public synchronized void onNext(SandboxFrame item) {
            output.writeBytes(item.bytes());
        }

        @Override
        public void onError(Throwable throwable) {}

        @Override
        public void onComplete() {}

        private synchronized String text() {
            return output.toString(StandardCharsets.UTF_8);
        }
    }
}
