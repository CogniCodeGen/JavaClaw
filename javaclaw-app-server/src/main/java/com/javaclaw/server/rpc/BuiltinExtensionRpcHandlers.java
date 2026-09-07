package com.javaclaw.server.rpc;

import java.util.Objects;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.protocol.BuiltinExtensionRpcContracts;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.ExtensionCatalogRepository;

/** 内置扩展状态查询与启停的薄 Protocol v3 registrar。 */
public final class BuiltinExtensionRpcHandlers {
    private final ExtensionCatalogRepository catalog;
    private final CanonicalJson json;

    /**
     * 创建 registrar。
     *
     * @param catalog 持久扩展目录
     * @param json 规范 JSON codec
     */
    public BuiltinExtensionRpcHandlers(ExtensionCatalogRepository catalog, CanonicalJson json) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.json = Objects.requireNonNull(json, "json");
    }

    /**
     * 注册强类型内置扩展管理方法。
     *
     * @param routes Router Builder
     * @return 当前 Builder
     */
    public RpcRouter.Builder register(RpcRouter.Builder routes) {
        return routes.register("extension/builtin/list", this::list)
                .register("extension/builtin/read", this::read)
                .register("extension/builtin/enable", params -> transition(params, true))
                .register("extension/builtin/disable", params -> transition(params, false));
    }

    private CanonicalPayload list(CanonicalPayload params) {
        json.decode(params, BuiltinExtensionRpcContracts.ListPayload.class);
        return json.encode(new BuiltinExtensionRpcContracts.ListResult(catalog.listBuiltIns()));
    }

    private CanonicalPayload read(CanonicalPayload params) {
        BuiltinExtensionRpcContracts.ExtensionPayload payload =
                json.decode(params, BuiltinExtensionRpcContracts.ExtensionPayload.class);
        return result(catalog.requireBuiltIn(payload.extensionId()));
    }

    private CanonicalPayload transition(CanonicalPayload params, boolean enable) {
        WriteCommand command = json.decode(params, WriteCommand.class);
        BuiltinExtensionRpcContracts.ExtensionPayload payload =
                json.decode(command.payload(), BuiltinExtensionRpcContracts.ExtensionPayload.class);
        String method = enable ? "extension/builtin/enable" : "extension/builtin/disable";
        CommandIdentity identity = CommandIdentity.from(method, command, json);
        return result(
                enable
                        ? catalog.enable(identity, payload.extensionId())
                        : catalog.disable(identity, payload.extensionId()));
    }

    private CanonicalPayload result(BuiltinExtensionRpcContracts.Status status) {
        return json.encode(new BuiltinExtensionRpcContracts.StatusResult(status));
    }
}
