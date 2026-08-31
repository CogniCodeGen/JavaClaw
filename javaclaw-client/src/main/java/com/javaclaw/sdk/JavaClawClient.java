package com.javaclaw.sdk;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import com.javaclaw.sdk.model.ServerInfo;

/**
 * Public SDK aggregate. Its API is domain-oriented and contains only JDK or SDK types; JSON-RPC, Jackson and Wire DTOs
 * stay behind the package-private protocol adapter.
 */
public final class JavaClawClient implements AutoCloseable {
    private final ProtocolClient protocol;
    private final SdkProtocolMapper mapper;
    private final WorkspaceClient workspaces;
    private final ThreadClient threads;
    private final ModelClient models;
    private final AutomationClient automations;
    private final KnowledgeClient knowledge;
    private final ExtensionClient extensions;
    private final AttachmentClient attachments;
    private final AdministrationClient administration;

    JavaClawClient(RpcConnection connection) {
        Objects.requireNonNull(connection, "connection");
        protocol = new ProtocolClient(connection);
        mapper = new SdkProtocolMapper(connection.codec().mapper());
        workspaces = new WorkspaceClient(protocol, mapper);
        threads = new ThreadClient(protocol, mapper);
        models = new ModelClient(protocol, mapper);
        automations = new AutomationClient(protocol, mapper);
        knowledge = new KnowledgeClient(protocol, mapper);
        extensions = new ExtensionClient(protocol, mapper);
        attachments = new AttachmentClient(protocol, mapper);
        administration = new AdministrationClient(protocol, mapper);
    }

    /** JDK-only transport entry point used by local applications and black-box tests. */
    public static JavaClawClient connect(InputStream input, OutputStream output) {
        return new JavaClawClient(new JsonRpcConnection(input, output));
    }

    /** 返回共享此连接的工作区领域客户端，不创建第二条连接。 */
    public WorkspaceClient workspaces() {
        return workspaces;
    }

    /** 返回共享此连接的 Thread/Turn/Item 领域客户端。 */
    public ThreadClient threads() {
        return threads;
    }

    /** 返回 Profile、Provider 和模型凭据管理客户端。 */
    public ModelClient models() {
        return models;
    }

    /** 返回自动化和 Schedule 领域客户端，仍使用统一服务端 Runtime。 */
    public AutomationClient automations() {
        return automations;
    }

    /** 返回 Memory、Knowledge 和 Skill 领域客户端。 */
    public KnowledgeClient knowledge() {
        return knowledge;
    }

    /** 返回 Plugin、信任公钥及 MCP 管理客户端。 */
    public ExtensionClient extensions() {
        return extensions;
    }

    /** 返回有界附件传输客户端，本地文件路径不进入公共协议。 */
    public AttachmentClient attachments() {
        return attachments;
    }

    /** 返回脱敏配置和诊断管理客户端。 */
    public AdministrationClient administration() {
        return administration;
    }

    /** 发送初始化请求并确认 initialized；成功 Future 返回协商能力，版本不匹配以异常完成。 */
    public CompletableFuture<ServerInfo> initialize(String clientName, String version) {
        return protocol.initialize(clientName, version).thenApply(mapper::server);
    }

    /** 注册类型化事件监听器并返回取消订阅句柄；回调可能在后台线程执行，UI 必须自行切换到 FX 线程。 */
    public AutoCloseable onNotification(Consumer<ClientNotification> listener) {
        Objects.requireNonNull(listener, "listener");
        return protocol.onNotification(value -> listener.accept(mapper.notification(value)));
    }

    /** 监听连接、退避重连、resync 和关闭状态；关闭返回句柄只移除监听，不关闭连接。 */
    public AutoCloseable onConnectionState(Consumer<ConnectionStatus> listener) {
        return protocol.onConnectionState(listener);
    }

    /** 监听自动重连恢复的快照/事件；回调应按 sequence 去重，关闭返回句柄可取消监听。 */
    public AutoCloseable onRecoveredThread(Consumer<RecoveredThread> listener) {
        Objects.requireNonNull(listener, "listener");
        return protocol.onRecoveredThread(value -> listener.accept(
                mapper.recovered(value.threadId(), value.snapshot(), value.events(), value.liveItems())));
    }

    @Override
    public void close() {
        protocol.close();
    }
}
