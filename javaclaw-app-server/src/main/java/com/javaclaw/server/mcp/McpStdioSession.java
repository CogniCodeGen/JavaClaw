package com.javaclaw.server.mcp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.McpEndpoint;
import com.javaclaw.api.McpProtocol;
import com.javaclaw.api.McpRemoteSession;
import com.javaclaw.extension.spi.McpClientInteractionPort;
import com.javaclaw.protocol.CanonicalJson;

/**
 * 单个签名 Bundle MCP 进程的有界 JSON-RPC stdio 会话。
 *
 * <p>每行最多 4 MiB，最多处理 16 个反向请求或通知。反向请求仅允许受限 elicitation 与 sampling；未知方法以 JSON-RPC error 回复，外部内容从不进入 system 指令。
 */
final class McpStdioSession implements AutoCloseable {
    private static final int MAXIMUM_LINE_BYTES = 4 * 1024 * 1024;
    private static final int MAXIMUM_INTERACTIONS = 16;

    private final Process process;
    private final McpEndpoint endpoint;
    private final McpClientInteractionPort interactions;
    private final CancellationToken cancellation;
    private final CanonicalJson json;
    private final McpReverseRequestHandler reverseRequests;
    private final InputStream input;
    private final OutputStream output;
    private final AtomicLong sequence = new AtomicLong();

    /**
     * 接管已启动进程的 stdin/stdout。
     *
     * @param process 原生 Sandbox 进程
     * @param endpoint 精确 Endpoint revision
     * @param interactions 当前调用受治理交互
     * @param cancellation 取消信号
     * @param json 严格 JSON codec
     * @param clock 平台时钟
     */
    McpStdioSession(
            Process process,
            McpEndpoint endpoint,
            McpClientInteractionPort interactions,
            CancellationToken cancellation,
            CanonicalJson json,
            Clock clock) {
        this.process = Objects.requireNonNull(process, "process");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.interactions = Objects.requireNonNull(interactions, "interactions");
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
        this.json = Objects.requireNonNull(json, "json");
        reverseRequests = new McpReverseRequestHandler(json, Objects.requireNonNull(clock, "clock"));
        input = process.getInputStream();
        output = process.getOutputStream();
    }

    /**
     * 固定协商 MCP 版本并发送 initialized 通知。
     *
     * @param advertiseInteractions 是否声明受治理反向交互
     * @return 远端能力
     * @throws Exception framing、协议或进程失败
     */
    McpRemoteSession initialize(boolean advertiseInteractions) throws Exception {
        CanonicalPayload capabilities = advertiseInteractions
                ? json.encode(Map.of("elicitation", Map.of("form", Map.of()), "sampling", Map.of()))
                : json.parse("{}");
        CanonicalPayload params = json.encode(Map.of(
                "protocolVersion",
                McpProtocol.VERSION,
                "capabilities",
                capabilities,
                "clientInfo",
                Map.of("name", "JavaClaw", "version", "5.0")));
        CanonicalPayload result = call("initialize", params);
        String protocol = json.textField(result, "protocolVersion")
                .orElseThrow(() -> new IllegalArgumentException("MCP initialize omitted protocolVersion"));
        if (McpProtocol.VERSION.equals(protocol)) {
            notify("notifications/initialized", json.parse("{}"));
        }
        Set<String> capabilitiesResult =
                json.objectField(result, "capabilities").map(json::fieldNames).orElse(Set.of());
        return new McpRemoteSession(protocol, capabilitiesResult);
    }

    /**
     * 发送请求并处理有界反向交互。
     *
     * @param method MCP 方法
     * @param params 规范参数
     * @return 规范 result
     * @throws Exception 传输、取消、反向交互或远端错误
     */
    CanonicalPayload call(String method, CanonicalPayload params) throws Exception {
        String id = "javaclaw-stdio-" + sequence.incrementAndGet();
        write(json.encode(Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params)));
        for (int handled = 0; handled <= MAXIMUM_INTERACTIONS; handled++) {
            CanonicalPayload message = read();
            Optional<String> responseId = json.textField(message, "id");
            Optional<String> incomingMethod = json.textField(message, "method");
            if (incomingMethod.isPresent()) {
                if (responseId.isPresent()) {
                    reverse(responseId.orElseThrow(), incomingMethod.orElseThrow(), message);
                }
                continue;
            }
            if (responseId.filter(id::equals).isEmpty()) {
                throw new IllegalArgumentException("MCP stdio response id does not match request");
            }
            if (json.objectField(message, "error").isPresent()) {
                throw new IllegalStateException("MCP stdio remote returned a JSON-RPC error");
            }
            return json.objectField(message, "result")
                    .orElseThrow(() -> new IllegalArgumentException("MCP stdio response omitted result"));
        }
        throw new IllegalStateException("MCP stdio interaction limit exceeded");
    }

    private void reverse(String id, String method, CanonicalPayload envelope) throws Exception {
        CanonicalPayload params = json.objectField(envelope, "params").orElseGet(() -> json.parse("{}"));
        try {
            CanonicalPayload result = reverseRequests.handle(endpoint, id, method, params, interactions, cancellation);
            write(json.encode(Map.of("jsonrpc", "2.0", "id", id, "result", result)));
        } catch (UnsupportedOperationException failure) {
            write(error(id, -32601, "reverse method is not allowed"));
        } catch (IllegalArgumentException failure) {
            write(error(id, -32602, "reverse request is invalid"));
        }
    }

    private CanonicalPayload error(String id, int code, String message) {
        return json.encode(Map.of("jsonrpc", "2.0", "id", id, "error", Map.of("code", code, "message", message)));
    }

    private void notify(String method, CanonicalPayload params) throws IOException {
        write(json.encode(Map.of("jsonrpc", "2.0", "method", method, "params", params)));
    }

    private void write(CanonicalPayload payload) throws IOException {
        cancellation.throwIfCancelled();
        byte[] bytes = payload.json().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAXIMUM_LINE_BYTES) {
            throw new IOException("MCP stdio request exceeds framing limit");
        }
        output.write(bytes);
        output.write('\n');
        output.flush();
    }

    private CanonicalPayload read() throws Exception {
        byte[] line = readWithDeadline(endpoint.spec().requestTimeout());
        return json.parse(decode(line));
    }

    private byte[] readWithDeadline(Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        try (ExecutorService reader = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("mcp-stdio-read-", 0).factory())) {
            Future<byte[]> future = reader.submit(this::readLine);
            while (true) {
                cancellation.throwIfCancelled();
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    future.cancel(true);
                    throw new TimeoutException("MCP stdio response timed out");
                }
                try {
                    return future.get(Math.min(remaining, Duration.ofMillis(50).toNanos()), TimeUnit.NANOSECONDS);
                } catch (TimeoutException ignored) {
                    // 短等待只用于传播取消与总时限，读取虚拟线程继续持有 stdout。
                } catch (ExecutionException failure) {
                    Throwable cause = failure.getCause();
                    if (cause instanceof Exception exception) {
                        throw exception;
                    }
                    throw new IOException("MCP stdio reader failed", cause);
                }
            }
        }
    }

    private byte[] readLine() throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        for (int read; (read = input.read()) >= 0; ) {
            if (read == '\n') {
                return line.toByteArray();
            }
            if (read != '\r') {
                if (line.size() == MAXIMUM_LINE_BYTES) {
                    throw new IOException("MCP stdio response exceeds framing limit");
                }
                line.write(read);
            }
        }
        throw new IOException("MCP stdio process closed before a complete frame");
    }

    private static String decode(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
    }

    /** 关闭管道并终止整个原生 Sandbox 进程。 */
    @Override
    public void close() {
        try {
            output.close();
        } catch (IOException ignored) {
            // 进程可能已先关闭管道。
        }
        try {
            input.close();
        } catch (IOException ignored) {
            // 进程可能已先关闭管道。
        }
        process.destroy();
        try {
            if (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}
