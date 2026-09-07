package com.javaclaw.nativehost.sandbox;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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

/** 真实验证 JDK/Node 启动需要的有限描述符控制，不允许扩大为任意 fcntl。 */
@EnabledOnOs(OS.MAC)
class MacRuntimeFcntlTest {
    @TempDir
    Path temporary;

    @Test
    void kernelAllowsDescriptorFlagsAndBlockingAndNonblockingFileLocks() throws Exception {
        var runtime = SandboxJavaRuntime.forWorker(Probe.class);
        var permission = new PermissionProfile(
                "fcntl-kernel-test",
                1,
                new FilePermission(List.of(temporary), List.of(temporary), true, false),
                new NetworkPermission(Set.of(), Set.of(), true),
                new ProcessPermission(
                        Set.of(runtime.executable().getFileName().toString()), false, Duration.ofSeconds(8)),
                new ToolPermission(Set.of(), ToolRisk.PROCESS, ApprovalRequirement.NONE),
                new ResourceLimits(512L * 1024 * 1024, 4096, 4, 128));
        var command = new SandboxCommand(
                "fcntl-probe",
                runtime.command(Probe.class, List.of(), 32),
                temporary,
                Map.of(),
                new byte[0],
                SandboxMode.BATCH,
                Duration.ofSeconds(8));
        var result = new PlatformSandboxExecutor()
                .execute(
                        command,
                        permission,
                        new CancellationSource(),
                        new SandboxRuntimeAccess(runtime.readRoots(), List.of(), List.of(runtime.executable())),
                        SandboxNetworkAccess.offline());
        assertEquals(0, result.exitCode(), new String(result.standardError(), StandardCharsets.UTF_8));
        assertEquals("fcntl-and-locks-allowed\n", new String(result.standardOutput(), StandardCharsets.UTF_8));
    }

    /** 仅在测试子 JVM 内操作自有 stdout 和权限根内的普通文件。 */
    public static final class Probe {
        private Probe() {}

        /** 分别触发 Node 所用的 FD 模式以及 Gradle/JDK 所用的 POSIX 锁。 */
        public static void main(String[] arguments) throws Throwable {
            var linker = Linker.nativeLinker();
            var fcntl = linker.downcallHandle(
                    linker.defaultLookup().find("fcntl").orElseThrow(),
                    FunctionDescriptor.of(
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
                    Linker.Option.firstVariadicArg(2));
            int flags = (int) fcntl.invokeExact(1, 3, 0);
            if (flags < 0 || (int) fcntl.invokeExact(1, 4, flags) < 0) {
                throw new IllegalStateException("descriptor flags are denied");
            }
            try (FileChannel channel = FileChannel.open(
                    Path.of("lock"), StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                try (FileLock lock = channel.lock()) {
                    if (!lock.isValid()) {
                        throw new IllegalStateException("blocking lock is unavailable");
                    }
                }
                try (FileLock lock = channel.tryLock()) {
                    if (lock == null || !lock.isValid()) {
                        throw new IllegalStateException("nonblocking lock is unavailable");
                    }
                }
            }
            System.out.println("fcntl-and-locks-allowed");
        }
    }
}
