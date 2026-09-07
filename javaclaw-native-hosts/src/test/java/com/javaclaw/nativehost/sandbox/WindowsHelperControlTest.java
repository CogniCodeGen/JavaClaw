package com.javaclaw.nativehost.sandbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.nativehost.ffm.WindowsSandbox;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowsHelperControlTest {
    @TempDir
    Path directory;

    @Test
    void 用户正常退出73由可信完成记录识别而不触发恢复故障() throws Exception {
        WindowsHelperControl.completed(directory, 73);
        assertDoesNotThrow(() -> WindowsHelperControl.verify(directory));
        assertTrue(SandboxIsolationFailure.evidence(new IOException("process exit 73; restoration failed"))
                .isEmpty());
    }

    @Test
    void 用户输出不能伪造恢复证明且缺记录保留证据目录() throws Exception {
        Files.writeString(directory.resolve("stdout"), "JAVACLAW-SANDBOX-V1\nCOMPLETED\n0\n");
        var failure = assertThrows(
                WindowsSandbox.AclRestorationException.class, () -> WindowsHelperControl.verify(directory));
        assertEquals(Optional.of(directory), failure.evidenceDirectory());
        assertTrue(Files.isDirectory(directory));
        WindowsHelperControl.failed(directory, true);
        assertThrows(WindowsSandbox.AclRestorationException.class, () -> WindowsHelperControl.verify(directory));
    }

    @Test
    void 格式错误和超大控制记录不能被当作完成() throws Exception {
        Files.writeString(directory.resolve(WindowsHelperControl.RESULT), "COMPLETED 0");
        assertThrows(WindowsSandbox.AclRestorationException.class, () -> WindowsHelperControl.verify(directory));
        Files.writeString(directory.resolve(WindowsHelperControl.RESULT), "x".repeat(129));
        assertThrows(WindowsSandbox.AclRestorationException.class, () -> WindowsHelperControl.verify(directory));
    }

    @Test
    void 聚合清理异常保留可信证据并在异常环路中终止() {
        var first = new IOException("outer");
        var second = new IOException("inner", first);
        first.initCause(second);
        assertTrue(SandboxIsolationFailure.evidence(first).isEmpty());
        second.addSuppressed(
                new WindowsSandbox.AclRestorationException("native cleanup", null, Optional.of(directory)));
        assertEquals(Optional.of(List.of(directory)), SandboxIsolationFailure.evidence(first));
        assertTrue(WindowsHelperControl.restorationFailure(first));
    }

    @Test
    void 可信执行失败记录证明未遗留权限而非根据退出码猜测() throws Exception {
        WindowsHelperControl.failed(directory, false);
        assertDoesNotThrow(() -> WindowsHelperControl.verify(directory));
    }

    @Test
    void 废弃跨进程租约的旧基线目录通过独立控制记录保留关联() throws Exception {
        Path previous = directory.resolve("previous-helper");
        WindowsHelperControl.failed(
                directory, new WindowsSandbox.AclRestorationException("abandoned lease", null, Optional.of(previous)));
        var failure = assertThrows(
                WindowsSandbox.AclRestorationException.class, () -> WindowsHelperControl.verify(directory));
        assertEquals(Optional.of(List.of(directory, previous)), SandboxIsolationFailure.evidence(failure));
    }
}
