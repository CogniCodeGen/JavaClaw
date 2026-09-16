package com.javaclaw.server.system;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.builtin.contracts.CodingSystemContracts.Registration;
import com.javaclaw.builtin.contracts.CodingSystemContracts.Registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemCommandCatalogTest {
    @TempDir
    Path temporary;

    @Test
    void 程序只从显式位置发现并验证冻结内容替换() throws Exception {
        Path program = executable("registered", "first");
        SystemCommandCatalog catalog = new SystemCommandCatalog(temporary.resolve("private"));
        var entries = catalog.discover(
                new Registry(1, List.of(new Registration("custom", program.toString(), List.of(), "GBK"))));
        var entry = entries.executables().stream()
                .filter(value -> value.id().equals("custom"))
                .findFirst()
                .orElseThrow();
        assertTrue(entry.available());
        assertEquals(program.toRealPath(), catalog.verify(entry));
        assertEquals("GBK", entry.outputEncoding());
        Files.writeString(program, "changed");
        assertThrows(SecurityException.class, () -> catalog.verify(entry));
        assertFalse(SystemCommandCatalog.systemPath().contains(program.getParent()));
    }

    @Test
    void 相同字节替换文件对象仍拒绝使用旧Turn快照() throws Exception {
        Path program = executable("registered", "same bytes");
        var catalog = new SystemCommandCatalog(temporary.resolve("private"));
        var entry = catalog
                .discover(new Registry(1, List.of(new Registration("custom", program.toString(), List.of(), "UTF-8"))))
                .executables()
                .stream()
                .filter(value -> value.id().equals("custom"))
                .findFirst()
                .orElseThrow();
        Path replacement = executable("replacement", "same bytes");
        Files.move(replacement, program, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        assertThrows(SecurityException.class, () -> catalog.verify(entry));
    }

    @Test
    void 私有程序和缺失入口保持不可用事实而不是隐式扩权() throws Exception {
        Path program = executable("private/registered", "private");
        var catalog = new SystemCommandCatalog(temporary.resolve("private"));
        var entries = catalog.discover(new Registry(
                1,
                List.of(
                        new Registration("private", program.toString(), List.of(), "UTF-8"),
                        new Registration("missing", temporary.resolve("none").toString(), List.of(), "UTF-8"))));
        assertTrue(entries.executables().stream()
                .filter(value -> !value.id().startsWith("system."))
                .noneMatch(value -> value.available()));
    }

    private Path executable(String name, String content) throws Exception {
        Path path = temporary.resolve(name);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        assertTrue(path.toFile().setExecutable(true));
        return path;
    }
}
