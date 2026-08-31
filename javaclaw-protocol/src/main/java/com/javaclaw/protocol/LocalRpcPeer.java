package com.javaclaw.protocol;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 专用辅助进程使用的有界双向 JSONL 通道；请求读取与响应匹配分离，反向 Broker 调用不会锁死前向请求。 */
public final class LocalRpcPeer implements AutoCloseable {
    private static final int MAX_FRAME = 12 * 1024 * 1024;
    private static final int MAX_QUEUED_BYTES = 16 * 1024 * 1024;
    private final BufferedReader input;
    private final BufferedWriter output;
    private final ObjectMapper json = new JsonRpcCodec().mapper();
    private final ArrayBlockingQueue<Received> requests = new ArrayBlockingQueue<>(16);
    private final ArrayBlockingQueue<Encoded> outbound = new ArrayBlockingQueue<>(16);
    private final Map<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final Set<String> inboundIds = ConcurrentHashMap.newKeySet();
    private final Semaphore calls = new Semaphore(16);
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong receivedBytes = new AtomicLong();
    private final AtomicLong queuedBytes = new AtomicLong();
    private final AtomicBoolean receiving = new AtomicBoolean(true);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object sendLock = new Object();
    private final Thread reader;
    private final Thread writer;

    private record Encoded(String text, int bytes) {}

    private record Received(Request request, int bytes) {}

    /**
     * 固定一条请求的标识与参数；请求正文只存于有界内存队列。
     *
     * @param id 对端请求标识
     * @param method 非空方法名
     * @param params 参数对象
     */
    public record Request(JsonNode id, String method, JsonNode params) {}

    /** 对端已明确拒绝单次请求；这是已知结果，不要求销毁仍健康的管道，也不能自动重试副作用。 */
    public static final class RejectedException extends IOException {
        /** 创建不含对端原始错误正文的拒绝结果，避免错误消息泄露请求凭据。 */
        public RejectedException(int code) {
            super("auxiliary request rejected (" + code + ")");
        }
    }

    /** 接管流并启动虚拟线程读取；close 关闭流、拒绝新请求并唤醒未完成调用。 */
    public LocalRpcPeer(Reader input, Writer output) {
        this.input = new BufferedReader(java.util.Objects.requireNonNull(input));
        this.output = new BufferedWriter(java.util.Objects.requireNonNull(output));
        writer = Thread.ofVirtual().name("javaclaw-local-rpc-writer").start(this::writeFrames);
        reader = Thread.ofVirtual().name("javaclaw-local-rpc-reader").start(this::receive);
    }

    /** 发起有界同步交换；超时/取消会关闭整个专用通道，防止未知副作用被自动重试。 */
    public JsonNode call(String method, JsonNode params, Duration timeout) throws Exception {
        if (timeout == null
                || timeout.isNegative()
                || timeout.isZero()
                || method == null
                || method.isBlank()
                || method.length() > 128
                || params == null
                || !params.isObject()) {
            throw new IllegalArgumentException("invalid local RPC request");
        }
        if (closed.get() || !receiving.get() || !calls.tryAcquire()) {
            throw new IOException("local RPC capacity unavailable");
        }
        String id = "request_" + sequence.incrementAndGet();
        var future = new CompletableFuture<JsonNode>();
        pending.put(id, future);
        try {
            var request =
                    json.createObjectNode().put("jsonrpc", "2.0").put("id", id).put("method", method);
            request.set("params", params);
            send(request);
            return future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof RejectedException rejected) {
                throw rejected;
            }
            close();
            throw failure;
        } catch (Exception failure) {
            close();
            throw failure;
        } finally {
            pending.remove(id);
            calls.release();
        }
    }

    /** 读取一条待处理请求；超时返回 null，调用方可用该间隔驱动浏览器事件循环。 */
    public Request next(Duration timeout) throws InterruptedException {
        Received received = requests.poll(Math.max(1, timeout.toNanos()), TimeUnit.NANOSECONDS);
        if (received == null) {
            return null;
        }
        receivedBytes.addAndGet(-received.bytes());
        return received.request();
    }

    /** 是否仍有输入或已经排队的请求；EOF 不丢弃此前接收的命令。 */
    public boolean isOpen() {
        return !closed.get() && (receiving.get() || !requests.isEmpty());
    }

    /** 回复请求结果；结果不能包含超出通道上限的正文。 */
    public void respond(Request request, JsonNode result) throws IOException {
        claimResponse(request);
        var response = json.createObjectNode().put("jsonrpc", "2.0");
        response.set("id", request.id());
        response.set("result", result);
        send(response);
    }

    /** 回复不含原始异常、URL 或凭据的错误码；业务方只传固定安全说明。 */
    public void reject(Request request, int code, String message) throws IOException {
        claimResponse(request);
        var response = json.createObjectNode().put("jsonrpc", "2.0");
        response.set("id", request.id());
        response.putObject("error").put("code", code).put("message", message);
        send(response);
    }

    private void send(JsonNode frame) throws IOException {
        String encoded = json.writeValueAsString(frame);
        int bytes = encoded.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_FRAME) {
            throw new IOException("local RPC frame exceeds limit");
        }
        // 业务线程只入队。写满管道不能使 call 停在 send 中、绕过请求超时或取消。
        synchronized (sendLock) {
            if (closed.get() || queuedBytes.get() + bytes > MAX_QUEUED_BYTES || outbound.remainingCapacity() == 0) {
                throw new IOException("local RPC output capacity unavailable");
            }
            queuedBytes.addAndGet(bytes);
            outbound.add(new Encoded(encoded, bytes));
        }
    }

    private void claimResponse(Request request) throws IOException {
        if (request == null || !inboundIds.remove(request.id().toString())) {
            throw new IOException("local RPC response was not requested or already sent");
        }
    }

    private void writeFrames() {
        try {
            while (!closed.get() || !outbound.isEmpty()) {
                Encoded frame = outbound.poll(50, TimeUnit.MILLISECONDS);
                if (frame == null) {
                    continue;
                }
                queuedBytes.addAndGet(-frame.bytes());
                output.write(frame.text());
                output.newLine();
                output.flush();
            }
        } catch (Exception failure) {
            closed.set(true);
            failPending("local RPC writer disconnected");
        } finally {
            // 只有 writer 拥有输出流的关闭权；调用线程不会等待阻塞 Writer 的内部锁。
            try {
                output.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void receive() {
        try {
            while (!closed.get()) {
                String line = readLine();
                if (line == null) {
                    break;
                }
                var frame = json.readTree(line);
                if (frame == null
                        || !frame.isObject()
                        || !"2.0".equals(frame.path("jsonrpc").asText())
                        || !frame.hasNonNull("id")
                        || !(frame.get("id").isTextual() || frame.get("id").isIntegralNumber())
                        || frame.path("id").asText().isBlank()
                        || frame.path("id").asText().length() > 128) {
                    throw new IOException("invalid local RPC envelope");
                }
                if (frame.has("method")) {
                    int bytes = line.getBytes(StandardCharsets.UTF_8).length;
                    if (!frame.path("method").isTextual()
                            || frame.path("method").asText().isBlank()
                            || frame.path("method").asText().length() > 128
                            || frame.has("result")
                            || frame.has("error")
                            || !frame.path("params").isObject()
                            || inboundIds.size() >= 16
                            || !inboundIds.add(frame.get("id").toString())
                            || receivedBytes.addAndGet(bytes) > MAX_QUEUED_BYTES
                            || !requests.offer(new Received(
                                    new Request(
                                            frame.get("id"),
                                            frame.path("method").asText(),
                                            frame.get("params")),
                                    bytes))) {
                        throw new IOException("invalid or overflowing local RPC request");
                    }
                } else {
                    var completion = pending.get(frame.path("id").asText());
                    if (completion == null || frame.has("result") == frame.has("error")) {
                        throw new IOException("unsolicited local RPC response");
                    }
                    boolean accepted = frame.has("error")
                            ? completion.completeExceptionally(new RejectedException(
                                    frame.path("error").path("code").asInt(-32000)))
                            : completion.complete(frame.get("result"));
                    if (!accepted) {
                        throw new IOException("duplicate local RPC response");
                    }
                }
            }
        } catch (Exception failure) {
            // 通道异常不能回显可能包含凭据的输入行或第三方错误消息。
            closed.set(true);
            requests.clear();
            outbound.clear();
            writer.interrupt();
        } finally {
            receiving.set(false);
            failPending("local RPC peer disconnected");
        }
    }

    private String readLine() throws IOException {
        StringBuilder line = new StringBuilder();
        int bytes = 0;
        for (int value; (value = input.read()) != -1; ) {
            if (value == '\n') {
                return line.toString();
            }
            // UTF-16 surrogate pair 各占三字节是保守上界，不能用字符数放大 UTF-8 帧上限。
            bytes += value < 128 ? 1 : value < 2048 ? 2 : 3;
            if (bytes > MAX_FRAME) {
                throw new IOException("local RPC frame exceeds limit");
            }
            if (value != '\r') {
                line.append((char) value);
            }
        }
        if (!line.isEmpty()) {
            throw new IOException("unterminated local RPC frame");
        }
        return null;
    }

    @Override
    public void close() {
        closed.set(true);
        receiving.set(false);
        reader.interrupt();
        failPending("local RPC peer closed");
        requests.clear();
        inboundIds.clear();
        // EOF 后已排队的响应允许短暂排空；慢客户端最多占用这段有界关闭时间。
        try {
            if (Thread.currentThread() != writer) {
                writer.join(300);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        writer.interrupt();
        // Reader.close 可能等待仍在阻塞的底层 read；专用管道由外层 Supervisor 先关闭，不在本方法等待。
    }

    private void failPending(String message) {
        pending.values().forEach(value -> value.completeExceptionally(new IOException(message)));
    }
}
