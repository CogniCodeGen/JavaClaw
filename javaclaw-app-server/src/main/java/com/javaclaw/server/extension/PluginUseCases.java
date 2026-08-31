package com.javaclaw.server.extension;

import java.io.IOException;
import java.util.List;

/** Plugin and MCP configuration boundary consumed by protocol handlers. */
public interface PluginUseCases {
    /** 列出已安装插件的持久状态，包括禁用、降级和隔离状态。 */
    List<PluginStateView> list();

    /** 读取单个插件公开状态；不存在时抛出 NoSuchElementException。 */
    PluginStateView read(String id);

    /** 检查上传的 ZIP 并展示实际声明权限，不安装、不运行；损坏或不受信签名直接失败。 */
    PluginBundlePreview preview(String attachmentSha256) throws IOException;

    /** 列出持久 MCP 配置和健康状态；凭据只保留 SecretStore 引用。 */
    List<McpServerState> listMcp();

    /** 按版本保存经严格校验的 MCP 元数据；插件所属入口与权限只能由服务器生成，不接受任意客户端替换。 */
    McpServerState configureMcp(
            String id,
            String pluginId,
            String name,
            String configurationJson,
            boolean enabled,
            long expectedRevision,
            String idempotencyKey);

    /** 检查 MCP 所属插件状态并更新公开健康投影，不返回凭据。 */
    McpServerState mcpHealth(String id);

    /**
     * 从受控附件安装 Plugin ZIP，分别处理来源确认、签名和权限批准；返回持久状态。
     *
     * @throws Exception 包校验、安装或登记失败
     */
    PluginStateView install(
            String attachmentSha256,
            boolean sourceConfirmed,
            boolean permissionsApproved,
            boolean enabled,
            String idempotencyKey)
            throws IOException;

    /** 按版本切换插件可用性；禁用时停止其进程，阻止后续快照继续执行。 */
    PluginStateView setEnabled(String id, boolean enabled, long expectedRevision, String idempotencyKey)
            throws IOException;

    /**
     * 先停止进程，再将安装目录移入应用 Trash 并删除登记；不直接永久删除用户包。
     *
     * @throws Exception 版本冲突或无法安全卸载
     */
    boolean uninstall(String id, long expectedRevision, String idempotencyKey) throws IOException;

    /** 执行受监督的健康检查，记录错误和退避/隔离状态；不启动未获授权的插件。 */
    PluginStateView health(String id);

    /** 列出可信发布公钥及 metadata；签名信任与权限审批保持独立。 */
    List<PluginTrustKeyView> trustList();

    /** 解码并验证公钥后按版本加入信任仓库；encodedPublicKey 是公开材料，不是 API Key。 */
    PluginTrustKeyView trustAdd(
            String keyId, String encodedPublicKey, String label, long expectedRevision, String idempotencyKey);

    /** 按版本删除公钥信任并返回结果；后续验签不能继续依赖已移除来源。 */
    boolean trustRemove(String keyId, long expectedRevision, String idempotencyKey);
}
