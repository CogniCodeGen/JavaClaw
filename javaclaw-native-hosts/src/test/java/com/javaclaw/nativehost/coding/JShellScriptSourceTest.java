package com.javaclaw.nativehost.coding;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JShellScriptSourceTest {
    @TempDir
    Path temporary;

    @Test
    void 发行源码在摘要目录发布且重复读取不产生第二个版本() throws Exception {
        Path data = Files.createDirectory(temporary.resolve("data-v6"));
        var first = JShellScriptSource.materialize(data);
        assertEquals(first, JShellScriptSource.materialize(data));
        assertEquals(first.sha256(), first.path().getParent().getFileName().toString());
        assertTrue(Files.readString(first.path()).contains("LocalExecutionControlProvider"));
        if (Files.getFileStore(first.path()).supportsFileAttributeView("posix")) {
            assertFalse(Files.getPosixFilePermissions(first.path())
                    .contains(java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        }
    }

    @Test
    void 已发布源码被篡改时拒绝而不覆盖原始证据() throws Exception {
        Path data = Files.createDirectory(temporary.resolve("data-v6"));
        var source = JShellScriptSource.materialize(data);
        if (Files.getFileStore(source.path()).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(source.path(), PosixFilePermissions.fromString("rw-------"));
        } else {
            Files.setAttribute(source.path(), "dos:readonly", false);
        }
        Files.writeString(source.path(), "tampered");
        assertThrows(IOException.class, () -> JShellScriptSource.materialize(data));
        assertEquals("tampered", Files.readString(source.path()));
    }

    @Test
    void 管理祖先不是目录时不发布到其他位置() throws Exception {
        Path data = Files.createDirectory(temporary.resolve("data-v6"));
        Files.writeString(data.resolve("coding"), "not a directory");
        assertThrows(IOException.class, () -> JShellScriptSource.materialize(data));
        assertThrows(IllegalArgumentException.class, () -> JShellScriptSource.materialize(Path.of("relative")));
    }

    @Test
    void 同长度源码篡改仍由完整摘要拒绝() throws Exception {
        Path data = Files.createDirectory(temporary.resolve("data-v6"));
        var source = JShellScriptSource.materialize(data);
        byte[] changed = Files.readAllBytes(source.path());
        changed[0] ^= 1;
        writable(source.path());
        Files.write(source.path(), changed);
        assertThrows(IOException.class, () -> JShellScriptSource.materialize(data));
        assertEquals(changed[0], Files.readAllBytes(source.path())[0]);
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void 管理祖先和最终源码的符号链接都不能重定向发布() throws Exception {
        Path ancestorData = Files.createDirectory(temporary.resolve("ancestor-data"));
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Files.createSymbolicLink(ancestorData.resolve("coding"), outside);
        assertThrows(IOException.class, () -> JShellScriptSource.materialize(ancestorData));
        try (var files = Files.list(outside)) {
            assertEquals(0, files.count());
        }
        Path data = Files.createDirectory(temporary.resolve("data-v6"));
        var source = JShellScriptSource.materialize(data);
        Path replacement = Files.copy(source.path(), outside.resolve("replacement.java"));
        Files.delete(source.path());
        Files.createSymbolicLink(source.path(), replacement);
        assertThrows(IOException.class, () -> JShellScriptSource.materialize(data));
        assertTrue(Files.isSymbolicLink(source.path()));
        assertEquals(replacement, Files.readSymbolicLink(source.path()));
    }

    @Test
    void 缺失空文件和超限发行资源均在发布目录创建前拒绝() throws Exception {
        for (byte[] packaged : new byte[][] {null, new byte[0], new byte[128 * 1024 + 1]}) {
            Path data = Files.createTempDirectory(temporary, "invalid-package-");
            URL classes = JShellScriptSource.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation();
            // 独立加载同一发行类，只替换资源供应；不为测试在生产入口添加可注入源码参数。
            try (var loader = new URLClassLoader(new URL[] {classes}, ClassLoader.getPlatformClassLoader()) {
                @Override
                public InputStream getResourceAsStream(String name) {
                    if (name.equals("coding/jshell/JShellScriptWorker.java")) {
                        return packaged == null ? null : new ByteArrayInputStream(packaged);
                    }
                    return super.getResourceAsStream(name);
                }
            }) {
                var isolated = Class.forName(JShellScriptSource.class.getName(), true, loader);
                var materialize = isolated.getMethod("materialize", Path.class);
                var failure = assertThrows(InvocationTargetException.class, () -> materialize.invoke(null, data));
                assertInstanceOf(IOException.class, failure.getCause());
                assertFalse(Files.exists(data.resolve("coding")));
            }
        }
    }

    private static void writable(Path source) throws Exception {
        if (Files.getFileStore(source).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(source, PosixFilePermissions.fromString("rw-------"));
        } else {
            Files.setAttribute(source, "dos:readonly", false);
        }
    }
}
