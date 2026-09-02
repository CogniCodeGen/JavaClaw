package com.javaclaw.release;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseManifestMainTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void 相同目录重复生成时清单字节保持稳定() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("release"));
        Files.writeString(root.resolve("z-last.txt"), "最后", StandardCharsets.UTF_8);
        Path nested = Files.createDirectories(root.resolve("nested"));
        Files.writeString(nested.resolve("first.txt"), "first", StandardCharsets.UTF_8);
        Path manifest = root.resolve("RELEASE-MANIFEST.json");

        ReleaseManifestMain.create(root, manifest);
        byte[] first = Files.readAllBytes(manifest);
        ReleaseManifestMain.create(root, manifest);

        assertEquals(new String(first, StandardCharsets.UTF_8), Files.readString(manifest));
        assertTrue(Files.readString(manifest).indexOf("nested/first.txt")
                < Files.readString(manifest).indexOf("z-last.txt"));
        ReleaseManifestMain.verify(root, manifest);
    }

    @Test
    void 文件被篡改后校验必须失败() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("release"));
        Path artifact = Files.writeString(root.resolve("artifact.zip"), "original", StandardCharsets.UTF_8);
        Path manifest = root.resolve("RELEASE-MANIFEST.json");
        ReleaseManifestMain.create(root, manifest);

        Files.writeString(artifact, "tampered", StandardCharsets.UTF_8);

        assertThrows(IllegalStateException.class, () -> ReleaseManifestMain.verify(root, manifest));
    }

    @Test
    void 清单路径位于发行目录外时拒绝写入() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("release"));
        Path outside = temporaryDirectory.resolve("outside.json");

        assertThrows(IllegalArgumentException.class, () -> ReleaseManifestMain.create(root, outside));
    }

    @Test
    void 命令行入口只接受完整的创建和校验命令() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("command-release"));
        Files.writeString(root.resolve("artifact.txt"), "artifact");
        Path manifest = root.resolve("manifest.json");

        ReleaseManifestMain.main(new String[] {"create", root.toString(), manifest.toString()});
        ReleaseManifestMain.main(new String[] {"verify", root.toString(), manifest.toString()});

        assertTrue(Files.isRegularFile(manifest));
        assertThrows(IllegalArgumentException.class, () -> ReleaseManifestMain.main(new String[0]));
        assertThrows(
                IllegalArgumentException.class,
                () -> ReleaseManifestMain.main(new String[] {"publish", root.toString(), manifest.toString()}));
    }

    @Test
    void 不存在的根目录和清单以及把根目录当作清单都被拒绝() throws Exception {
        Path missingRoot = temporaryDirectory.resolve("missing");
        Path root = Files.createDirectories(temporaryDirectory.resolve("validation-release"));

        assertThrows(
                IllegalArgumentException.class,
                () -> ReleaseManifestMain.create(missingRoot, missingRoot.resolve("manifest.json")));
        assertThrows(IllegalArgumentException.class, () -> ReleaseManifestMain.create(root, root));
        assertThrows(
                IllegalStateException.class,
                () -> ReleaseManifestMain.verify(root, root.resolve("missing-manifest.json")));
    }

    @Test
    void 文件名中的Json控制字符全部按规范转义() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("escaped-release"));
        String fileName = "quote\"\\\b\f\n\r\t\u0001.txt";
        Files.writeString(root.resolve(fileName), "escaped");
        Path manifest = root.resolve("manifest.json");

        ReleaseManifestMain.create(root, manifest);

        String json = Files.readString(manifest);
        assertAll(
                () -> assertTrue(json.contains("\\\"")),
                () -> assertTrue(json.contains("\\\\")),
                () -> assertTrue(json.contains("\\b")),
                () -> assertTrue(json.contains("\\f")),
                () -> assertTrue(json.contains("\\n")),
                () -> assertTrue(json.contains("\\r")),
                () -> assertTrue(json.contains("\\t")),
                () -> assertTrue(json.contains("\\u0001")));
    }

    @Test
    void 符号链接只记录目标文本而不跟随目标内容() throws Exception {
        Assumptions.assumeFalse(isWindows());
        Path root = Files.createDirectories(temporaryDirectory.resolve("linked-release"));
        Files.writeString(root.resolve("target.txt"), "target");
        Files.createSymbolicLink(root.resolve("alias.txt"), Path.of("target.txt"));
        Path manifest = root.resolve("manifest.json");

        ReleaseManifestMain.create(root, manifest);

        String json = Files.readString(manifest);
        assertTrue(json.contains("\"path\": \"alias.txt\", \"type\": \"symlink\""));
        ReleaseManifestMain.verify(root, manifest);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
    }
}
