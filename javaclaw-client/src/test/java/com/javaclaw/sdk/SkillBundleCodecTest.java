package com.javaclaw.sdk;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.sdk.model.JsonDocument;
import com.javaclaw.sdk.model.SkillContentInfo;
import com.javaclaw.sdk.model.SkillInfo;
import com.javaclaw.sdk.model.SkillResourceInfo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillBundleCodecTest {
    @TempDir
    Path temporary;

    @Test
    void roundTripsEditableInstructionsAndResourcesWithoutImportingIdentityOrEnablingScripts() throws Exception {
        var original = new SkillContentInfo(
                new SkillInfo(
                        "existing",
                        "报告",
                        "2.1",
                        new JsonDocument("{\"instructions\":\"OLD_PRIVATE_BODY\",\"custom\":\"kept\"}"),
                        true,
                        8,
                        null,
                        null),
                "先核验来源，再生成报告。\n",
                List.of(
                        new SkillResourceInfo("references/style.md", "text/markdown", "保持原风格\n", false),
                        new SkillResourceInfo("scripts/check.jsh", "text/plain", "System.out.println(42);", true)));
        Path bundle = temporary.resolve("report.zip");
        SkillBundleCodec.write(original, bundle, false);
        var imported = SkillBundleCodec.read(bundle);
        assertEquals(original.instructions(), imported.instructions());
        assertEquals(original.resources(), imported.resources());
        assertEquals("报告", imported.skill().name());
        assertNotEquals("existing", imported.skill().id());
        assertEquals(0, imported.skill().revision());
        assertFalse(imported.skill().enabled());
        assertTrue(imported.skill().manifest().canonicalJson().contains("kept"));
        assertFalse(imported.skill().manifest().canonicalJson().contains("OLD_PRIVATE_BODY"));
        // 导入只返回未保存草稿，脚本标记不等于执行许可，也没有将任何资源解压到用户目录。
        assertFalse(Files.exists(temporary.resolve("scripts")));
        assertThrows(FileAlreadyExistsException.class, () -> SkillBundleCodec.write(original, bundle, false));
        SkillBundleCodec.write(original, bundle, true);
        assertEquals(original.instructions(), SkillBundleCodec.read(bundle).instructions());
    }

    @Test
    void rejectsTraversalMissingResourcesInvalidEncodingAndExpandedZipBombs() throws Exception {
        Path bundle = temporary.resolve("attack.zip");
        for (String path : List.of("../outside", "/outside", "a\\outside", "a/../outside", "a//b", "C:outside")) {
            Files.write(bundle, zip(Map.of("SKILL.md", bytes("safe"), path, bytes("bad"))));
            assertThrows(IllegalArgumentException.class, () -> SkillBundleCodec.read(bundle), path);
        }
        Files.write(bundle, zip(Map.of("SKILL.md", bytes("safe"), "large.txt", new byte[4 * 1024 * 1024])));
        assertThrows(IllegalArgumentException.class, () -> SkillBundleCodec.read(bundle));
        Files.write(bundle, zip(Map.of("SKILL.md", new byte[] {(byte) 0xc3, 0x28})));
        assertThrows(java.io.IOException.class, () -> SkillBundleCodec.read(bundle));
        Files.write(
                bundle,
                zip(Map.of(
                        "SKILL.md", bytes("safe"),
                        "javaclaw-skill.json", bytes("""
                        {"format":"javaclaw.skill.bundle/4","manifest":{"resources":[
                        {"path":"missing.md","content":"","mediaType":"text/markdown","executable":false}]}}
                        """))));
        assertThrows(IllegalArgumentException.class, () -> SkillBundleCodec.read(bundle));
        assertFalse(Files.exists(temporary.resolve("outside")));
    }

    @Test
    void markdownImportPreservesTextAndRequiresExplicitSave() throws Exception {
        Path markdown = temporary.resolve("my-skill.md");
        Files.writeString(markdown, "# 技能\n中文与换行\n");
        var draft = SkillBundleCodec.read(markdown);
        assertEquals("# 技能\n中文与换行\n", draft.instructions());
        assertEquals("my-skill", draft.skill().name());
        assertTrue(draft.resources().isEmpty());
        assertFalse(draft.skill().enabled());
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] zip(Map<String, byte[]> entries) throws Exception {
        var output = new ByteArrayOutputStream();
        try (var archive = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            for (var entry : entries.entrySet()) {
                archive.putNextEntry(new ZipEntry(entry.getKey()));
                archive.write(entry.getValue());
                archive.closeEntry();
            }
        }
        return output.toByteArray();
    }
}
