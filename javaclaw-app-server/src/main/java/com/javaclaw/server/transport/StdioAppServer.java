package com.javaclaw.server.transport;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.Objects;
import java.util.concurrent.Flow;

import com.fasterxml.jackson.databind.node.NullNode;

import com.javaclaw.agent.runtime.AgentRuntime;
import com.javaclaw.agent.runtime.LiveItemSource;
import com.javaclaw.agent.runtime.persistence.AttachmentRepository;
import com.javaclaw.core.api.ThreadEvent;
import com.javaclaw.protocol.JsonRpcCodec;
import com.javaclaw.protocol.JsonRpcFrame;
import com.javaclaw.protocol.JsonRpcResponse;
import com.javaclaw.server.configuration.ConfigurationUseCases;
import com.javaclaw.server.discovery.ServerDiscovery;

/** Blocking JSONL transport. It owns a connection session, not the process runtime. */
public final class StdioAppServer {
    public static final int DEFAULT_MAX_FRAME_CHARS = 8 * 1024 * 1024;

    private final AgentRuntime threads;
    private final Flow.Publisher<ThreadEvent> events;
    private final JsonRpcCodec codec;
    private final int maxFrameChars;
    private final ApprovalResponseHandler approvalResponses;
    private final UserInputResponseHandler userInputResponses;
    private final ServerDiscovery discovery;
    private final boolean localSocket;
    private final ConfigurationUseCases configuration;
    private final AttachmentRepository attachments;
    private final LiveItemSource liveItems;
    private final ServerUseCases features;

    /** 创建仅包含基础 Thread/Turn 能力的 stdio 服务；可选领域服务、附件和用户交互不在此入口装配。 */
    public static StdioAppServer minimal(AgentRuntime runtime, Flow.Publisher<ThreadEvent> events) {
        return new StdioAppServer(AppServerEndpointConfig.minimal(runtime, events, false));
    }

    /** 固定连接所需的共享依赖与帧上限；不启动 Runtime，也不取得其关闭权。 */
    public StdioAppServer(AppServerEndpointConfig config) {
        Objects.requireNonNull(config, "config");
        this.threads = config.runtime();
        this.events = config.events();
        this.codec = config.codec();
        this.maxFrameChars = config.maximumFrameCharacters();
        this.approvalResponses = config.approvals();
        this.userInputResponses = config.userInputs();
        this.discovery = config.discovery();
        this.localSocket = config.localSocket();
        this.configuration = config.configuration();
        this.attachments = config.attachments();
        this.liveItems = config.liveItems();
        this.features = config.useCases();
    }

    /**
     * 在当前线程处理有界 JSONL 输入，并为本连接创建会话与发送队列；EOF 或发送端关闭时释放订阅。慢客户端可被断开，但不能阻塞 Turn 的通知发布。
     *
     * @throws IOException 输入读取或帧大小检查失败
     */
    public void serve(Reader input, Writer output) throws IOException {
        Objects.requireNonNull(input, "input");
        try (BoundedJsonlSender sender = new BoundedJsonlSender(output, codec, () -> closeQuietly(input));
                AppServerSession session = new AppServerSession(
                        threads,
                        events,
                        codec.mapper(),
                        sender::sendNotification,
                        liveItems,
                        new DomainRpcApi(
                                threads,
                                discovery,
                                configuration,
                                attachments,
                                approvalResponses,
                                userInputResponses,
                                features,
                                codec.mapper(),
                                localSocket,
                                liveItems != LiveItemSource.EMPTY))) {
            BoundedJsonlReader lines = new BoundedJsonlReader(input, maxFrameChars);
            String line;
            while ((line = lines.readLine()) != null) {
                if (sender.isAborted()) {
                    break;
                }
                if (line.isBlank()) {
                    continue;
                }
                try {
                    JsonRpcFrame frame = codec.decode(line);
                    session.handle(frame).ifPresent(sender::sendResponse);
                } catch (BoundedJsonlSender.TransportClosedException closed) {
                    break;
                } catch (Throwable failure) {
                    try {
                        sender.sendResponse(JsonRpcResponse.failure(
                                NullNode.getInstance(),
                                com.javaclaw.protocol.JsonRpcError.PARSE_ERROR,
                                "invalid JSON-RPC frame",
                                null));
                    } catch (BoundedJsonlSender.TransportClosedException closed) {
                        break;
                    }
                }
            }
        }
    }

    private static void closeQuietly(Reader input) {
        try {
            input.close();
        } catch (IOException ignored) {
        }
    }

    private static final class BoundedJsonlReader {
        private final Reader input;
        private final int maximum;

        private BoundedJsonlReader(Reader input, int maximum) {
            this.input = input;
            this.maximum = maximum;
        }

        private String readLine() throws IOException {
            StringBuilder line = new StringBuilder(Math.min(maximum, 4096));
            int value;
            while ((value = input.read()) >= 0) {
                if (value == '\n') {
                    break;
                }
                if (value != '\r') {
                    line.append((char) value);
                }
                if (line.length() > maximum) {
                    throw new IOException("JSON-RPC frame exceeds " + maximum + " characters");
                }
            }
            return value < 0 && line.isEmpty() ? null : line.toString();
        }
    }
}
