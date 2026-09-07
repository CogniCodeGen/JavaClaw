package com.javaclaw.server.rpc;

import java.util.Objects;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.PromptOptimizationRpcContracts;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.turn.PromptOptimizationService;

/** Agent Role Prompt 优化 RPC 到可恢复 Harness 用例的薄映射。 */
public final class PromptOptimizationRpcHandlers {
    private final PromptOptimizationService service;
    private final CanonicalJson json;

    /**
     * 创建 handlers。
     *
     * @param service Prompt 优化用例
     * @param json 规范 JSON codec
     */
    public PromptOptimizationRpcHandlers(PromptOptimizationService service, CanonicalJson json) {
        this.service = Objects.requireNonNull(service, "service");
        this.json = Objects.requireNonNull(json, "json");
    }

    /**
     * 注册 Prompt 优化逐方法路由。
     *
     * @param builder Router Builder
     */
    public void register(RpcRouter.Builder builder) {
        builder.register("agent/role/prompt/optimization/start", this::start)
                .register("agent/role/prompt/optimization/read", this::read)
                .register("agent/role/prompt/optimization/list", this::list)
                .register("agent/role/prompt/optimization/cancel", this::cancel)
                .register("agent/role/prompt/optimization/adopt", this::adopt);
    }

    private CanonicalPayload start(CanonicalPayload params) {
        WriteCommand command = json.decode(params, WriteCommand.class);
        PromptOptimizationRpcContracts.StartPayload payload =
                json.decode(command.payload(), PromptOptimizationRpcContracts.StartPayload.class);
        return json.encode(
                service.start(CommandIdentity.from("agent/role/prompt/optimization/start", command, json), payload));
    }

    private CanonicalPayload read(CanonicalPayload params) {
        PromptOptimizationRpcContracts.ReadPayload payload =
                json.decode(params, PromptOptimizationRpcContracts.ReadPayload.class);
        return json.encode(service.read(payload.draftId()));
    }

    private CanonicalPayload list(CanonicalPayload params) {
        PromptOptimizationRpcContracts.ListPayload payload =
                json.decode(params, PromptOptimizationRpcContracts.ListPayload.class);
        return json.encode(new PromptOptimizationRpcContracts.ListResult(service.list(payload.workspaceId())));
    }

    private CanonicalPayload cancel(CanonicalPayload params) {
        WriteCommand command = json.decode(params, WriteCommand.class);
        PromptOptimizationRpcContracts.CancelPayload payload =
                json.decode(command.payload(), PromptOptimizationRpcContracts.CancelPayload.class);
        return json.encode(service.cancel(
                CommandIdentity.from("agent/role/prompt/optimization/cancel", command, json),
                payload.draftId(),
                payload.reason()));
    }

    private CanonicalPayload adopt(CanonicalPayload params) {
        WriteCommand command = json.decode(params, WriteCommand.class);
        PromptOptimizationRpcContracts.AdoptPayload payload =
                json.decode(command.payload(), PromptOptimizationRpcContracts.AdoptPayload.class);
        return json.encode(
                service.adopt(CommandIdentity.from("agent/role/prompt/optimization/adopt", command, json), payload));
    }
}
