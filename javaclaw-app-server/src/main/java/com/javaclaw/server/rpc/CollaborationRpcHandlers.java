package com.javaclaw.server.rpc;

import java.util.Objects;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CollaborationRpcContracts;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.turn.AgentCollaborationService;

/** 子智能体 v3 RPC 映射；所有创建和取消均由共享服务执行权限、预算与幂等校验。 */
public final class CollaborationRpcHandlers {
    private final AgentCollaborationService collaboration;
    private final CanonicalJson json;

    /**
     * 创建协作方法映射。
     *
     * @param collaboration 权威子任务服务
     * @param json 规范 JSON
     */
    public CollaborationRpcHandlers(AgentCollaborationService collaboration, CanonicalJson json) {
        this.collaboration = Objects.requireNonNull(collaboration, "collaboration");
        this.json = Objects.requireNonNull(json, "json");
    }

    /**
     * 注册唯一版本的方法。
     *
     * @param routes App Server 路由构造器
     */
    public void register(RpcRouter.Builder routes) {
        routes.register("agent/spawn", this::spawn)
                .register("agent/wait", this::read)
                .register("agent/interrupt", this::interrupt);
    }

    private CanonicalPayload spawn(CanonicalPayload params) {
        WriteCommand command = json.decode(params, WriteCommand.class);
        var payload = json.decode(command.payload(), CollaborationRpcContracts.SpawnPayload.class);
        return json.encode(collaboration.spawn(CommandIdentity.from("agent/spawn", command, json), payload));
    }

    private CanonicalPayload read(CanonicalPayload params) {
        var payload = json.decode(params, CollaborationRpcContracts.WaitPayload.class);
        return json.encode(collaboration.read(payload.turnId()));
    }

    private CanonicalPayload interrupt(CanonicalPayload params) {
        WriteCommand command = json.decode(params, WriteCommand.class);
        var payload = json.decode(command.payload(), CollaborationRpcContracts.InterruptPayload.class);
        return json.encode(collaboration.interrupt(
                CommandIdentity.from("agent/interrupt", command, json), payload.turnId(), payload.reason()));
    }
}
