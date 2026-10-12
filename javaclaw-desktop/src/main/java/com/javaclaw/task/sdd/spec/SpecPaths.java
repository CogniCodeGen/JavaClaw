package com.javaclaw.task.sdd.spec;

import java.util.Locale;

/**
 * OpenSpec 文档名与提示路径约定。
 *
 * <p>SDD 文档存入 H2 {@code sdd_spec_docs} 表。</p>
 *
 * @author JavaClaw
 */
public final class SpecPaths {

    public static final String SPECS_DIR = "specs";

    public static final String PROPOSAL_FILE = "proposal.md";
    public static final String DESIGN_FILE = "design.md";
    public static final String TASKS_FILE = "tasks.md";
    public static final String SPEC_FILE = "spec.md";
    private SpecPaths() {}

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
