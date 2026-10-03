package com.javaclaw.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.platform.data.DataRoot;
import com.javaclaw.platform.spring.ApplicationContexts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SkillInstallerPortablePathTest {

    @TempDir Path temporary;

    @Test
    void zipInspectionStagesOnlyBesideManagedSkillsRepository() throws Exception {
        Path zip = temporary.resolve("skill.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("SKILL.md"));
            out.write("---\nname: sample\n---\nSample skill".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
        }
        try (var root = ApplicationContexts.createRoot(new DataRoot(temporary.resolve("data")))) {
            SkillManager skills = new SkillManager(temporary.resolve("data/skills"),
                    root.getBean(ObjectMapper.class), root.getBean(AgentConfig.class));
            assertTrue(new SkillInstaller(skills).inspectZip(zip).ok());
        }
        Path stagingRoot = temporary.resolve("data/tmp");
        assertTrue(Files.isDirectory(stagingRoot));
        try (var entries = Files.list(stagingRoot)) {
            assertTrue(entries.findAny().isEmpty());
        }
    }

    @Test
    void installerRejectsSkillsDirectorySwappedToExternalSymlink() throws Exception {
        Path zip = temporary.resolve("skill.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("SKILL.md"));
            out.write("---\nname: sample\n---\nSample skill".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
        }
        try (var root = ApplicationContexts.createRoot(new DataRoot(temporary.resolve("data")))) {
            SkillManager skills = new SkillManager(temporary.resolve("data/skills"),
                    root.getBean(ObjectMapper.class), root.getBean(AgentConfig.class));
            Path skillsDir = skills.getSkillsDir();
            Path outside = Files.createDirectories(temporary.resolve("outside"));
            Files.delete(skillsDir);
            Files.createSymbolicLink(skillsDir, outside);
            assertFalse(new SkillInstaller(skills).inspectZip(zip).ok());
            try (var entries = Files.list(outside)) {
                assertTrue(entries.findAny().isEmpty());
            }
        }
    }
}
