package com.javaclaw.sdk;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.javaclaw.sdk.model.AttachmentInfo;
import com.javaclaw.sdk.model.JsonDocument;
import com.javaclaw.sdk.model.McpAuthorizationInfo;
import com.javaclaw.sdk.model.McpServerInfo;
import com.javaclaw.sdk.model.PluginInfo;
import com.javaclaw.sdk.model.PluginTrustKeyInfo;
import com.javaclaw.sdk.model.SecretMetadata;

/** Plugin/MCP 安装、信任和凭据操作的领域客户端。签名、来源确认及权限审批保持独立，远程失败通过 Future 异常返回。 */
public final class ExtensionClient {
    private final ProtocolClient protocol;
    private final SdkProtocolMapper mapper;

    ExtensionClient(ProtocolClient protocol, SdkProtocolMapper mapper) {
        this.protocol = protocol;
        this.mapper = mapper;
    }

    /** 列出插件声明与运维状态，不加载第三方 JVM 代码。 */
    public CompletableFuture<List<PluginInfo>> listPlugins() {
        return protocol.listPlugins()
                .thenApply(values -> values.stream().map(mapper::plugin).toList());
    }

    /** 读取单个插件状态；不存在时 Future 以 RPC 错误完成。 */
    public CompletableFuture<PluginInfo> readPlugin(String id) {
        return protocol.readPlugin(id).thenApply(mapper::plugin);
    }

    /** 对已上传 ZIP 做只读审阅，返回声明权限与来源状态；批准前不安装或运行任何代码。 */
    public CompletableFuture<com.javaclaw.sdk.model.PluginPreviewInfo> previewPlugin(AttachmentInfo bundle) {
        return protocol.previewPlugin(bundle.sha256())
                .thenApply(value -> new com.javaclaw.sdk.model.PluginPreviewInfo(
                        value.sha256(),
                        value.id(),
                        value.name(),
                        value.version(),
                        value.signatureVerified(),
                        value.signerKeyId(),
                        value.requiresPermissions(),
                        value.permissions()));
    }

    /** 从已上传的 ZIP 附件安装插件；来源确认、权限批准和启用状态分别传递，服务端仍校验包和签名。 */
    public CompletableFuture<PluginInfo> installPlugin(
            AttachmentInfo bundle, boolean sourceConfirmed, boolean permissionsApproved, boolean enabled, String key) {
        return protocol.installPlugin(bundle.sha256(), sourceConfirmed, permissionsApproved, enabled, key)
                .thenApply(mapper::plugin);
    }

    /** 按版本切换插件状态；禁用由服务端撤销后续执行资格，不靠 UI 隐藏实现安全。 */
    public CompletableFuture<PluginInfo> setPluginEnabled(
            String id, boolean enabled, long expectedRevision, String key) {
        return protocol.setPluginEnabled(id, enabled, expectedRevision, key).thenApply(mapper::plugin);
    }

    /** 请求停止插件进程并将安装目录移至应用 Trash；返回卸载结果，不直接递归删除。 */
    public CompletableFuture<Boolean> uninstallPlugin(String id, long expectedRevision, String key) {
        return protocol.uninstallPlugin(id, expectedRevision, key);
    }

    /** 请求一次受监督的插件健康检查并返回更新状态。 */
    public CompletableFuture<PluginInfo> pluginHealth(String id) {
        return protocol.pluginHealth(id).thenApply(mapper::plugin);
    }

    /** 列出签名信任公钥 metadata，不返回任何私钥。 */
    public CompletableFuture<List<PluginTrustKeyInfo>> listTrustKeys() {
        return protocol.listPluginTrust()
                .thenApply(values -> values.stream().map(mapper::trust).toList());
    }

    /** 按版本登记 X.509 编码的 Ed25519 公钥；信任来源不会自动批准插件权限。 */
    public CompletableFuture<PluginTrustKeyInfo> addTrustKey(
            String id, byte[] x509PublicKey, String label, long expectedRevision, String key) {
        return protocol.addPluginTrust(id, x509PublicKey, label, expectedRevision, key)
                .thenApply(mapper::trust);
    }

    /** 按版本删除公钥信任记录；返回删除结果，不扩大其他插件权限。 */
    public CompletableFuture<Boolean> removeTrustKey(String id, long expectedRevision, String key) {
        return protocol.removePluginTrust(id, expectedRevision, key);
    }

    /** 列出 MCP 配置与状态；auth 字段仅含元数据。 */
    public CompletableFuture<List<McpServerInfo>> listMcpServers() {
        return protocol.listMcpServers()
                .thenApply(values -> values.stream().map(mapper::mcp).toList());
    }

    /** 读取供表单使用的传输与认证元数据，不读取或返回任何秘密。 */
    public CompletableFuture<com.javaclaw.sdk.model.McpSettingsInfo> readMcpSettings(String id) {
        return listMcpServers()
                .thenApply(values -> McpDocuments.read(values.stream()
                        .filter(value -> value.id().equals(id))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("MCP connection not found"))
                        .config()));
    }

    /** 保存独立 HTTPS MCP 的类型化配置；stdio 入口和权限不能由客户端覆盖。 */
    public CompletableFuture<McpServerInfo> saveMcpSettings(
            String id,
            String name,
            com.javaclaw.sdk.model.McpSettingsInfo settings,
            boolean enabled,
            long expectedRevision,
            String key) {
        return configureMcpServer(id, name, McpDocuments.write(settings), enabled, expectedRevision, key);
    }

    /** 仅切换原 MCP 配置的启用状态；服务端复核版本和插件归属，不改变原入口或权限。 */
    public CompletableFuture<McpServerInfo> setMcpEnabled(McpServerInfo original, boolean enabled, String key) {
        return configureMcpServer(original.id(), original.name(), original.config(), enabled, original.revision(), key);
    }

    /** 按版本配置 MCP transport/network/auth 元数据；插件所属入口和权限由服务端控制，不能通过 config 替换。 */
    public CompletableFuture<McpServerInfo> configureMcpServer(
            String id, String name, JsonDocument config, boolean enabled, long expectedRevision, String key) {
        return protocol.configureMcpServer(id, null, name, mapper.parse(config), enabled, expectedRevision, key)
                .thenApply(mapper::mcp);
    }

    /** 请求检查指定 MCP Server 的连接和健康状态。 */
    public CompletableFuture<McpServerInfo> mcpHealth(String id) {
        return protocol.mcpHealth(id).thenApply(mapper::mcp);
    }

    /** 请求固定协议版本的 MCP 能力发现，返回保留扩展字段的 JSON 文档。 */
    public CompletableFuture<JsonDocument> discoverMcp(String id) {
        return protocol.discoverMcp(id).thenApply(mapper::document);
    }

    /** 开始一次 MCP OAuth 授权，返回 URL、授权标识和过期时间；不返回访问或刷新 token。 */
    public CompletableFuture<McpAuthorizationInfo> startMcpAuthorization(String id) {
        return protocol.startMcpAuthorization(id)
                .thenApply(value -> new McpAuthorizationInfo(
                        value.path("authorizationId").asText(),
                        URI.create(value.path("url").asText()),
                        Instant.parse(value.path("expiresAt").asText())));
    }

    /** 取消指定一次性授权会话；返回是否存在可取消会话。 */
    public CompletableFuture<Boolean> cancelMcpAuthorization(String authorizationId) {
        return protocol.cancelMcpAuthorization(authorizationId);
    }

    /** 通过安全配置通道设置 MCP 静态凭据，响应只含 metadata；调用方应在请求完成后清空自身 char[]。 */
    public CompletableFuture<SecretMetadata> setMcpCredential(String id, char[] credential, String key) {
        return protocol.setMcpCredential(id, credential, key).thenApply(mapper::secret);
    }

    /** 查询静态凭据 metadata；未配置返回 Optional.empty，不返回 API Key 或 token。 */
    public CompletableFuture<java.util.Optional<SecretMetadata>> readMcpCredential(String id) {
        return protocol.readMcpCredential(id)
                .thenApply(value -> java.util.Optional.ofNullable(value).map(mapper::secret));
    }

    /** 按预期凭据版本清除静态凭据；key 用于去重，返回清除结果。 */
    public CompletableFuture<Boolean> clearMcpCredential(String id, long expectedRevision, String key) {
        return protocol.clearMcpCredential(id, expectedRevision, key);
    }

    /** 列出工作区站点；不返回凭据或 Cookie。 */
    public CompletableFuture<List<com.javaclaw.sdk.model.BrowserSiteInfo>> listSites(String workspaceId) {
        return protocol.listSites(workspaceId)
                .thenApply(values -> values.stream().map(mapper::site).toList());
    }

    /** 显式确认准确来源范围后保存；使用 site.revision 乐观锁，更改配置会使旧会话失效。 */
    public CompletableFuture<com.javaclaw.sdk.model.BrowserSiteInfo> saveSite(
            com.javaclaw.sdk.model.BrowserSiteInfo site, boolean confirmed, String key) {
        return protocol.putSite(site, confirmed, key).thenApply(mapper::site);
    }

    /** 按版本禁用站点并终止其全部会话。 */
    public CompletableFuture<Boolean> disableSite(String id, long revision, String key) {
        return protocol.disableSite(id, revision, key);
    }

    /** 保存站点 SecretRef；输入数组由调用方在 Future 完成后清空，返回 metadata。 */
    public CompletableFuture<SecretMetadata> setSiteCredential(String id, String name, char[] value, String key) {
        return protocol.putSiteSecret(id, name, value, key).thenApply(mapper::secret);
    }

    /** 查询凭据槽位是否配置，不读取秘密。 */
    public CompletableFuture<java.util.Optional<SecretMetadata>> readSiteCredential(String id, String name) {
        return protocol.readSiteSecret(id, name, false)
                .thenApply(value -> java.util.Optional.ofNullable(value).map(mapper::secret));
    }

    /** 按凭据版本清除槽位。 */
    public CompletableFuture<Boolean> clearSiteCredential(String id, String name, long revision, String key) {
        return protocol.clearSiteSecret(id, name, false, revision, key);
    }

    /** 打开隔离可见浏览器，由用户自行登录；confirmed 必须来自明确用户操作。 */
    public CompletableFuture<com.javaclaw.sdk.model.BrowserLoginInfo> startBrowserLogin(
            String siteId, long revision, boolean confirmed, String key) {
        return protocol.startBrowserLogin(siteId, revision, confirmed, key)
                .thenApply(value -> new com.javaclaw.sdk.model.BrowserLoginInfo(
                        value.sessionId(), value.siteId(), Instant.parse(value.expiresAt())));
    }

    /** 用户完成后保存加密会话并关闭；save=false 仅取消，不保存登录状态。 */
    public CompletableFuture<Boolean> finishBrowserLogin(String sessionId, boolean save, String key) {
        return protocol.finishBrowserLogin(sessionId, save, key);
    }

    /** 查询当前站点版本的加密登录状态 metadata，不返回原始 state。 */
    public CompletableFuture<java.util.Optional<SecretMetadata>> readBrowserSession(String siteId) {
        return protocol.readSiteSecret(siteId, null, true)
                .thenApply(value -> java.util.Optional.ofNullable(value).map(mapper::secret));
    }

    /** 清除当前版本会话并停止该站点的活动浏览器。 */
    public CompletableFuture<Boolean> clearBrowserSession(String siteId, long revision, String key) {
        return protocol.clearSiteSecret(siteId, null, true, revision, key);
    }

    /** 查询工作区已确认的准确私网端点，默认公开网络访问不产生隐式授权。 */
    public CompletableFuture<List<com.javaclaw.sdk.model.NetworkGrantInfo>> listNetworkGrants(String workspaceId) {
        return protocol.listNetworkGrants(workspaceId)
                .thenApply(values -> values.stream().map(mapper::grant).toList());
    }

    /** 显式批准用途、端点、IP 与到期时间；服务端拒绝元数据、本机控制端点和通配网段。 */
    public CompletableFuture<com.javaclaw.sdk.model.NetworkGrantInfo> saveNetworkGrant(
            com.javaclaw.sdk.model.NetworkGrantInfo grant, boolean confirmed, String key) {
        return protocol.putNetworkGrant(grant, confirmed, key).thenApply(mapper::grant);
    }

    /** 按版本立即撤销私网授权。 */
    public CompletableFuture<Boolean> disableNetworkGrant(String id, long revision, String key) {
        return protocol.disableNetworkGrant(id, revision, key);
    }

    /** 查询本工作区最近真实发现且版本仍有效的 MCP 工具，未发现时返回空目录。 */
    public CompletableFuture<List<com.javaclaw.sdk.model.ToolAuthorityOptionInfo>> toolAuthorizationOptions(
            String workspaceId) {
        return protocol.toolAuthorizationOptions(workspaceId)
                .thenApply(values -> values.stream()
                        .map(value -> new com.javaclaw.sdk.model.ToolAuthorityOptionInfo(
                                value.sourceId(),
                                value.toolName(),
                                value.description(),
                                value.sourceRevision(),
                                value.schemaSha256(),
                                new JsonDocument(value.inputSchemaJson())))
                        .toList());
    }

    /** 查询有限许可与剩余次数；不会读取凭据或执行工具。 */
    public CompletableFuture<List<com.javaclaw.sdk.model.ToolAuthorizationInfo>> listToolAuthorizations(
            String workspaceId) {
        return protocol.listToolAuthorizations(workspaceId)
                .thenApply(
                        values -> values.stream().map(mapper::toolAuthorization).toList());
    }

    /** 明确确认接收对象、参数范围、次数和期限后按 revision 保存；服务端仍验证实际工具版本。 */
    public CompletableFuture<com.javaclaw.sdk.model.ToolAuthorizationInfo> saveToolAuthorization(
            com.javaclaw.sdk.model.ToolAuthorizationInfo grant, boolean confirmed, String key) {
        return protocol.putToolAuthorization(grant, confirmed, key).thenApply(mapper::toolAuthorization);
    }

    /** 按版本撤销许可；不删除历史执行凭据，也不假定已发送的消息可撤回。 */
    public CompletableFuture<Boolean> disableToolAuthorization(String id, long revision, String key) {
        return protocol.disableToolAuthorization(id, revision, key);
    }
}
