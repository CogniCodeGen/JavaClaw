package com.javaclaw.nativehost.sandbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SandboxWorkerControlBoundaryTest {
    @TempDir
    Path temporary;

    @Test
    void 控制目录符号链接和控制文件符号链接都不能借用宿主授权() throws Exception {
        Assumptions.assumeTrue(
                temporary.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Path secret = Files.writeString(temporary.resolve("outside"), "protected");
        try (var control = SandboxWorkerControl.create()) {
            Path alias = Files.createSymbolicLink(temporary.resolve("alias"), control.directory());
            assertThrows(IOException.class, () -> SandboxWorkerControl.open(alias));
            Files.delete(control.directory().resolve("control"));
            Files.createSymbolicLink(control.directory().resolve("control"), secret);
            assertThrows(IOException.class, control::state);
        }
        assertEquals("protected", Files.readString(secret));
    }

    @Test
    void 原子写入遇到残留临时文件拒绝覆盖且关闭后禁止续期() throws Exception {
        var control = SandboxWorkerControl.create();
        Path directory = control.directory();
        try {
            assertEquals("", control.status());
            Files.writeString(directory.resolve("control.pending"), "foreign");
            assertThrows(IOException.class, control::touch);
            assertEquals("foreign", Files.readString(directory.resolve("control.pending")));
        } finally {
            control.close();
        }
        control.close();
        assertFalse(Files.exists(directory));
        assertThrows(IOException.class, control::touch);
    }

    @Test
    void 监护参数严格限制租约资源和布尔开关且冻结目标命令() {
        var target = new ArrayList<>(List.of("worker"));
        var valid = new SandboxWorkerMonitorArguments(temporary, Duration.ofMinutes(15), 1024, 1, false, false, target);
        target.clear();
        assertEquals(List.of("worker"), valid.target());
        assertThrows(IllegalArgumentException.class, () -> arguments(Duration.ofMillis(999), 1024, 1, List.of("w")));
        assertThrows(IllegalArgumentException.class, () -> arguments(Duration.ofMinutes(16), 1024, 1, List.of("w")));
        assertThrows(IllegalArgumentException.class, () -> arguments(Duration.ofSeconds(1), 0, 1, List.of("w")));
        assertThrows(IllegalArgumentException.class, () -> arguments(Duration.ofSeconds(1), 1024, 0, List.of("w")));
        assertThrows(IllegalArgumentException.class, () -> arguments(Duration.ofSeconds(1), 1024, 1, List.of()));
        assertThrows(IllegalArgumentException.class, () -> SandboxWorkerMonitorArguments.parse(new String[0]));
        assertThrows(IllegalArgumentException.class, () -> parse("true", "false", "not-separator"));
        assertThrows(IllegalArgumentException.class, () -> parse("TRUE", "false", "--"));
        assertThrows(IllegalArgumentException.class, () -> parse("false", "1", "--"));
        assertEquals(List.of("worker"), parse("true", "false", "--").target());
    }

    private SandboxWorkerMonitorArguments arguments(Duration idle, long memory, int children, List<String> target) {
        return new SandboxWorkerMonitorArguments(temporary, idle, memory, children, true, false, target);
    }

    private SandboxWorkerMonitorArguments parse(String limits, String bootstrap, String separator) {
        return SandboxWorkerMonitorArguments.parse(
                new String[] {temporary.toString(), "1000", "1024", "1", limits, bootstrap, separator, "worker"});
    }
}
