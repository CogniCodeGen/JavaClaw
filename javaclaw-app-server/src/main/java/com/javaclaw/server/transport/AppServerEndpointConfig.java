package com.javaclaw.server.transport;

import java.util.Objects;
import java.util.concurrent.Flow;

import com.javaclaw.agent.runtime.AgentRuntime;
import com.javaclaw.agent.runtime.LiveItemSource;
import com.javaclaw.agent.runtime.persistence.AttachmentRepository;
import com.javaclaw.core.api.ThreadEvent;
import com.javaclaw.protocol.JsonRpcCodec;
import com.javaclaw.server.configuration.ConfigurationUseCases;
import com.javaclaw.server.configuration.ServerConfiguration;
import com.javaclaw.server.discovery.ServerDiscovery;

/**
 * Immutable transport-to-session wiring; it contains no process bootstrap behavior.
 *
 * @param runtime 共享的 Agent Runtime；连接关闭不负责关闭 Runtime，非空
 * @param events 持久事件发布源；为 null 时不建立事件订阅
 * @param codec 连接使用的 JSON-RPC 编解码器，非空
 * @param maximumFrameCharacters 单帧字符上限，必须为正数；不是 UTF-8 字节上限
 * @param approvals 审批回复入口；为 null 时不提供该交互能力
 * @param userInputs 用户输入回复入口；为 null 时不提供该交互能力
 * @param discovery 模型、工具及扩展能力发现入口，非空
 * @param localSocket 是否向客户端声明本地域套接字传输能力
 * @param configuration 服务端配置用例，非空
 * @param attachments 附件存储端口；为 null 时附件方法不可用
 * @param liveItems 活动 Item 内存流；无流式能力时使用 EMPTY，非空
 * @param useCases 领域用例集合；null 归一化为不提供可选功能的 EMPTY
 */
public record AppServerEndpointConfig(
        AgentRuntime runtime,
        Flow.Publisher<ThreadEvent> events,
        JsonRpcCodec codec,
        int maximumFrameCharacters,
        ApprovalResponseHandler approvals,
        UserInputResponseHandler userInputs,
        ServerDiscovery discovery,
        boolean localSocket,
        ConfigurationUseCases configuration,
        AttachmentRepository attachments,
        LiveItemSource liveItems,
        ServerUseCases useCases) {
    /** 校验共享依赖和正数帧上限，归一化可选领域用例；不启动连接或接管 Runtime 生命周期。 */
    public AppServerEndpointConfig {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(codec, "codec");
        if (maximumFrameCharacters < 1) {
            throw new IllegalArgumentException("maximumFrameCharacters must be positive");
        }
        discovery = Objects.requireNonNull(discovery, "discovery");
        configuration = Objects.requireNonNull(configuration, "configuration");
        liveItems = Objects.requireNonNull(liveItems, "liveItems");
        useCases = useCases == null ? ServerUseCases.EMPTY : useCases;
    }

    static AppServerEndpointConfig minimal(
            AgentRuntime runtime, Flow.Publisher<ThreadEvent> events, boolean localSocket) {
        JsonRpcCodec codec = new JsonRpcCodec();
        return new AppServerEndpointConfig(
                runtime,
                events,
                codec,
                StdioAppServer.DEFAULT_MAX_FRAME_CHARS,
                null,
                null,
                ServerDiscovery.EMPTY,
                localSocket,
                ServerConfiguration.inMemory(codec.mapper()),
                null,
                LiveItemSource.EMPTY,
                ServerUseCases.EMPTY);
    }

    AppServerEndpointConfig asLocalSocket() {
        return new AppServerEndpointConfig(
                runtime,
                events,
                codec,
                maximumFrameCharacters,
                approvals,
                userInputs,
                discovery,
                true,
                configuration,
                attachments,
                liveItems,
                useCases);
    }
}
