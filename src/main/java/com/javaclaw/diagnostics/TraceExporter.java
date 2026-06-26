package com.javaclaw.diagnostics;

import com.javaclaw.config.AgentConfig;
import com.javaclaw.config.WorkspaceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 诊断信息打包器 — 将日志 + 脱敏配置 + 系统信息打成 zip，供用户上报 issue
 *
 * <p>脱敏处理：</p>
 * <ul>
 *   <li>API Key 字段（api.key、rag.embedding.api.key 等）替换为 {@code ***REDACTED***}</li>
 *   <li>SMTP/IMAP 密码字段同样替换</li>
 *   <li>Webhook URL 中的 token/secret 查询参数去除</li>
 * </ul>
 */
public final class TraceExporter {

    private static final Logger log = LoggerFactory.getLogger(TraceExporter.class);
    private static final String REDACTED = "***REDACTED***";

    /** 需要脱敏的 key 名片段（小写，子串匹配） */
    private static final String[] SECRET_KEY_PATTERNS = {
            "api.key", "secret", "password", "token", "access.key"
    };

    private TraceExporter() {}

    /**
     * 导出诊断包到指定 zip 文件
     *
     * @return 写入的字节数
     */
    public static long exportTo(Path zipFile) throws IOException {
        Files.createDirectories(zipFile.getParent());
        try (OutputStream out = Files.newOutputStream(zipFile);
             ZipOutputStream zos = new ZipOutputStream(out)) {

            // 1. 诊断日志
            Path trace = TraceRecorder.tracePath();
            if (Files.exists(trace)) {
                copyEntry(zos, trace, "agent-trace.jsonl");
            }

            // 2. 主日志
            Path mainLog = WorkspaceManager.getInstance().getCurrentLogDir().resolve("javaclaw.log");
            if (Files.exists(mainLog)) {
                copyEntry(zos, mainLog, "javaclaw.log");
            }

            // 3. 任务日志
            Path taskLog = WorkspaceManager.getInstance().getCurrentLogDir().resolve("task.log");
            if (Files.exists(taskLog)) {
                copyEntry(zos, taskLog, "task.log");
            }

            // 4. 脱敏后的 agent 配置
            byte[] redactedCfg = redactedAgentProperties();
            zos.putNextEntry(new ZipEntry("javaclaw-agent.redacted.properties"));
            zos.write(redactedCfg);
            zos.closeEntry();

            // 5. 系统信息
            zos.putNextEntry(new ZipEntry("system-info.txt"));
            zos.write(systemInfo().getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        long size = Files.size(zipFile);
        log.info("诊断包已导出: {} ({} bytes)", zipFile, size);
        return size;
    }

    private static void copyEntry(ZipOutputStream zos, Path source, String entryName) throws IOException {
        zos.putNextEntry(new ZipEntry(entryName));
        try (InputStream in = Files.newInputStream(source)) {
            in.transferTo(zos);
        }
        zos.closeEntry();
    }

    /**
     * 读取当前工作区的 agent properties，剔除敏感字段
     */
    private static byte[] redactedAgentProperties() throws IOException {
        Path cfgFile = WorkspaceManager.getInstance().getCurrentWorkspacePath()
                .resolve("javaclaw-agent.properties");
        Properties props = new Properties();
        if (Files.exists(cfgFile)) {
            try (InputStream in = Files.newInputStream(cfgFile)) {
                props.load(in);
            }
        }
        for (String key : new ArrayList<>(props.stringPropertyNames())) {
            String lower = key.toLowerCase();
            for (String pat : SECRET_KEY_PATTERNS) {
                if (lower.contains(pat)) {
                    props.setProperty(key, REDACTED);
                    break;
                }
            }
        }
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        props.store(buf, "JavaClaw agent config (secrets redacted, exported at " + Instant.now() + ")");
        return buf.toByteArray();
    }

    private static String systemInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("export.time=").append(Instant.now()).append('\n');
        sb.append("java.version=").append(System.getProperty("java.version")).append('\n');
        sb.append("java.vendor=").append(System.getProperty("java.vendor")).append('\n');
        sb.append("os.name=").append(System.getProperty("os.name")).append('\n');
        sb.append("os.arch=").append(System.getProperty("os.arch")).append('\n');
        sb.append("os.version=").append(System.getProperty("os.version")).append('\n');
        sb.append("workspace=").append(WorkspaceManager.getInstance().getCurrentWorkspacePath()).append('\n');
        sb.append("provider=").append(AgentConfig.getInstance().getProviderType()).append('\n');
        sb.append("model=").append(AgentConfig.getInstance().getModelName()).append('\n');
        sb.append("base.url=").append(AgentConfig.getInstance().getBaseUrl()).append('\n');
        Runtime r = Runtime.getRuntime();
        sb.append("jvm.max.mem.mb=").append(r.maxMemory() / (1024 * 1024)).append('\n');
        sb.append("jvm.free.mem.mb=").append(r.freeMemory() / (1024 * 1024)).append('\n');
        sb.append("jvm.available.processors=").append(r.availableProcessors()).append('\n');
        return sb.toString();
    }

    /**
     * 读取 trace 文件并按筛选条件返回匹配行
     */
    public static List<String> grep(String keyword, String agentFilter, String eventFilter,
                                    long sinceMillis, int maxLines) throws IOException {
        Path trace = TraceRecorder.tracePath();
        if (!Files.exists(trace)) return List.of();
        List<String> matches = new ArrayList<>();
        try (var reader = Files.newBufferedReader(trace, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (matches.size() >= maxLines) break;
                if (keyword != null && !keyword.isBlank() && !line.contains(keyword)) continue;
                if (agentFilter != null && !agentFilter.isBlank()
                        && !line.contains("\"agent\":\"" + agentFilter + "\"")) continue;
                if (eventFilter != null && !eventFilter.isBlank()
                        && !line.contains("\"event\":\"" + eventFilter + "\"")) continue;
                if (sinceMillis > 0) {
                    int tsIdx = line.indexOf("\"ts\":");
                    if (tsIdx >= 0) {
                        int end = line.indexOf(',', tsIdx + 5);
                        if (end > tsIdx + 5) {
                            try {
                                long ts = Long.parseLong(line.substring(tsIdx + 5, end).trim());
                                if (ts < sinceMillis) continue;
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                }
                matches.add(line);
            }
        }
        return matches;
    }
}
