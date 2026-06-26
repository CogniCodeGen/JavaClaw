package com.javaclaw.task.sdd.spec;

import java.nio.file.Path;
import java.util.Locale;

/**
 * OpenSpec 产物的路径约定 —— 与 {@code skills/spec-driven-development/SKILL.md} 的目录布局对齐。
 *
 * <pre>
 * {workDir}/.agent/openspec/
 * ├── project.md                          # 项目级长期约定（可选、跨变更复用）
 * ├── changes/{slug}/                      # 一次变更一个目录
 * │   ├── proposal.md
 * │   ├── design.md
 * │   ├── tasks.md
 * │   └── specs/{能力}/spec.md
 * └── specs/{能力}/spec.md                 # 已归档的"当前真相"
 * </pre>
 *
 * <p>纯路径计算，不触盘、不抛异常。{@code workDir} 为空时返回 {@code null}，调用方据此跳过。</p>
 *
 * @author JavaClaw
 */
public final class SpecPaths {

    public static final String AGENT_DIR = ".agent";
    public static final String OPENSPEC_DIR = AGENT_DIR + "/openspec";
    public static final String CHANGES_DIR = "changes";
    public static final String SPECS_DIR = "specs";

    public static final String PROPOSAL_FILE = "proposal.md";
    public static final String DESIGN_FILE = "design.md";
    public static final String TASKS_FILE = "tasks.md";
    public static final String SPEC_FILE = "spec.md";
    public static final String PROJECT_FILE = "project.md";

    private SpecPaths() {}

    /** {workDir}/.agent/openspec 根；workDir 空返回 null。 */
    public static Path openspecRoot(String workDir) {
        if (workDir == null || workDir.isBlank()) return null;
        try {
            return Path.of(workDir).toAbsolutePath().resolve(OPENSPEC_DIR);
        } catch (Exception e) {
            return null;
        }
    }

    /** changes/ 根；workDir 空返回 null。 */
    public static Path changesRoot(String workDir) {
        Path root = openspecRoot(workDir);
        return root == null ? null : root.resolve(CHANGES_DIR);
    }

    /** 某变更目录 changes/{slug}/；workDir 空返回 null。 */
    public static Path changeDir(String workDir, String slug) {
        Path root = changesRoot(workDir);
        return root == null ? null : root.resolve(slug);
    }

    public static Path proposalFile(String workDir, String slug) {
        Path d = changeDir(workDir, slug);
        return d == null ? null : d.resolve(PROPOSAL_FILE);
    }

    public static Path designFile(String workDir, String slug) {
        Path d = changeDir(workDir, slug);
        return d == null ? null : d.resolve(DESIGN_FILE);
    }

    public static Path tasksFile(String workDir, String slug) {
        Path d = changeDir(workDir, slug);
        return d == null ? null : d.resolve(TASKS_FILE);
    }

    /** 变更内某能力的规格 changes/{slug}/specs/{能力}/spec.md。 */
    public static Path changeSpecFile(String workDir, String slug, String capability) {
        Path d = changeDir(workDir, slug);
        return d == null ? null : d.resolve(SPECS_DIR).resolve(capability).resolve(SPEC_FILE);
    }

    /** 归档后的"当前真相"规格 specs/{能力}/spec.md。 */
    public static Path archivedSpecFile(String workDir, String capability) {
        Path root = openspecRoot(workDir);
        return root == null ? null : root.resolve(SPECS_DIR).resolve(capability).resolve(SPEC_FILE);
    }

    /** 提示词里引用的相对路径（如告诉执行体去哪读规格）。 */
    public static String relativeChangeHint(String slug) {
        return "./" + OPENSPEC_DIR + "/" + CHANGES_DIR + "/" + slug;
    }

    /**
     * 由任务 id + 标题生成 change 目录 slug：{@code {前8位id}-{标题slug}}。
     * 保留中文字符，替换文件系统非法字符为短横线，限长 48。
     */
    public static String makeSlug(String taskId, String title) {
        String shortId = (taskId == null || taskId.isBlank())
                ? "task" : taskId.substring(0, Math.min(8, taskId.length()));
        String t = title == null ? "" : title.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s/\\\\:*?\"<>|]+", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^-|-$", "");
        if (t.length() > 40) t = t.substring(0, 40);
        return t.isEmpty() ? shortId : shortId + "-" + t;
    }
}
