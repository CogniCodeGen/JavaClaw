package com.javaclaw.server.rpc;

import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ExecutionRpcContracts;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.ExecutionConfigurationService;

/** 安装、Workspace 与 Thread 执行配置的薄 RPC 映射。 */
public final class ExecutionRpcHandlers {
    private final ExecutionConfigurationService configurations;
    private final CanonicalJson json;

    /**
     * 创建执行配置 handlers。
     *
     * @param configurations 独立执行配置版本化服务
     * @param json 规范 JSON codec
     */
    public ExecutionRpcHandlers(ExecutionConfigurationService configurations, CanonicalJson json) {
        this.configurations = Objects.requireNonNull(configurations, "configurations");
        this.json = Objects.requireNonNull(json, "json");
    }

    /**
     * 注册执行默认值与 Thread 覆盖方法。
     *
     * @param routes Router Builder
     */
    public void register(RpcRouter.Builder routes) {
        routes.register("execution/default/read", this::readDefault)
                .register("execution/default/update", this::updateDefault)
                .register("execution/subagent/read", this::readSubagent)
                .register("execution/subagent/update", this::updateSubagent)
                .register("thread/execution/read", this::readThread)
                .register("thread/execution/update", this::updateThread);
    }

    private CanonicalPayload readDefault(CanonicalPayload params) {
        ExecutionRpcContracts.DefaultReadPayload payload =
                json.decode(params, ExecutionRpcContracts.DefaultReadPayload.class);
        return json.encode(
                new ExecutionRpcContracts.ReadResult(configurations.find(payload.workspaceId(), Optional.empty())));
    }

    private CanonicalPayload updateDefault(CanonicalPayload params) {
        WriteCommand command = json.decode(params, WriteCommand.class);
        ExecutionRpcContracts.DefaultUpdatePayload payload =
                json.decode(command.payload(), ExecutionRpcContracts.DefaultUpdatePayload.class);
        return json.encode(configurations.update(
                CommandIdentity.from("execution/default/update", command, json),
                payload.workspaceId(),
                Optional.empty(),
                payload.execution()));
    }

    private CanonicalPayload readSubagent(CanonicalPayload params) {
        ExecutionRpcContracts.DefaultReadPayload payload =
                json.decode(params, ExecutionRpcContracts.DefaultReadPayload.class);
        return json.encode(new ExecutionRpcContracts.ReadResult(
                configurations.findSubagentDefaults(payload.workspaceId(), Optional.empty())));
    }

    private CanonicalPayload updateSubagent(CanonicalPayload params) {
        WriteCommand command = json.decode(params, WriteCommand.class);
        ExecutionRpcContracts.DefaultUpdatePayload payload =
                json.decode(command.payload(), ExecutionRpcContracts.DefaultUpdatePayload.class);
        return json.encode(configurations.updateSubagentDefaults(
                CommandIdentity.from("execution/subagent/update", command, json),
                payload.workspaceId(),
                Optional.empty(),
                payload.execution()));
    }

    private CanonicalPayload readThread(CanonicalPayload params) {
        ExecutionRpcContracts.ThreadReadPayload payload =
                json.decode(params, ExecutionRpcContracts.ThreadReadPayload.class);
        return json.encode(new ExecutionRpcContracts.ReadResult(
                configurations.find(Optional.of(payload.workspaceId()), Optional.of(payload.threadId()))));
    }

    private CanonicalPayload updateThread(CanonicalPayload params) {
        WriteCommand command = json.decode(params, WriteCommand.class);
        ExecutionRpcContracts.ThreadUpdatePayload payload =
                json.decode(command.payload(), ExecutionRpcContracts.ThreadUpdatePayload.class);
        return json.encode(configurations.update(
                CommandIdentity.from("thread/execution/update", command, json),
                Optional.of(payload.workspaceId()),
                Optional.of(payload.threadId()),
                payload.execution()));
    }
}
