package com.javaclaw.server.rpc;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.extension.spi.ExtensionResponse;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ExtensionRpcContracts;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.extension.contract.ExtensionHost;

/** Protocol v3 通用 Extension 方法到内置 Host 的薄映射。 */
public final class ExtensionRpcHandlers {
    private static final Logger LOGGER = LoggerFactory.getLogger(ExtensionRpcHandlers.class);

    private final ExtensionHost extensions;
    private final CanonicalJson json;
    private final ExtensionEventHub events;

    /**
     * 创建 handlers。
     *
     * @param extensions 统一扩展 Host
     * @param json 共享 JSON codec
     * @param events Extension 状态失效通知
     */
    public ExtensionRpcHandlers(ExtensionHost extensions, CanonicalJson json, ExtensionEventHub events) {
        this.extensions = Objects.requireNonNull(extensions, "extensions");
        this.json = Objects.requireNonNull(json, "json");
        this.events = Objects.requireNonNull(events, "events");
    }

    /**
     * 注册通用扩展目录、调用、schema 与页面方法。
     *
     * @param builder Router Builder
     */
    public void register(RpcRouter.Builder builder) {
        builder.register("extension/list", this::list)
                .register("extension/query", this::query)
                .register("extension/command", this::command)
                .register("extension/schema/read", this::readSchema)
                .register("extension/view/list", this::listViews);
    }

    private CanonicalPayload list(CanonicalPayload params) {
        requireEmpty(params);
        return json.encode(new ExtensionRpcContracts.ListResult(extensions.list()));
    }

    private CanonicalPayload query(CanonicalPayload params) throws Exception {
        ExtensionRpcContracts.CallPayload call = json.decode(params, ExtensionRpcContracts.CallPayload.class);
        return result(extensions.query(call));
    }

    private CanonicalPayload command(CanonicalPayload params) throws Exception {
        WriteCommand command = json.decode(params, WriteCommand.class);
        ExtensionRpcContracts.CallPayload call =
                json.decode(command.payload(), ExtensionRpcContracts.CallPayload.class);
        ExtensionResponse response = extensions.command(call, command.idempotencyKey(), command.expectedRevision());
        publishInvalidation(call, response);
        return result(response);
    }

    private CanonicalPayload readSchema(CanonicalPayload params) {
        ExtensionRpcContracts.SchemaReadPayload request =
                json.decode(params, ExtensionRpcContracts.SchemaReadPayload.class);
        var schema = extensions.schema(request.extensionId(), request.schemaId());
        return json.encode(
                new ExtensionRpcContracts.SchemaResult(request.extensionId(), schema.schemaId(), schema.schema()));
    }

    private CanonicalPayload listViews(CanonicalPayload params) {
        ExtensionRpcContracts.ViewListPayload request =
                json.decode(params, ExtensionRpcContracts.ViewListPayload.class);
        return json.encode(new ExtensionRpcContracts.ViewListResult(extensions.views(request.extensionId())));
    }

    private CanonicalPayload result(ExtensionResponse response) {
        return json.encode(new ExtensionRpcContracts.CallResult(response.payload(), response.revision()));
    }

    private void publishInvalidation(ExtensionRpcContracts.CallPayload call, ExtensionResponse response) {
        if (response.revision() < 1) {
            LOGGER.error(
                    "Extension {} command {} returned no resource revision; invalidation was not published",
                    call.extensionId(),
                    call.operation());
            return;
        }
        events.publish(new ExtensionRpcContracts.ExtensionEvent(
                call.workspaceId(),
                call.extensionId(),
                "workspace",
                call.workspaceId().toString(),
                call.operation(),
                response.revision()));
    }

    private static void requireEmpty(CanonicalPayload params) {
        if (!"{}".equals(params.json())) {
            throw new IllegalArgumentException("params must be empty");
        }
    }
}
