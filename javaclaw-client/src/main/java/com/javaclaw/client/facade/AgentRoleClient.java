package com.javaclaw.client.facade;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.AgentRole;
import com.javaclaw.api.AgentRoleFileExport;
import com.javaclaw.api.AgentRoleFileFormat;
import com.javaclaw.api.AgentRoleFilePreview;
import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.AgentRoleSpec;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.RoleLifecycle;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.client.RpcClientConnection;
import com.javaclaw.protocol.AgentRoleFileRpcContracts;
import com.javaclaw.protocol.AgentRoleRpcContracts;

/** Agent Role 生命周期与文件交换的强类型 facade。 */
public final class AgentRoleClient {
    private final RpcClientConnection connection;

    /**
     * 创建 facade。
     *
     * @param connection 已初始化连接
     */
    public AgentRoleClient(RpcClientConnection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    /** @return 每个 Agent Role 的最新版本 */
    public List<AgentRole> list() {
        return connection
                .query("agent/role/list", Map.of(), AgentRoleRpcContracts.ListResult.class)
                .roles();
    }

    /**
     * 读取精确版本。
     *
     * @param id Role 标识
     * @param revision 版本
     * @return Role
     */
    public AgentRole read(String id, long revision) {
        return connection.query(
                "agent/role/read",
                new AgentRoleRpcContracts.ReadPayload(new AgentRoleRef(id, revision)),
                AgentRole.class);
    }

    /**
     * 创建 Role。
     *
     * @param id 稳定标识
     * @param spec 完整配置
     * @param options expected revision 必须为 0
     * @return 首个版本
     */
    public AgentRole create(String id, AgentRoleSpec spec, CommandOptions options) {
        return connection.command(
                "agent/role/create", new AgentRoleRpcContracts.CreatePayload(id, spec), options, AgentRole.class);
    }

    /**
     * 更新 Role。
     *
     * @param id 稳定标识
     * @param spec 完整配置
     * @param lifecycle ACTIVE 或 DISABLED
     * @param options expected revision 必须匹配当前版本
     * @return 新版本
     */
    public AgentRole update(String id, AgentRoleSpec spec, RoleLifecycle lifecycle, CommandOptions options) {
        return connection.command(
                "agent/role/update",
                new AgentRoleRpcContracts.UpdatePayload(id, spec, lifecycle),
                options,
                AgentRole.class);
    }

    /**
     * 归档 Role。
     *
     * @param id Role 标识
     * @param options expected revision 必须匹配当前版本
     * @return 归档版本
     */
    public AgentRole archive(String id, CommandOptions options) {
        return connection.command(
                "agent/role/archive", new AgentRoleRpcContracts.ArchivePayload(id), options, AgentRole.class);
    }

    /**
     * 从精确版本复制成新的自定义 Role。
     *
     * @param source 精确来源
     * @param id 新标识
     * @param name 新名称
     * @param options 创建 revision 为 0 的幂等写入参数
     * @return 新角色首个版本
     */
    public AgentRole clone(AgentRoleRef source, String id, String name, CommandOptions options) {
        return connection.command(
                "agent/role/clone", new AgentRoleRpcContracts.ClonePayload(source, id, name), options, AgentRole.class);
    }

    /**
     * 验证文件并创建待确认预览；不会修改 Role。
     *
     * @param roleId 目标稳定标识
     * @param content 文件 UTF-8 文本，编码后最多 1 MiB
     * @param format 明确的兼容或无损模式
     * @return 字段差异、内容摘要及需要用户选择的模型
     */
    public AgentRoleFilePreview importPreview(String roleId, String content, AgentRoleFileFormat format) {
        return connection.query(
                "agent/role/import/preview",
                new AgentRoleFileRpcContracts.PreviewPayload(roleId, content, format),
                AgentRoleFilePreview.class);
    }

    /**
     * 将用户已确认的预览提交为新的不可变版本。
     *
     * @param previewId 服务端预览标识
     * @param modelMapping 模型不唯一时的显式选择
     * @param options 创建时 revision 为 0，更新时为目标 Role 当前 revision
     * @return 已提交 Role
     */
    public AgentRole importCommit(String previewId, Optional<ProviderRef> modelMapping, CommandOptions options) {
        return connection.command(
                "agent/role/import/commit",
                new AgentRoleFileRpcContracts.CommitPayload(previewId, modelMapping),
                options,
                AgentRole.class);
    }

    /**
     * 导出精确版本；客户端自行选择保存位置，服务端不接受任意输出路径。
     *
     * @param role 精确 Role 引用
     * @param format 明确的兼容或无损模式
     * @return 建议文件名、内容和摘要
     */
    public AgentRoleFileExport export(AgentRoleRef role, AgentRoleFileFormat format) {
        return connection.query(
                "agent/role/export",
                new AgentRoleFileRpcContracts.ExportPayload(role, format),
                AgentRoleFileExport.class);
    }
}
