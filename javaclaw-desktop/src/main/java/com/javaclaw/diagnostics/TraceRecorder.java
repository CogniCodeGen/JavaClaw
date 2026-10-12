package com.javaclaw.diagnostics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.config.WorkspaceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 结构化诊断日志写入器（JSONL）
 *
 * <p>写入路径：{@code {workspace}/logs/agent-trace.jsonl}。每行一个 JSON 对象，
 * 供诊断应用用例检索，并由 {@link TraceExporter} 打包。</p>
 *
 * <p>由根 Spring Context 管理，线程安全。首次写入时延迟创建文件。</p>
 */
public final class TraceRecorder implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TraceRecorder.class);
    private static final int MAX_CONTENT = 2048;

    private final WorkspaceManager workspaces;
    private final ObjectMapper mapper;

    private BufferedWriter writer;
    private Path currentPath;
    private boolean enabled = true;

    public TraceRecorder(WorkspaceManager workspaces, ObjectMapper mapper) {
        this.workspaces = java.util.Objects.requireNonNull(workspaces, "workspaces");
        this.mapper = java.util.Objects.requireNonNull(mapper, "mapper");
    }

    public Path tracePath() {
        return workspaces.getCurrentLogDir().resolve("agent-trace.jsonl");
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 工作区切换时重新打开文件（调用方在切换流程中触发）
     */
    public synchronized void reload() {
        closeQuietly();
        currentPath = null;
    }

    public synchronized void record(String agent, String event, Map<String, Object> fields) {
        if (!enabled) return;
        Path target = tracePath();
        if (writer == null || !target.equals(currentPath)) {
            try {
                Files.createDirectories(target.getParent());
                closeQuietly();
                writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                currentPath = target;
            } catch (IOException e) {
                log.warn("打开诊断日志文件失败: {}", e.getMessage());
                return;
            }
        }
        try {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ts", System.currentTimeMillis());
            row.put("agent", agent == null ? "unknown" : agent);
            row.put("event", event == null ? "unknown" : event);
            if (fields != null) {
                for (var e : fields.entrySet()) {
                    row.put(e.getKey(), truncate(e.getValue()));
                }
            }
            writer.write(mapper.writeValueAsString(row));
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            log.debug("写入诊断日志失败: {}", e.getMessage());
        }
    }

    private Object truncate(Object v) {
        if (!(v instanceof String s)) return v;
        if (s.length() <= MAX_CONTENT) return s;
        return s.substring(0, MAX_CONTENT) + "...(已截断)";
    }

    @Override
    public synchronized void close() {
        closeQuietly();
        currentPath = null;
    }

    private void closeQuietly() {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException failure) {
                log.debug("关闭诊断日志失败: {}", failure.getMessage());
            }
            writer = null;
        }
    }
}
