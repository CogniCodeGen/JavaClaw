package com.javaclaw.client.facade;

import java.util.List;
import java.util.Objects;

import com.javaclaw.client.CommandOptions;
import com.javaclaw.client.RpcClientConnection;
import com.javaclaw.protocol.BuiltinExtensionRpcContracts;

/** 内置 Bundle 与 MCP 平台能力启停管理的强类型 SDK facade。 */
public final class BuiltinExtensionManagementClient {
    private final RpcClientConnection connection;

    /**
     * 创建 facade。
     *
     * @param connection 已初始化连接
     */
    public BuiltinExtensionManagementClient(RpcClientConnection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    /**
     * 列出全部内置能力。
     *
     * @return 按标识排序的实时状态
     */
    public List<BuiltinExtensionRpcContracts.Status> list() {
        return connection
                .query(
                        "extension/builtin/list",
                        new BuiltinExtensionRpcContracts.ListPayload(),
                        BuiltinExtensionRpcContracts.ListResult.class)
                .extensions();
    }

    /**
     * 读取一个内置能力。
     *
     * @param extensionId 扩展标识
     * @return 权威状态
     */
    public BuiltinExtensionRpcContracts.Status read(String extensionId) {
        return connection
                .query(
                        "extension/builtin/read",
                        new BuiltinExtensionRpcContracts.ExtensionPayload(extensionId),
                        BuiltinExtensionRpcContracts.StatusResult.class)
                .extension();
    }

    /**
     * 启用可选内置能力。
     *
     * @param extensionId 扩展标识
     * @param options 幂等键与当前状态 revision
     * @return 新状态
     */
    public BuiltinExtensionRpcContracts.Status enable(String extensionId, CommandOptions options) {
        return transition("extension/builtin/enable", extensionId, options);
    }

    /**
     * 停用可选内置能力。
     *
     * @param extensionId 扩展标识
     * @param options 幂等键与当前状态 revision
     * @return 新状态
     */
    public BuiltinExtensionRpcContracts.Status disable(String extensionId, CommandOptions options) {
        return transition("extension/builtin/disable", extensionId, options);
    }

    private BuiltinExtensionRpcContracts.Status transition(String method, String extensionId, CommandOptions options) {
        return connection
                .command(
                        method,
                        new BuiltinExtensionRpcContracts.ExtensionPayload(extensionId),
                        options,
                        BuiltinExtensionRpcContracts.StatusResult.class)
                .extension();
    }
}
