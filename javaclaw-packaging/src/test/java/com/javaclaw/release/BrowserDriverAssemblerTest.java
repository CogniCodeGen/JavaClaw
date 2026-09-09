package com.javaclaw.release;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserDriverAssemblerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void 平台仅接受已打包的五种操作系统架构组合() {
        String previousOs = System.getProperty("os.name");
        String previousArch = System.getProperty("os.arch");
        try {
            for (String[] platform : new String[][] {
                {"Mac OS X", "x86_64", "mac"},
                {"Mac OS X", "aarch64", "mac-arm64"},
                {"Linux", "amd64", "linux"},
                {"Linux", "arm64", "linux-arm64"},
                {"Windows 11", "amd64", "win32_x64"}
            }) {
                System.setProperty("os.name", platform[0]);
                System.setProperty("os.arch", platform[1]);
                assertEquals(platform[2], BrowserDriverAssembler.platformDirectory());
            }
            for (String[] platform : new String[][] {
                {"Mac OS X", "ppc64"}, {"Linux", "riscv64"}, {"Windows 11", "aarch64"}, {"Solaris", "amd64"}
            }) {
                System.setProperty("os.name", platform[0]);
                System.setProperty("os.arch", platform[1]);
                assertThrows(IllegalArgumentException.class, BrowserDriverAssembler::platformDirectory);
            }
        } finally {
            restore("os.name", previousOs);
            restore("os.arch", previousArch);
        }
    }

    @Test
    void 仅从锁定归档安装当前平台驱动且保留许可() throws Exception {
        Path image = Files.createDirectories(temporaryDirectory.resolve("complete"));
        BrowserDriverFixture.bundle(image);

        BrowserDriverAssembler.install(image, "1.52.0");

        assertTrue(Files.isRegularFile(image.resolve("driver/package/cli.js")));
        assertTrue(Files.isRegularFile(image.resolve("driver/LICENSE")));
        assertFalse(Files.exists(image.resolve("driver/mac-arm64")));
        BrowserDriverAssembler.verify(image);
    }

    @Test
    void 缺失锁定归档或驱动入口不能形成可执行镜像() throws Exception {
        Path missing = Files.createDirectories(temporaryDirectory.resolve("missing"));
        assertThrows(IOException.class, () -> BrowserDriverAssembler.install(missing, "1.52.0"));

        Path incomplete = Files.createDirectories(temporaryDirectory.resolve("incomplete"));
        BrowserDriverFixture.bundle(incomplete, Map.of("package/cli.js", "cli"));
        assertThrows(IOException.class, () -> BrowserDriverAssembler.install(incomplete, "1.52.0"));
    }

    @Test
    void 归档越界路径和反斜线被拒绝且不写出镜像() throws Exception {
        for (String entry : new String[] {"../../outside", "..\\outside"}) {
            Path image = Files.createTempDirectory(temporaryDirectory, "unsafe-");
            BrowserDriverFixture.bundle(image, Map.of(entry, "blocked"));

            assertThrows(IOException.class, () -> BrowserDriverAssembler.install(image, "1.52.0"));
        }
        assertFalse(Files.exists(temporaryDirectory.resolve("outside")));
    }

    @Test
    void 既有驱动目录不被覆盖也不回退临时解压() throws Exception {
        Path image = Files.createDirectories(temporaryDirectory.resolve("existing"));
        BrowserDriverFixture.bundle(image);
        Files.createDirectory(image.resolve("driver"));

        assertThrows(IOException.class, () -> BrowserDriverAssembler.install(image, "1.52.0"));
    }

    private static void restore(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}
