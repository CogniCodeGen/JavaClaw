package com.javaclaw.task.sdd;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * SDD 实现阶段的"盘上工作记忆"——与提供商无关的省 token 手段（不靠缓存，靠不重发）。
 *
 * <ul>
 *   <li><b>项目地图</b> {@code .agent/project-map.md}：确定性地列出工作目录文件清单（零模型调用），
 *       注入实现项提示，让执行体不必每项重跑 {@code find} 重新发现项目结构。</li>
 *   <li><b>进度账本</b> {@code .agent/progress.md}：每完成一项追加一行蒸馏摘要，下一项读账本尾部即可
 *       获得跨项记忆，而无需把前项的完整 ReAct transcript 带进上下文（也跨 resume 天然幸存）。</li>
 * </ul>
 *
 * @author JavaClaw
 */
public final class SddWorkNotes {

    private static final Logger log = LoggerFactory.getLogger(SddWorkNotes.class);

    /** 项目地图最多列出的文件数（写盘上限）。 */
    private static final int MAP_MAX_FILES = 400;
    /** 注入提示词时最多带的文件行数（控制注入体积——该清单随 ReAct 每轮迭代全量重发，是固定载荷大头）。 */
    private static final int MAP_INJECT_LINES = 80;
    /** 指纹/地图跳过的目录。 */
    private static final String[] SKIP_DIRS = {
            "/.agent/", "/target/", "/build/", "/.git/", "/node_modules/", "/.gradle/", "/dist/", "/out/"};

    private SddWorkNotes() {}

    private static Path agentDir(String workDir) {
        if (workDir == null || workDir.isBlank()) return null;
        return Path.of(workDir).toAbsolutePath().resolve(".agent");
    }

    /**
     * 重建并落盘项目地图，返回可注入提示词的精简内容（已限行）。工作目录无效返回空串。
     * 确定性文件遍历，零模型调用。
     */
    public static String ensureProjectMap(String workDir) {
        Path base = (workDir == null || workDir.isBlank()) ? null : Path.of(workDir).toAbsolutePath();
        if (base == null || !Files.isDirectory(base)) return "";
        List<String> files = new ArrayList<>();
        try (Stream<Path> s = Files.walk(base)) {
            s.filter(Files::isRegularFile)
                    .map(base::relativize)
                    .map(p -> p.toString().replace('\\', '/'))
                    .filter(rel -> { for (String sk : SKIP_DIRS) if (("/" + rel).contains(sk)) return false; return true; })
                    .sorted()
                    .limit(MAP_MAX_FILES)
                    .forEach(files::add);
        } catch (Exception e) {
            log.debug("[WorkNotes] 构建项目地图失败：{}", e.getMessage());
            return "";
        }
        String full = "# 项目文件清单（" + files.size() + " 个）\n" + String.join("\n", files);
        // 落盘（best-effort，供人/工具查看）
        try {
            Path dir = agentDir(workDir);
            if (dir != null) {
                Files.createDirectories(dir);
                Files.writeString(dir.resolve("project-map.md"), full);
            }
        } catch (Exception e) {
            log.debug("[WorkNotes] 写项目地图失败（忽略）：{}", e.getMessage());
        }
        // 注入用：限行
        if (files.size() <= MAP_INJECT_LINES) return full;
        return "# 项目文件清单（共 " + files.size() + " 个，仅列前 " + MAP_INJECT_LINES + " 个；其余见 .agent/project-map.md）\n"
                + String.join("\n", files.subList(0, MAP_INJECT_LINES));
    }

    /** 读进度账本尾部（最近 maxLines 行）；无则空串。 */
    public static String readLedgerTail(String workDir, int maxLines) {
        Path dir = agentDir(workDir);
        if (dir == null) return "";
        Path f = dir.resolve("progress.md");
        if (!Files.exists(f)) return "";
        try {
            List<String> lines = Files.readAllLines(f);
            int from = Math.max(0, lines.size() - Math.max(1, maxLines));
            return String.join("\n", lines.subList(from, lines.size()));
        } catch (Exception e) {
            return "";
        }
    }

    /** 向进度账本追加一行（最新在末尾）。 */
    public static void appendLedger(String workDir, String line) {
        Path dir = agentDir(workDir);
        if (dir == null || line == null || line.isBlank()) return;
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("progress.md"), line.strip() + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            log.debug("[WorkNotes] 追加进度账本失败（忽略）：{}", e.getMessage());
        }
    }

    /** 取一段文本的单行摘要（首个非空行，限长）。 */
    public static String oneLine(String text, int max) {
        if (text == null) return "";
        for (String l : text.split("\n")) {
            String t = l.strip();
            if (!t.isEmpty()) return t.length() > max ? t.substring(0, max) + "…" : t;
        }
        return "";
    }
}
