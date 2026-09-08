package com.javaclaw.desktop.web;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.desktop.FxTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSurfaceRuntimeTest {
    // 失败时保留子 JVM 输出与原生错误文件，避免清理先于诊断而丢失退出原因。
    @TempDir(cleanup = CleanupMode.ON_SUCCESS)
    Path directory;

    @Test
    void 同进程复用目录且结束只清理精确owner目录() throws Exception {
        WebSurfaceRuntime.close();
        Path profile = WebSurfaceRuntime.directory();
        assertEquals(profile, WebSurfaceRuntime.directory());
        Path nested = Files.createDirectories(profile.resolve("test-cache"));
        Files.writeString(nested.resolve("owned.txt"), "owned");
        Path outside = directory.resolve("untouched.txt");
        Files.writeString(outside, "untouched");
        WebSurfaceRuntime.close();
        WebSurfaceRuntime.close();
        assertTrue(Files.notExists(profile));
        assertEquals("untouched", Files.readString(outside));
        Path next = WebSurfaceRuntime.directory();
        assertNotEquals(profile, next);
        WebSurfaceRuntime.close();
    }

    @Test
    void 两个独立JavaFX进程同时确认显示且不争用默认全局WebKit目录() throws Exception {
        Process first = start("first");
        Process second = null;
        try {
            awaitReady("first", first);
            second = start("second");
            awaitReady("second", second);
            Path firstProfile = Path.of(Files.readString(directory.resolve("first.ready")));
            Path secondProfile = Path.of(Files.readString(directory.resolve("second.ready")));
            assertNotEquals(firstProfile, secondProfile);
            assertTrue(firstProfile.getFileName().toString().startsWith("javaclaw-webview-"));
            assertTrue(secondProfile.getFileName().toString().startsWith("javaclaw-webview-"));
            assertTrue(first.isAlive(), () -> diagnostic("first", first));
            Files.writeString(directory.resolve("first.release"), "release");
            Files.writeString(directory.resolve("second.release"), "release");
            assertTrue(first.waitFor(10, TimeUnit.SECONDS));
            assertTrue(second.waitFor(10, TimeUnit.SECONDS));
            assertEquals(0, first.exitValue(), () -> diagnostic("first", first));
            Process other = second;
            assertEquals(0, second.exitValue(), () -> diagnostic("second", other));
        } catch (Exception | AssertionError failure) {
            System.err.println(diagnostic("first", first));
            if (second != null) {
                System.err.println(diagnostic("second", second));
            }
            throw failure;
        } finally {
            first.destroyForcibly();
            if (second != null) {
                second.destroyForcibly();
            }
        }
    }

    private Process start(String name) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return new ProcessBuilder(
                        java,
                        "--enable-native-access=ALL-UNNAMED",
                        "-XX:ErrorFile=" + directory.resolve(name + "-hs_err_pid%p.log"),
                        "-cp",
                        System.getProperty("java.class.path"),
                        WebProfileIsolationProbe.class.getName(),
                        directory.resolve(name + ".ready").toString(),
                        directory.resolve(name + ".release").toString())
                .redirectErrorStream(true)
                .redirectOutput(directory.resolve(name + ".log").toFile())
                .start();
    }

    private void awaitReady(String name, Process process) {
        FxTestSupport.await(() -> {
            assertTrue(process.isAlive(), () -> diagnostic(name, process));
            return Files.exists(directory.resolve(name + ".ready"));
        });
    }

    private String diagnostic(String name, Process process) {
        Path log = directory.resolve(name + ".log");
        String output;
        try {
            output = Files.exists(log) ? Files.readString(log) : "尚无子进程日志";
        } catch (Exception failure) {
            output = "读取子进程日志失败：" + failure;
        }
        return "WebKit 探针 " + name + "，pid=" + process.pid() + "，状态="
                + (process.isAlive() ? "alive" : "exit=" + process.exitValue())
                + "，ready=" + Files.exists(directory.resolve(name + ".ready"))
                + "，release=" + Files.exists(directory.resolve(name + ".release"))
                + "，保留诊断目录=" + directory + "\n" + output;
    }
}
