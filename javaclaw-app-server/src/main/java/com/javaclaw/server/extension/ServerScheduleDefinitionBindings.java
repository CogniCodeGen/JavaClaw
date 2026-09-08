package com.javaclaw.server.extension;

import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.ScheduleDefinitionBindingPort;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ExtensionRpcContracts;

/** 将运行端口的真实扩展身份绑定到内部 Schedule 路由，公共 RPC 不能提交此路由。 */
public final class ServerScheduleDefinitionBindings {
    private final CanonicalJson json;
    private volatile BuiltinExtensionHost host;

    /**
     * 创建待装配路由；Host 发布完成后、恢复 Job 之前必须调用 bindHost。
     *
     * @param json 规范 payload codec
     */
    public ServerScheduleDefinitionBindings(CanonicalJson json) {
        this.json = Objects.requireNonNull(json, "json");
    }

    /**
     * 一次性完成组合根的循环端口装配，不允许替换已发布的 Host。
     *
     * @param value 已完成贡献注册的 Host
     */
    public synchronized void bindHost(BuiltinExtensionHost value) {
        if (host != null) {
            throw new IllegalStateException("Schedule binding host is already attached");
        }
        host = Objects.requireNonNull(value, "value");
    }

    /**
     * 生成只能代表指定内置扩展的端口；调用参数中的 owner 不能提升身份。
     *
     * @param owner 注册描述符提供的身份
     * @return 具有固定身份的端口
     */
    public ScheduleDefinitionBindingPort forOwner(ExtensionId owner) {
        ExtensionId boundOwner = Objects.requireNonNull(owner, "owner");
        return new ScheduleDefinitionBindingPort() {
            @Override
            public Binding read(ExtensionId claimed, WorkspaceId workspaceId, String definitionId) throws Exception {
                requireOwner(boundOwner, claimed);
                var payload = json.encode(new Lookup(boundOwner, definitionId));
                return json.decode(
                        requireHost()
                                .scheduleBinding(call(workspaceId, "binding/read", payload), Optional.empty())
                                .payload(),
                        Binding.class);
            }

            @Override
            public Binding bind(
                    ExtensionId claimed, WorkspaceId workspaceId, Change change, CancellationToken cancellation)
                    throws Exception {
                requireOwner(boundOwner, claimed);
                cancellation.throwIfCancelled();
                BuiltinExtensionHost target = requireHost();
                boolean contributed = target.definitions(workspaceId).stream()
                        .anyMatch(definition -> definition.extensionId().equals(boundOwner.value())
                                && definition.definitionId().equals(change.definitionId())
                                && definition.revision() == change.definitionRevision());
                if (!contributed) {
                    throw new SecurityException("Schedule binding requires a current owned Definition");
                }
                var payload = json.encode(new Apply(boundOwner, change));
                return json.decode(
                        target.scheduleBinding(
                                        call(workspaceId, "binding/apply", payload),
                                        Optional.of(change.idempotencyKey()))
                                .payload(),
                        Binding.class);
            }
        };
    }

    static void requirePublicOperation(ExtensionRpcContracts.CallPayload call) {
        if (call.extensionId().equals(com.javaclaw.builtin.contracts.BuiltinExtensionIds.SCHEDULE)
                && (call.operation().equals("binding/read") || call.operation().equals("binding/apply"))) {
            throw new SecurityException("Schedule binding is an internal platform operation");
        }
    }

    private BuiltinExtensionHost requireHost() {
        return Objects.requireNonNull(host, "Schedule binding host has not been attached");
    }

    private static void requireOwner(ExtensionId boundOwner, ExtensionId claimed) {
        if (!boundOwner.equals(claimed)) {
            throw new SecurityException("Schedule binding owner cannot be changed");
        }
    }

    private static ExtensionRpcContracts.CallPayload call(
            WorkspaceId workspaceId, String operation, com.javaclaw.api.CanonicalPayload payload) {
        return new ExtensionRpcContracts.CallPayload(
                com.javaclaw.builtin.contracts.BuiltinExtensionIds.SCHEDULE,
                workspaceId,
                Optional.empty(),
                Optional.empty(),
                operation,
                payload);
    }
}
