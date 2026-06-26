package com.javaclaw.agent.persona;

import com.javaclaw.prompt.MemoryPrompts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 工作区上下文文件管理器（借鉴 Harness Workspace 设计）
 *
 * <p>每个工作区一份：</p>
 * <ul>
 *   <li><b>AGENTS.md</b> —— 人工编写的 Agent 人格 / 行为约定，每轮注入 system prompt</li>
 *   <li><b>MEMORY.md</b> —— LLM 自动维护的长期记忆（由后台合并器从日流水汇总）</li>
 *   <li><b>memory/YYYY-MM-DD.md</b> —— 日流水账，每次对话结束后追加事实条目</li>
 * </ul>
 *
 * <p>对外暴露纯文件 I/O 操作；prompt 注入格式 / 触发时机由 ChatService 控制。
 * 文件 I/O 是同步且廉价的（小文件）；高频的 readAgentsMd 也可放心每轮调用。</p>
 *
 * @author JavaClaw
 */
public class WorkspaceContextFiles {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceContextFiles.class);

    public static final String AGENTS_MD = "AGENTS.md";
    public static final String MEMORY_MD = "MEMORY.md";
    public static final String MEMORY_DIR = "memory";

    private final Path workspacePath;

    public WorkspaceContextFiles(Path workspacePath) {
        this.workspacePath = workspacePath;
    }

    public Path getAgentsMdPath() {
        return workspacePath.resolve(AGENTS_MD);
    }

    public Path getMemoryMdPath() {
        return workspacePath.resolve(MEMORY_MD);
    }

    public Path getMemoryDir() {
        return workspacePath.resolve(MEMORY_DIR);
    }

    public Path getDailyMemoryFile(LocalDate date) {
        return getMemoryDir().resolve(date.toString() + ".md");
    }

    // ==================== AGENTS.md ====================

    /**
     * 读取 AGENTS.md，返回 UTF-8 全文；文件缺失时返回空字符串。
     */
    public String readAgentsMd() {
        Path p = getAgentsMdPath();
        if (!Files.exists(p)) return "";
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("读取 AGENTS.md 失败: {}", e.getMessage());
            return "";
        }
    }

    public void writeAgentsMd(String content) {
        try {
            Files.createDirectories(workspacePath);
            Files.writeString(getAgentsMdPath(),
                    content == null ? "" : content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("写入 AGENTS.md 失败", e);
        }
    }

    /**
     * 若 AGENTS.md 不存在则写入默认骨架；存在则不动。
     *
     * @return true=新创建；false=已存在或创建失败
     */
    public boolean ensureAgentsMdSkeleton() {
        Path p = getAgentsMdPath();
        if (Files.exists(p)) return false;
        try {
            Files.createDirectories(workspacePath);
            Files.writeString(p, MemoryPrompts.DEFAULT_AGENTS_SKELETON, StandardCharsets.UTF_8);
            log.info("已创建 AGENTS.md 骨架: {}", p);
            return true;
        } catch (IOException e) {
            log.error("创建 AGENTS.md 骨架失败", e);
            return false;
        }
    }

    // ==================== MEMORY.md ====================

    /**
     * 读取 MEMORY.md，超过 maxChars 时按字符截断并加截断尾注。
     */
    public String readMemoryMd(int maxChars) {
        Path p = getMemoryMdPath();
        if (!Files.exists(p)) return "";
        try {
            String content = Files.readString(p, StandardCharsets.UTF_8);
            if (maxChars > 0 && content.length() > maxChars) {
                return content.substring(0, maxChars)
                        + "\n\n(MEMORY.md 已截断，超过 " + maxChars + " 字符；查看完整内容请打开 "
                        + getMemoryMdPath() + ")";
            }
            return content;
        } catch (IOException e) {
            log.warn("读取 MEMORY.md 失败: {}", e.getMessage());
            return "";
        }
    }

    public void writeMemoryMd(String content) {
        try {
            Files.createDirectories(workspacePath);
            Files.writeString(getMemoryMdPath(),
                    content == null ? "" : content, StandardCharsets.UTF_8);
            log.debug("MEMORY.md 已更新（{} 字符）", content == null ? 0 : content.length());
        } catch (IOException e) {
            log.error("写入 MEMORY.md 失败", e);
        }
    }

    // ==================== memory/YYYY-MM-DD.md ====================

    /**
     * 追加一段事实到今日的日流水账文件
     */
    public void appendTodayMemory(String entry) {
        if (entry == null || entry.isBlank()) return;
        try {
            Path dir = getMemoryDir();
            Files.createDirectories(dir);
            Path daily = getDailyMemoryFile(LocalDate.now());
            String toAppend = (Files.exists(daily) ? "\n" : "")
                    + entry.trim() + "\n";
            Files.writeString(daily, toAppend, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("追加日流水账失败: {}", e.getMessage());
        }
    }

    /**
     * 列出所有日流水账文件，按日期升序（最早的在前）
     */
    public List<Path> listDailyMemoryFiles() {
        Path dir = getMemoryDir();
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".md"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            log.warn("列出日流水账失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 读取并合并多个日流水账内容（用于合并器输入）
     */
    public String readDailyMemoriesCombined(List<Path> files) {
        if (files == null || files.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Path p : files) {
            try {
                sb.append("\n## ").append(p.getFileName().toString().replace(".md", "")).append("\n\n");
                sb.append(Files.readString(p, StandardCharsets.UTF_8)).append("\n");
            } catch (IOException e) {
                log.warn("读取日流水账失败 {}: {}", p, e.getMessage());
            }
        }
        return sb.toString().trim();
    }

    /**
     * 删除已合并到 MEMORY.md 的日流水账文件
     */
    public void deleteDailyMemoryFiles(List<Path> files) {
        if (files == null) return;
        for (Path p : files) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException e) {
                log.warn("删除日流水账失败 {}: {}", p, e.getMessage());
            }
        }
    }

    // ==================== Prompt 拼接（XML 风格，借鉴 Harness） ====================

    /**
     * 构建 system prompt 注入段：把 AGENTS.md 与 MEMORY.md 包成 XML 风格上下文块。
     * 两个文件都为空时返回空字符串。
     */
    public String buildContextInjection(int memoryMaxChars) {
        String agents = readAgentsMd().trim();
        String memory = readMemoryMd(memoryMaxChars).trim();
        if (agents.isEmpty() && memory.isEmpty()) return "";

        StringBuilder sb = new StringBuilder("\n\n<loaded_context>\n");
        if (!agents.isEmpty()) {
            sb.append("<agents_context>\n").append(agents).append("\n</agents_context>\n");
        }
        if (!memory.isEmpty()) {
            sb.append("<memory_context>\n").append(memory).append("\n</memory_context>\n");
        }
        sb.append("</loaded_context>\n");
        return sb.toString();
    }
}
