package com.javaclaw.server.extension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Persistent MCP configuration; credentials are represented only by secret references. */
public interface McpRepository {
    /** 列出持久 MCP 配置和健康状态；凭据只保留 SecretStore 引用。 */
    List<McpRecord> list();

    /** 按配置 id 查找 MCP 记录；不存在返回 Optional.empty。 */
    Optional<McpRecord> find(String id);

    /** 按预期修订号保存已校验 MCP 元数据并支持幂等；不在配置 JSON 中保存凭据。 */
    McpRecord put(McpDraft draft, long expectedRevision, String idempotencyKey);

    /** 按版本保存 MCP 健康/连接状态，不改变配置入口或权限。 */
    McpRecord setState(String id, String state, long expectedRevision);

    /** 删除指定插件生成的 MCP 登记并返回数量；调用方先停止相应外部进程。 */
    int deleteForPlugin(String pluginId);

    /**
     * MCP 配置的持久修订快照，用于发现缓存和执行前权限复核。
     *
     * @param id MCP Server 的稳定标识，非空
     * @param pluginId 所属插件标识；独立 MCP 配置可为空
     * @param name 供用户识别的 Server 名称
     * @param configJson 严格校验的 MCP transport/network/auth 元数据 JSON，不含明文凭据
     * @param enabled 是否允许新调用使用该资源；禁用不删除历史
     * @param state 持久化运行状态，不代表实时进程存活探测
     * @param revision 乐观锁版本，更新时用于检测并发修改
     * @param updatedAt 最近一次持久化更新的时间
     */
    record McpRecord(
            String id,
            String pluginId,
            String name,
            String configJson,
            boolean enabled,
            String state,
            long revision,
            Instant updatedAt) {}

    /**
     * 待保存的 MCP 元数据；保存前必须校验传输、网络和认证配置。
     *
     * @param id MCP Server 的稳定标识，非空
     * @param pluginId 所属插件标识；独立 MCP 配置可为空
     * @param name 供用户识别的 Server 名称
     * @param configJson 严格校验的 MCP transport/network/auth 元数据 JSON，不含明文凭据
     * @param enabled 是否允许新调用使用该资源；禁用不删除历史
     */
    record McpDraft(String id, String pluginId, String name, String configJson, boolean enabled) {}
}
