package com.javaclaw.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * MCP 客户端 — 与单个 MCP 服务器通过 stdio 进行 JSON-RPC 2.0 通信
 *
 * <p>生命周期：{@link #start()} → 使用 → {@link #stop()}。
 * 启动时自动完成 initialize 握手和工具发现。</p>
 *
 * @author JavaClaw
 */
public class McpClient {

    private static final Logger log = LoggerFactory.getLogger(McpClient.class);

    /** MCP 协议版本 */
    private static final String PROTOCOL_VERSION = "2024-11-05";

    /** 请求超时（秒） */
    private static final int REQUEST_TIMEOUT_SECONDS = 30;

    /** stderr 环形缓冲保留行数（最近 N 行） */
    private static final int STDERR_TAIL_CAPACITY = 200;

    /**
     * 服务器运行状态。UI 通过 {@link #getState()} 读取，
     * 用于渲染状态徽章和决定可用操作按钮。
     */
    public enum ServerState {
        /** 配置存在但未启动（默认状态、stop() 后状态） */
        STOPPED,
        /** start() 已调用，正在握手/发现工具 */
        STARTING,
        /** 握手成功，进程存活，工具已发现 */
        RUNNING,
        /** 启动失败或运行中崩溃 */
        FAILED
    }

    private final McpServerConfig config;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicInteger requestIdCounter = new AtomicInteger(1);
    private final Map<Integer, CompletableFuture<JsonNode>> pendingRequests = new ConcurrentHashMap<>();

    private Process process;
    private BufferedWriter writer;
    private Thread readerThread;
    private volatile boolean running = false;

    /** 服务器信息 */
    private String serverName;
    private String serverVersion;

    /** 已发现的工具列表 */
    private List<McpToolInfo> tools = List.of();

    /** 当前生命周期状态（UI 通过此字段渲染徽章） */
    private final AtomicReference<ServerState> state = new AtomicReference<>(ServerState.STOPPED);

    /** 最近一次启动失败的错误摘要；成功启动时清空 */
    private volatile String startupError;

    /** 进入 RUNNING 状态时的时间戳；尚未启动或已停止时为 0 */
    private volatile long startedAtMs;

    /** stderr 最近输出的环形缓冲，UI 在卡片错误抽屉中展示 */
    private final Deque<String> stderrTail = new ArrayDeque<>(STDERR_TAIL_CAPACITY);

    /** 状态切换回调（McpClientManager 注入）；可为 null */
    private volatile Runnable stateChangeListener;

    // ---------- HTTP 传输专用字段 ----------

    /** HTTP 模式下的 JDK HttpClient；stdio 模式时为 null */
    private HttpClient httpClient;

    /** initialize 响应中由服务器分配的会话 ID（部分实现要求后续请求带回） */
    private volatile String mcpSessionId;

    public McpClient(McpServerConfig config) {
        this.config = config;
    }

    /**
     * 启动 MCP 服务器进程并完成初始化握手。
     *
     * <p>状态转移：STOPPED → STARTING → (RUNNING | FAILED)。
     * 失败时 {@link #startupError} 被填充，调用方可继续读取以展示错误。</p>
     */
    public void start() throws Exception {
        transitionState(ServerState.STARTING);
        startupError = null;
        try {
            doStart();
            startedAtMs = System.currentTimeMillis();
            transitionState(ServerState.RUNNING);
        } catch (Exception e) {
            startupError = summarizeError(e);
            transitionState(ServerState.FAILED);
            throw e;
        }
    }

    private void doStart() throws Exception {
        if ("http".equals(config.getTransport())) {
            doStartHttp();
        } else {
            doStartStdio();
        }
    }

    /**
     * HTTP / streamable-HTTP 传输：以 JSON-RPC over POST 为主，兼容 SSE 响应。
     */
    private void doStartHttp() throws Exception {
        log.info("正在初始化 MCP 服务器（HTTP）: {} (url: {})",
                config.getName(), config.getUrl());

        if (config.getUrl() == null || config.getUrl().isBlank()) {
            throw new IllegalStateException("HTTP MCP 配置缺少 url");
        }
        // URL 早期校验，给出友好错误而非 NPE
        URI.create(config.getUrl());

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        running = true;

        // initialize 握手
        ObjectNode initParams = objectMapper.createObjectNode();
        initParams.put("protocolVersion", PROTOCOL_VERSION);
        initParams.set("capabilities", objectMapper.createObjectNode());
        ObjectNode clientInfo = objectMapper.createObjectNode();
        clientInfo.put("name", "JavaClaw");
        clientInfo.put("version", "1.0.0");
        initParams.set("clientInfo", clientInfo);

        JsonNode initResult = sendRequest("initialize", initParams);
        if (initResult.has("serverInfo")) {
            JsonNode info = initResult.get("serverInfo");
            serverName = info.has("name") ? info.get("name").asText() : config.getName();
            serverVersion = info.has("version") ? info.get("version").asText() : "unknown";
        } else {
            serverName = config.getName();
            serverVersion = "unknown";
        }
        log.info("MCP 服务器已初始化: {} v{} (transport=http)", serverName, serverVersion);

        // initialized 通知
        sendNotification("notifications/initialized", null);

        // 发现工具
        discoverTools();
    }

    /**
     * stdio 传输：原 doStart 主体，保持原有行为不变
     */
    private void doStartStdio() throws Exception {
        if (config.getCommand() == null || config.getCommand().isBlank()) {
            throw new IllegalStateException("stdio MCP 配置缺少 command（如需远程 HTTP MCP，请配置 url）");
        }
        log.info("正在启动 MCP 服务器: {} (command: {} {})",
                config.getName(), config.getCommand(), config.getArgs());

        // 构建进程命令
        List<String> command = new ArrayList<>();
        command.add(config.getCommand());
        command.addAll(config.getArgs());

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);

        // 设置环境变量
        if (config.getEnv() != null && !config.getEnv().isEmpty()) {
            pb.environment().putAll(config.getEnv());
        }

        // 继承 PATH 等常用环境变量
        Map<String, String> sysEnv = System.getenv();
        if (!pb.environment().containsKey("PATH") && sysEnv.containsKey("PATH")) {
            pb.environment().put("PATH", sysEnv.get("PATH"));
        }

        process = pb.start();
        try {
            writer = new BufferedWriter(new OutputStreamWriter(
                    process.getOutputStream(), StandardCharsets.UTF_8));
        } catch (Throwable t) {
            // writer 构造失败（OOM 等）必须立即回收子进程，否则变僵尸
            process.destroyForcibly();
            throw t;
        }
        running = true;

        // 启动 stderr 日志线程
        Thread stderrThread = new Thread(() -> {
            try (BufferedReader errReader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = errReader.readLine()) != null) {
                    appendStderr(line);
                    log.debug("[MCP:{}:stderr] {}", config.getName(), line);
                }
            } catch (IOException e) {
                if (running) {
                    log.debug("MCP stderr 读取结束: {}", config.getName());
                }
            }
        }, "mcp-stderr-" + config.getName());
        stderrThread.setDaemon(true);
        stderrThread.start();

        // 启动 stdout 读取线程
        readerThread = new Thread(this::readLoop, "mcp-reader-" + config.getName());
        readerThread.setDaemon(true);
        readerThread.start();

        // 发送 initialize 请求（含 clientInfo）
        ObjectNode initParams = objectMapper.createObjectNode();
        initParams.put("protocolVersion", PROTOCOL_VERSION);
        initParams.set("capabilities", objectMapper.createObjectNode());
        ObjectNode clientInfo = objectMapper.createObjectNode();
        clientInfo.put("name", "JavaClaw");
        clientInfo.put("version", "1.0.0");
        initParams.set("clientInfo", clientInfo);

        JsonNode initResult = sendRequest("initialize", initParams);

        // 解析服务器信息
        if (initResult.has("serverInfo")) {
            JsonNode info = initResult.get("serverInfo");
            serverName = info.has("name") ? info.get("name").asText() : config.getName();
            serverVersion = info.has("version") ? info.get("version").asText() : "unknown";
        } else {
            serverName = config.getName();
            serverVersion = "unknown";
        }
        log.info("MCP 服务器已初始化: {} v{}", serverName, serverVersion);

        // 发送 initialized 通知
        sendNotification("notifications/initialized", null);

        // 发现工具
        discoverTools();
    }

    /**
     * 发现服务器提供的工具
     */
    private void discoverTools() throws Exception {
        JsonNode result = sendRequest("tools/list", objectMapper.createObjectNode());
        List<McpToolInfo> discovered = new ArrayList<>();

        if (result.has("tools") && result.get("tools").isArray()) {
            for (JsonNode toolNode : result.get("tools")) {
                McpToolInfo tool = new McpToolInfo();
                tool.setName(toolNode.has("name") ? toolNode.get("name").asText() : "unknown");
                tool.setDescription(toolNode.has("description") ?
                        toolNode.get("description").asText() : "");
                if (toolNode.has("inputSchema")) {
                    tool.setInputSchema(toolNode.get("inputSchema"));
                }
                discovered.add(tool);
            }
        }

        this.tools = List.copyOf(discovered);
        log.info("MCP 服务器 {} 已发现 {} 个工具: {}", config.getName(), tools.size(),
                tools.stream().map(McpToolInfo::getName).toList());
    }

    /**
     * 调用 MCP 工具
     *
     * @param toolName  工具名称
     * @param arguments 参数（JSON 对象）
     * @return 工具返回的内容文本
     */
    public String callTool(String toolName, JsonNode arguments) throws Exception {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("name", toolName);
        params.set("arguments", arguments != null ? arguments : objectMapper.createObjectNode());

        JsonNode result = sendRequest("tools/call", params);

        // 解析结果内容
        StringBuilder sb = new StringBuilder();
        if (result.has("content") && result.get("content").isArray()) {
            for (JsonNode block : result.get("content")) {
                String type = block.has("type") ? block.get("type").asText() : "text";
                if ("text".equals(type) && block.has("text")) {
                    if (!sb.isEmpty()) sb.append("\n");
                    sb.append(block.get("text").asText());
                }
            }
        }

        // 检查是否有错误标记
        if (result.has("isError") && result.get("isError").asBoolean()) {
            throw new RuntimeException("MCP 工具调用返回错误: " + sb);
        }

        return sb.toString();
    }

    /**
     * 发送 JSON-RPC 请求并等待响应（按传输类型分流）
     */
    private JsonNode sendRequest(String method, JsonNode params) throws Exception {
        if (httpClient != null) {
            return sendHttpRequest(method, params);
        }
        return sendStdioRequest(method, params);
    }

    /**
     * stdio：通过 stdin 写出，从 readLoop 投递结果到 future
     */
    private JsonNode sendStdioRequest(String method, JsonNode params) throws Exception {
        int id = requestIdCounter.getAndIncrement();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pendingRequests.put(id, future);

        ObjectNode request = objectMapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        if (params != null) {
            request.set("params", params);
        }

        String json = objectMapper.writeValueAsString(request);
        sendLine(json);
        log.debug("[MCP:{}] → {}", config.getName(), json);

        try {
            JsonNode response = future.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return unwrapResult(response);
        } finally {
            pendingRequests.remove(id);
        }
    }

    /**
     * HTTP：POST JSON-RPC 请求，解析 JSON 或 SSE 响应。
     */
    private JsonNode sendHttpRequest(String method, JsonNode params) throws Exception {
        int id = requestIdCounter.getAndIncrement();
        ObjectNode request = objectMapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        if (params != null) {
            request.set("params", params);
        }
        String json = objectMapper.writeValueAsString(request);
        log.debug("[MCP:{} http] → {}", config.getName(), json);

        HttpRequest httpReq = buildHttpRequest(json, true);
        HttpResponse<String> resp = httpClient.send(httpReq, HttpResponse.BodyHandlers.ofString());

        // 服务器可能在 initialize 响应里下发 session id；后续请求需带回
        if ("initialize".equals(method)) {
            resp.headers().firstValue("mcp-session-id")
                    .or(() -> resp.headers().firstValue("Mcp-Session-Id"))
                    .ifPresent(s -> this.mcpSessionId = s);
        }

        int status = resp.statusCode();
        if (status < 200 || status >= 300) {
            throw new RuntimeException("HTTP MCP 请求失败 [" + status + "]: " + truncate(resp.body(), 240));
        }

        String body = resp.body() == null ? "" : resp.body();
        String contentType = resp.headers().firstValue("content-type")
                .or(() -> resp.headers().firstValue("Content-Type"))
                .orElse("application/json")
                .toLowerCase(Locale.ROOT);

        JsonNode response = contentType.contains("text/event-stream")
                ? parseSseForId(body, id)
                : objectMapper.readTree(body);

        log.debug("[MCP:{} http] ← {}", config.getName(), truncate(response.toString(), 240));
        return unwrapResult(response);
    }

    /**
     * 发送 JSON-RPC 通知（不需要响应）— 按传输类型分流
     */
    private void sendNotification(String method, JsonNode params) throws Exception {
        ObjectNode notification = objectMapper.createObjectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", method);
        if (params != null) {
            notification.set("params", params);
        }
        String json = objectMapper.writeValueAsString(notification);
        if (httpClient != null) {
            HttpRequest httpReq = buildHttpRequest(json, false);
            // notifications 不强求成功状态码，但记录日志
            HttpResponse<String> resp = httpClient.send(httpReq, HttpResponse.BodyHandlers.ofString());
            log.debug("[MCP:{} http] → (notification) {} status={}",
                    config.getName(), method, resp.statusCode());
        } else {
            sendLine(json);
            log.debug("[MCP:{}] → (notification) {}", config.getName(), json);
        }
    }

    /**
     * 通用 JSON-RPC 响应处理：错误抛异常，否则取 result（无 result 返回空 object）
     */
    private JsonNode unwrapResult(JsonNode response) {
        if (response.has("error")) {
            JsonNode error = response.get("error");
            String errorMsg = error.has("message") ? error.get("message").asText() : "未知错误";
            int errorCode = error.has("code") ? error.get("code").asInt() : -1;
            throw new RuntimeException("MCP 错误 [" + errorCode + "]: " + errorMsg);
        }
        return response.has("result") ? response.get("result") : objectMapper.createObjectNode();
    }

    /**
     * 构造一个 POST 请求，自动注入 Accept、自定义 headers 和 mcp-session-id
     */
    private HttpRequest buildHttpRequest(String body, boolean expectsResponse) {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(config.getUrl()))
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .header("Content-Type", "application/json")
                .header("Accept",
                        expectsResponse
                                ? "application/json, text/event-stream"
                                : "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (config.getHeaders() != null) {
            for (Map.Entry<String, String> e : config.getHeaders().entrySet()) {
                if (e.getKey() == null || e.getKey().isBlank()) continue;
                b.header(e.getKey(), e.getValue() == null ? "" : e.getValue());
            }
        }
        if (mcpSessionId != null && !mcpSessionId.isBlank()) {
            b.header("Mcp-Session-Id", mcpSessionId);
        }
        return b.build();
    }

    /**
     * 极简 SSE 解析：把 {@code data:} 行串起来，按空行切分事件，
     * 找到含目标 id 的 JSON-RPC 响应。
     */
    private JsonNode parseSseForId(String sseBody, int targetId) throws IOException {
        StringBuilder dataBuf = new StringBuilder();
        String[] lines = sseBody.split("\\r?\\n", -1);
        for (String line : lines) {
            if (line.isEmpty()) {
                // 事件结束
                if (dataBuf.length() > 0) {
                    JsonNode candidate = tryParseJson(dataBuf.toString());
                    if (candidate != null && candidate.has("id")
                            && candidate.get("id").asInt() == targetId) {
                        return candidate;
                    }
                    dataBuf.setLength(0);
                }
            } else if (line.startsWith("data:")) {
                if (dataBuf.length() > 0) dataBuf.append('\n');
                dataBuf.append(line.substring(5).stripLeading());
            }
            // 忽略 event:/id:/retry: 等其他字段
        }
        // 末事件未以空行结束的兜底
        if (dataBuf.length() > 0) {
            JsonNode candidate = tryParseJson(dataBuf.toString());
            if (candidate != null && candidate.has("id")
                    && candidate.get("id").asInt() == targetId) {
                return candidate;
            }
        }
        throw new IOException("SSE 响应中未找到 id=" + targetId + " 的 JSON-RPC 应答");
    }

    private JsonNode tryParseJson(String s) {
        try {
            return objectMapper.readTree(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    /**
     * 向 stdin 写入一行 JSON
     */
    private synchronized void sendLine(String json) throws IOException {
        if (writer == null) {
            throw new IOException("MCP writer 未初始化或已关闭");
        }
        writer.write(json);
        writer.newLine();
        writer.flush();
    }

    /**
     * 从 stdout 持续读取 JSON-RPC 消息的循环
     */
    private void readLoop() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (running && (line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                log.debug("[MCP:{}] ← {}", config.getName(), line);
                try {
                    JsonNode msg = objectMapper.readTree(line);

                    // 响应消息（带 id）
                    if (msg.has("id") && !msg.get("id").isNull()) {
                        int id = msg.get("id").asInt();
                        CompletableFuture<JsonNode> future = pendingRequests.get(id);
                        if (future != null) {
                            future.complete(msg);
                        }
                    }
                    // 通知消息（无 id）— 目前仅记录日志
                    else if (msg.has("method")) {
                        log.debug("[MCP:{}] 收到通知: {}", config.getName(), msg.get("method").asText());
                    }
                } catch (Exception e) {
                    log.warn("[MCP:{}] 解析消息失败: {}", config.getName(), line, e);
                }
            }
        } catch (IOException e) {
            if (running) {
                log.warn("MCP 服务器 {} stdout 读取异常", config.getName(), e);
            }
        } finally {
            // 进程退出后，完成所有等待中的请求
            for (CompletableFuture<JsonNode> future : pendingRequests.values()) {
                future.completeExceptionally(new IOException("MCP 服务器进程已退出"));
            }
            pendingRequests.clear();
        }
    }

    /**
     * 停止 MCP 服务器（按传输类型释放资源）
     */
    public void stop() {
        boolean wasRunning = running;
        running = false;
        log.info("正在停止 MCP 服务器: {}", config.getName());

        // HTTP 模式：仅断开 HttpClient，无进程
        if (httpClient != null) {
            httpClient = null;
            mcpSessionId = null;
        }

        // 关闭 writer，避免 sendLine 竞态使用半关闭流
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException e) {
                log.debug("关闭 MCP writer 失败", e);
            }
            writer = null;
        }

        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    log.warn("MCP 服务器 {} 强制终止", config.getName());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }

        if (readerThread != null) {
            readerThread.interrupt();
        }

        startedAtMs = 0L;
        // 失败状态保留以便 UI 展示错误；正常停止才回到 STOPPED
        if (wasRunning || state.get() == ServerState.RUNNING || state.get() == ServerState.STARTING) {
            transitionState(ServerState.STOPPED);
        }

        log.info("MCP 服务器已停止: {}", config.getName());
    }

    /**
     * 服务器是否在运行（按传输类型分流）
     */
    public boolean isRunning() {
        if ("http".equals(config.getTransport())) {
            return running && httpClient != null;
        }
        return running && process != null && process.isAlive();
    }

    /** 当前生命周期状态，UI 渲染状态徽章用 */
    public ServerState getState() {
        return state.get();
    }

    /** 最近一次启动失败的错误摘要；正常运行返回 null */
    public String getStartupError() {
        return startupError;
    }

    /** 最近进入 RUNNING 状态的时间戳；未启动返回 0 */
    public long getStartedAtMs() {
        return startedAtMs;
    }

    /**
     * 获取 stderr 最近若干行（最早 → 最新顺序）。
     * 用于「启动失败」抽屉中查看进程输出。
     */
    public List<String> getStderrTail() {
        synchronized (stderrTail) {
            return new ArrayList<>(stderrTail);
        }
    }

    /** 注入状态变化监听器（McpClientManager 使用） */
    public void setStateChangeListener(Runnable listener) {
        this.stateChangeListener = listener;
    }

    private void transitionState(ServerState next) {
        ServerState prev = state.getAndSet(next);
        if (prev != next) {
            Runnable listener = this.stateChangeListener;
            if (listener != null) {
                try { listener.run(); } catch (Exception ignored) {}
            }
        }
    }

    private void appendStderr(String line) {
        synchronized (stderrTail) {
            if (stderrTail.size() >= STDERR_TAIL_CAPACITY) {
                stderrTail.removeFirst();
            }
            stderrTail.addLast(line);
        }
    }

    private static String summarizeError(Throwable e) {
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) msg = e.getClass().getSimpleName();
        // 控制长度，UI 卡片单行展示更清爽
        return msg.length() > 240 ? msg.substring(0, 240) + "…" : msg;
    }

    /**
     * 获取服务器配置
     */
    public McpServerConfig getConfig() {
        return config;
    }

    /**
     * 获取已发现的工具列表
     */
    public List<McpToolInfo> getTools() {
        return tools;
    }

    /**
     * 获取服务器名称（来自初始化握手）
     */
    public String getServerName() {
        return serverName;
    }

    /**
     * 获取服务器版本
     */
    public String getServerVersion() {
        return serverVersion;
    }

    /**
     * MCP 工具信息
     */
    public static class McpToolInfo {
        private String name;
        private String description;
        private JsonNode inputSchema;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public JsonNode getInputSchema() {
            return inputSchema;
        }

        public void setInputSchema(JsonNode inputSchema) {
            this.inputSchema = inputSchema;
        }

        @Override
        public String toString() {
            return name + ": " + description;
        }
    }
}
