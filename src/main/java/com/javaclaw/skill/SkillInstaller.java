package com.javaclaw.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 技能安装器 — 将外部技能目录或 zip 包导入 {@code skills/}
 *
 * <p>安装流程：</p>
 * <ol>
 *   <li>校验目录/zip 顶层包含 {@code SKILL.md}</li>
 *   <li>扫描 {@code scripts/} 列出所有可执行/脚本文件，返回给调用者确认</li>
 *   <li>用户确认后复制到 skills/{name}/，并调用 {@link SkillManager#reload()}</li>
 * </ol>
 */
public final class SkillInstaller {

    private static final Logger log = LoggerFactory.getLogger(SkillInstaller.class);

    /**
     * 安装结果
     */
    public record InstallResult(boolean ok, String message, Path installedDir,
                                List<String> detectedScripts) {}

    private final SkillManager skills;

    public SkillInstaller(SkillManager skills) {
        this.skills = java.util.Objects.requireNonNull(skills, "skills");
    }

    /**
     * 从本地目录安装技能
     */
    public InstallResult installFromDirectory(Path sourceDir, String desiredName) {
        if (sourceDir == null || !Files.isDirectory(sourceDir)) {
            return new InstallResult(false, "源目录不存在或不是目录", null, List.of());
        }
        Path skillFile = sourceDir.resolve(Skill.SKILL_FILE);
        if (!Files.exists(skillFile)) {
            return new InstallResult(false, "源目录缺少 SKILL.md", null, List.of());
        }
        String name = (desiredName == null || desiredName.isBlank())
                ? sourceDir.getFileName().toString() : desiredName;
        if (!name.matches("[a-zA-Z0-9_\\-]+")) {
            return new InstallResult(false, "技能名只允许字母、数字、下划线、连字符", null, List.of());
        }

        Path target = skills.getSkillsDir().resolve(name);
        if (Files.exists(target)) {
            return new InstallResult(false, "目标技能目录已存在，请先删除或改名", null, List.of());
        }

        try {
            List<String> scripts = listScripts(sourceDir);
            copyRecursively(sourceDir, target);
            skills.reload();
            log.info("技能安装完成: {} → {}", sourceDir, target);
            return new InstallResult(true, "安装成功", target, scripts);
        } catch (IOException e) {
            deleteRecursively(target);
            log.error("安装技能失败", e);
            return new InstallResult(false, "安装失败: " + e.getMessage(), null, List.of());
        }
    }

    /**
     * 从 zip 包安装技能（zip 顶层包含 SKILL.md 或一个包裹目录）
     */
    public InstallResult installFromZip(Path zipFile, String desiredName) {
        if (zipFile == null || !Files.isRegularFile(zipFile)) {
            return new InstallResult(false, "zip 文件不存在", null, List.of());
        }
        Path tmp = null;
        try {
            tmp = Files.createTempDirectory("javaclaw-skill-");
            unzip(zipFile, tmp);
            // 查找 SKILL.md 所在目录（可能是 zip 根或第一层子目录）
            Path skillRoot = findSkillRoot(tmp);
            if (skillRoot == null) {
                return new InstallResult(false, "zip 中未找到 SKILL.md", null, List.of());
            }
            return installFromDirectory(skillRoot, desiredName);
        } catch (IOException e) {
            return new InstallResult(false, "解压失败: " + e.getMessage(), null, List.of());
        } finally {
            if (tmp != null) deleteRecursively(tmp);
        }
    }

    /** 校验目录导入源并返回脚本清单，不修改技能目录。 */
    public InspectionResult inspectDirectory(Path sourceDir) {
        if (sourceDir == null || !Files.isDirectory(sourceDir)) {
            return new InspectionResult(false, "源目录不存在或不是目录", List.of());
        }
        if (!Files.isRegularFile(sourceDir.resolve(Skill.SKILL_FILE))) {
            return new InspectionResult(false, "源目录缺少 SKILL.md", List.of());
        }
        return new InspectionResult(true, "", listScripts(sourceDir));
    }

    /** 校验 zip 导入源并返回其中的脚本清单，不修改技能目录。 */
    public InspectionResult inspectZip(Path zipFile) {
        if (zipFile == null || !Files.isRegularFile(zipFile)) {
            return new InspectionResult(false, "zip 文件不存在", List.of());
        }
        Path tmp = null;
        try {
            tmp = Files.createTempDirectory("javaclaw-skill-inspect-");
            unzip(zipFile, tmp);
            Path skillRoot = findSkillRoot(tmp);
            return skillRoot == null
                    ? new InspectionResult(false, "zip 中未找到 SKILL.md", List.of())
                    : new InspectionResult(true, "", listScripts(skillRoot));
        } catch (IOException failure) {
            return new InspectionResult(false, "检查 zip 失败: " + failure.getMessage(), List.of());
        } finally {
            if (tmp != null) deleteRecursively(tmp);
        }
    }

    public record InspectionResult(boolean ok, String message, List<String> scripts) {
        public InspectionResult {
            message = message == null ? "" : message;
            scripts = List.copyOf(scripts == null ? List.of() : scripts);
        }
    }

    /** 列出 scripts/ 目录下的脚本文件（供 UI 弹窗展示以确认） */
    public static List<String> listScripts(Path sourceDir) {
        Path scripts = sourceDir.resolve("scripts");
        if (!Files.isDirectory(scripts)) return List.of();
        List<String> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(scripts)) {
            walk.filter(Files::isRegularFile)
                    .forEach(p -> out.add(scripts.relativize(p).toString()));
        } catch (IOException failure) {
            log.debug("扫描技能脚本失败: {}", scripts, failure);
        }
        return out;
    }

    private static void copyRecursively(Path from, Path to) throws IOException {
        try (Stream<Path> walk = Files.walk(from)) {
            Iterable<Path> paths = walk.sorted(Comparator.naturalOrder())::iterator;
            for (Path path : paths) {
                Path destination = to.resolve(from.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static Path findSkillRoot(Path dir) throws IOException {
        if (Files.exists(dir.resolve(Skill.SKILL_FILE))) return dir;
        try (Stream<Path> children = Files.list(dir)) {
            for (Path c : (Iterable<Path>) children::iterator) {
                if (Files.isDirectory(c) && Files.exists(c.resolve(Skill.SKILL_FILE))) return c;
            }
        }
        return null;
    }

    private static void unzip(Path zipFile, Path target) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path resolved = target.resolve(entry.getName()).normalize();
                if (!resolved.startsWith(target)) {
                    throw new IOException("zip 条目越界: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(resolved);
                } else {
                    Files.createDirectories(resolved.getParent());
                    Files.copy(zis, resolved, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteRecursively(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException failure) {
                    log.debug("清理技能导入临时文件失败: {}", path, failure);
                }
            });
        } catch (IOException failure) {
            log.debug("清理技能导入临时目录失败: {}", root, failure);
        }
    }
}
