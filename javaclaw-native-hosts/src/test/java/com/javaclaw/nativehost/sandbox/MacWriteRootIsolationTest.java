package com.javaclaw.nativehost.sandbox;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
import com.javaclaw.api.SandboxMode;
import com.javaclaw.api.ToolPermission;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.nativehost.network.SandboxNetworkAccess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs(OS.MAC)
class MacWriteRootIsolationTest {
    @TempDir
    Path temporary;

    @Test
    void kernelAllowsDeletingChildrenButKeepsTheWriteRootAnchored() throws Exception {
        Path cache = Files.createDirectory(temporary.resolve("cache")).toRealPath();
        var runtime = SandboxJavaRuntime.current();
        var permission = new PermissionProfile(
                "root-kernel-test",
                1,
                new FilePermission(List.of(cache), List.of(cache), true, false),
                new NetworkPermission(Set.of(), Set.of(), true),
                new ProcessPermission(
                        Set.of(runtime.executable().getFileName().toString()), false, Duration.ofSeconds(8)),
                new ToolPermission(Set.of(), ToolRisk.PROCESS, ApprovalRequirement.NONE),
                new ResourceLimits(512L * 1024 * 1024, 4096, 4, 128));
        var command = new SandboxCommand(
                "root-probe",
                runtime.command(Probe.class, List.of(cache.toString()), 32),
                cache,
                Map.of(),
                new byte[0],
                SandboxMode.BATCH,
                Duration.ofSeconds(8));
        var access = new SandboxRuntimeAccess(runtime.readRoots(), List.of(), List.of(runtime.executable()));
        var result = new PlatformSandboxExecutor()
                .execute(command, permission, new CancellationSource(), access, SandboxNetworkAccess.offline());
        assertEquals(0, result.exitCode(), new String(result.standardError(), StandardCharsets.UTF_8));
        assertEquals("child-deleted\nroot-protected\n", new String(result.standardOutput(), StandardCharsets.UTF_8));
        assertTrue(Files.isDirectory(cache));
    }

    /** 在真实 Seatbelt 进程内区分子文件删除权与授权根的不可替换性。 */
    public static final class Probe {
        private Probe() {}

        /** 参数是测试专用的已存在写根；不调用 shell。 */
        public static void main(String[] arguments) throws IOException {
            Path root = Path.of(arguments[0]);
            Path child = Files.writeString(root.resolve("child"), "temporary");
            Files.delete(child);
            System.out.println("child-deleted");
            try {
                Files.delete(root);
                System.out.println("root-deleted");
            } catch (IOException denied) {
                System.out.println("root-protected");
            }
        }
    }
}
