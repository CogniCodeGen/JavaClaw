package com.javaclaw.server.rpc;

import java.util.Objects;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.DiagnosticsRpcContracts;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.lifecycle.LauncherLifecycleService;
import com.javaclaw.server.persistence.CommandIdentity;

/** launcher 与 App Server 生命周期 Protocol v2 的薄映射。 */
public final class LauncherLifecycleRpcHandlers {
    private final LauncherLifecycleService service;
    private final CanonicalJson json;

    /**
     * 创建 handlers。
     *
     * @param service 生命周期用例
     * @param json 规范 JSON codec
     */
    public LauncherLifecycleRpcHandlers(LauncherLifecycleService service, CanonicalJson json) {
        this.service = Objects.requireNonNull(service, "service");
        this.json = Objects.requireNonNull(json, "json");
    }

    /**
     * 注册 launcher 状态与协作式停止方法。
     *
     * @param builder Router Builder
     */
    public void register(RpcRouter.Builder builder) {
        Objects.requireNonNull(builder, "builder")
                .register("diagnostics/launcher/read", this::readStatus)
                .register("diagnostics/server/stop", this::stopServer);
    }

    private CanonicalPayload readStatus(CanonicalPayload params) {
        json.decode(params, DiagnosticsRpcContracts.LauncherStatusQuery.class);
        return json.encode(service.readStatus());
    }

    private CanonicalPayload stopServer(CanonicalPayload params) {
        WriteCommand command = json.decode(params, WriteCommand.class);
        json.decode(command.payload(), DiagnosticsRpcContracts.ServerStopPayload.class);
        CommandIdentity identity = CommandIdentity.from("diagnostics/server/stop", command, json);
        return json.encode(service.stop(identity));
    }
}
