package com.javaclaw.server.rpc;

import java.util.Objects;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.protocol.AgentRoleRpcContracts;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.persistence.AgentRoleService;
import com.javaclaw.server.persistence.CommandIdentity;

/** Agent Role 版本化用例的薄 RPC 映射；幂等与不可变内置版本由服务端事务验证。 */
public final class AgentRoleRpcHandlers {
    private final AgentRoleService roles;
    private final CanonicalJson json;

    /**
     * 创建 Role handlers。
     *
     * @param roles Role 版本化服务
     * @param json 规范 JSON codec
     */
    public AgentRoleRpcHandlers(AgentRoleService roles, CanonicalJson json) {
        this.roles = Objects.requireNonNull(roles, "roles");
        this.json = Objects.requireNonNull(json, "json");
    }

    /**
     * 注册已实现的 Role 生命周期方法。
     *
     * @param routes Router Builder
     */
    public void register(RpcRouter.Builder routes) {
        routes.register("agent/role/list", this::list)
                .register("agent/role/read", this::read)
                .register("agent/role/create", this::create)
                .register("agent/role/update", this::update)
                .register("agent/role/archive", this::archive)
                .register("agent/role/clone", this::cloneRole);
    }

    private CanonicalPayload list(CanonicalPayload params) {
        if (!"{}".equals(params.json())) {
            throw new IllegalArgumentException("params must be empty");
        }
        return json.encode(new AgentRoleRpcContracts.ListResult(roles.listLatest()));
    }

    private CanonicalPayload read(CanonicalPayload params) {
        AgentRoleRpcContracts.ReadPayload payload = json.decode(params, AgentRoleRpcContracts.ReadPayload.class);
        return json.encode(roles.require(payload.role().id(), payload.role().revision()));
    }

    private CanonicalPayload create(CanonicalPayload params) {
        WriteCommand command = json.decode(params, WriteCommand.class);
        AgentRoleRpcContracts.CreatePayload payload =
                json.decode(command.payload(), AgentRoleRpcContracts.CreatePayload.class);
        return json.encode(
                roles.create(CommandIdentity.from("agent/role/create", command, json), payload.id(), payload.spec()));
    }

    private CanonicalPayload update(CanonicalPayload params) {
        WriteCommand command = json.decode(params, WriteCommand.class);
        AgentRoleRpcContracts.UpdatePayload payload =
                json.decode(command.payload(), AgentRoleRpcContracts.UpdatePayload.class);
        return json.encode(roles.update(
                CommandIdentity.from("agent/role/update", command, json),
                payload.id(),
                payload.spec(),
                payload.lifecycle()));
    }

    private CanonicalPayload archive(CanonicalPayload params) {
        WriteCommand command = json.decode(params, WriteCommand.class);
        AgentRoleRpcContracts.ArchivePayload payload =
                json.decode(command.payload(), AgentRoleRpcContracts.ArchivePayload.class);
        return json.encode(roles.archive(CommandIdentity.from("agent/role/archive", command, json), payload.id()));
    }

    private CanonicalPayload cloneRole(CanonicalPayload params) {
        WriteCommand command = json.decode(params, WriteCommand.class);
        AgentRoleRpcContracts.ClonePayload payload =
                json.decode(command.payload(), AgentRoleRpcContracts.ClonePayload.class);
        return json.encode(roles.clone(
                CommandIdentity.from("agent/role/clone", command, json),
                payload.source(),
                payload.id(),
                payload.name()));
    }
}
