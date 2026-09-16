package com.javaclaw.client.extension;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.client.CommandOptions;
import com.javaclaw.client.RpcClientConnection;
import com.javaclaw.extension.spi.ViewQueryResult;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ExtensionRpcContracts;
import com.javaclaw.protocol.ExtensionSecretRpcContracts;
import com.javaclaw.protocol.SessionKeyInfo;
import com.javaclaw.protocol.SessionSecretSealer;

/** Protocol v3 通用 Extension 目录、调用、schema 与页面 facade。 */
public final class ExtensionClient {
    private final RpcClientConnection connection;
    private final CanonicalJson json = new CanonicalJson();
    private final Optional<SessionKeyInfo> sessionKey;

    /**
     * 创建 facade。
     *
     * @param connection 已初始化连接
     */
    public ExtensionClient(RpcClientConnection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
        sessionKey = Optional.empty();
    }

    /**
     * 创建支持密封管理命令的已初始化 facade。
     *
     * @param connection 当前连接
     * @param sessionKey initialize 返回的会话公钥
     */
    public ExtensionClient(RpcClientConnection connection, SessionKeyInfo sessionKey) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.sessionKey = Optional.of(Objects.requireNonNull(sessionKey, "sessionKey"));
    }

    /**
     * 在进入 JSON-RPC 前密封秘密并调用宿主注册的扩展领域操作。
     *
     * @param call 不含秘密的领域元数据
     * @param secret 调用方拥有的短生命周期字符，返回后须清零
     * @param options 幂等身份与精确资源版本
     * @return 不含秘密的领域回执
     */
    public ExtensionRpcContracts.CallResult secretCommand(
            ExtensionRpcContracts.CallPayload call, char[] secret, CommandOptions options) {
        String purpose = ExtensionSecretRpcContracts.purpose(call, options.expectedRevision());
        var sealed = SessionSecretSealer.seal(
                sessionKey.orElseThrow(() -> new IllegalStateException("SDK 未绑定会话公钥")), purpose, secret);
        return connection.command(
                ExtensionSecretRpcContracts.METHOD,
                new ExtensionSecretRpcContracts.Payload(call, sealed),
                options,
                ExtensionRpcContracts.CallResult.class);
    }

    /**
     * 列出扩展实时状态。
     *
     * @return 扩展摘要
     */
    public List<ExtensionRpcContracts.Summary> list() {
        return connection
                .query("extension/list", Map.of(), ExtensionRpcContracts.ListResult.class)
                .extensions();
    }

    /**
     * 调用无副作用 query。
     *
     * @param call 调用参数
     * @return 规范结果
     */
    public ExtensionRpcContracts.CallResult query(ExtensionRpcContracts.CallPayload call) {
        return connection.query("extension/query", call, ExtensionRpcContracts.CallResult.class);
    }

    /**
     * 调用 ViewSchema v2 标准数据源并校验内外层 revision 一致。
     *
     * @param call 已包含 {@link com.javaclaw.extension.spi.ViewQueryRequest} payload 的调用
     * @return 标准页面数据
     */
    public ViewQueryResult viewQuery(ExtensionRpcContracts.CallPayload call) {
        ExtensionRpcContracts.CallResult result = query(call);
        ViewQueryResult view = json.decode(result.payload(), ViewQueryResult.class);
        if (result.revision() != view.revision()) {
            throw new IllegalArgumentException("view query revision does not match extension response");
        }
        return view;
    }

    /**
     * 调用幂等 command。
     *
     * @param call 调用参数
     * @param options 幂等键与资源 expected revision
     * @return 规范结果
     */
    public ExtensionRpcContracts.CallResult command(ExtensionRpcContracts.CallPayload call, CommandOptions options) {
        return connection.command("extension/command", call, options, ExtensionRpcContracts.CallResult.class);
    }

    /**
     * 读取扩展 JSON Schema。
     *
     * @param extensionId 扩展标识
     * @param schemaId schema 标识
     * @return schema 文档
     */
    public ExtensionRpcContracts.SchemaResult schema(String extensionId, String schemaId) {
        return connection.query(
                "extension/schema/read",
                new ExtensionRpcContracts.SchemaReadPayload(extensionId, schemaId),
                ExtensionRpcContracts.SchemaResult.class);
    }

    /**
     * 列出安全 ViewSchema 文档。
     *
     * @param extensionId 可选扩展过滤
     * @return 编码页面
     */
    public List<ExtensionRpcContracts.ViewDocument> views(Optional<String> extensionId) {
        return connection
                .query(
                        "extension/view/list",
                        new ExtensionRpcContracts.ViewListPayload(extensionId),
                        ExtensionRpcContracts.ViewListResult.class)
                .views();
    }
}
