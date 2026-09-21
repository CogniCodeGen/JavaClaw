package com.javaclaw.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.client.jpms.ProviderConfigurationModuleProbe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderConfigurationModulePathTest {
    @TempDir
    Path temporary;

    @Test
    void 命名模块准备保留和替换凭据时摘要兼容且清零秘密() throws Exception {
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        String probeClasses = Path.of(ProviderConfigurationModuleProbe.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI())
                .toString();
        Path log = temporary.resolve("provider-module-probe.log");
        // 普通 Surefire 使用 classpath；独立 JVM 才能覆盖 Desktop 的命名模块反射边界。
        Process process = new ProcessBuilder(
                        javaExecutable(),
                        "--module-path",
                        classpath,
                        "--add-modules",
                        "com.javaclaw.client",
                        "--class-path",
                        probeClasses,
                        ProviderConfigurationModuleProbe.class.getName())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "命名模块配置探针应在 30 秒内完成：" + Files.readString(log));
            assertEquals(0, process.exitValue(), Files.readString(log));
        } finally {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
    }

    private static String javaExecutable() {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }
}
