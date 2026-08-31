package com.javaclaw.server.extension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Installed Plugin 4.0 bundle persistence port. */
public interface PluginRepository {
    /** 列出已安装插件的持久状态，包括禁用、降级和隔离状态。 */
    List<PluginRecord> list();

    /** 按插件 id 查找安装记录；不存在返回 Optional.empty。 */
    Optional<PluginRecord> find(String pluginId);

    /** 按 ZIP 摘要查找已安装记录，避免相同内容重复登记。 */
    Optional<PluginRecord> findByBundleSha256(String bundleSha256);

    /** 保存已验证且已安装的包声明和来源/权限确认；幂等键用于重试去重，不直接解包或启动进程。 */
    PluginRecord install(PluginRecordDraft draft, String idempotencyKey);

    /** 按版本保存启停状态；进程关闭和执行权撤销由上层协调。 */
    PluginRecord setEnabled(String pluginId, boolean enabled, long expectedRevision, String idempotencyKey);

    /** 按版本保存健康状态、重启次数和脱敏错误，用于崩溃退避与 QUARANTINED 隔离。 */
    PluginRecord setState(String pluginId, State state, String lastError, int restartCount, long expectedRevision);

    /** 按版本删除安装登记；调用方先停止进程并将目录移入 Trash，不在仓库层递归删文件。 */
    boolean delete(String pluginId, long expectedRevision, String idempotencyKey);

    /** 插件运维状态；QUARANTINED 必须停止执行，不能自动当作 HEALTHY 重启。 */
    enum State {
        INSTALLED,
        HEALTHY,
        DEGRADED,
        QUARANTINED,
        REMOVING,
        DISABLED
    }

    /**
     * 已安装插件的权威元数据、独立授权结果与健康投影。
     *
     * @param id Plugin 的稳定标识
     * @param version 插件发布版本字符串
     * @param installPath 受控 v4 插件根下的安装路径，仅供服务端使用
     * @param manifestJson 已验证的 Plugin 4.0 声明 JSON
     * @param bundleSha256 安装 ZIP 的 SHA-256，用于内容验证和去重
     * @param signerKeyId 签名公钥标识；未签名时可为空
     * @param signatureVerified 签名验证结果；只证明来源，不授予执行权限
     * @param sourceConfirmed 用户是否显式确认插件来源
     * @param permissionsApproved 用户是否独立批准插件权限
     * @param enabled 是否允许新调用使用该资源；禁用不删除历史
     * @param state 插件运维状态，包含崩溃隔离状态 QUARANTINED
     * @param restartCount 插件重启计数，用于崩溃退避和隔离判断
     * @param lastError 最近的脱敏错误；无错误时可为空
     * @param revision 乐观锁版本，更新时用于检测并发修改
     * @param createdAt 记录首次写入的时间
     * @param updatedAt 最近一次持久化更新的时间
     */
    record PluginRecord(
            String id,
            String version,
            String installPath,
            String manifestJson,
            String bundleSha256,
            String signerKeyId,
            boolean signatureVerified,
            boolean sourceConfirmed,
            boolean permissionsApproved,
            boolean enabled,
            State state,
            int restartCount,
            String lastError,
            long revision,
            Instant createdAt,
            Instant updatedAt) {}

    /**
     * 完成包校验和安装后的持久登记草稿，不自动从签名推导权限。
     *
     * @param id Plugin 的稳定标识
     * @param version 插件发布版本字符串
     * @param installPath 受控 v4 插件根下的安装路径，仅供服务端使用
     * @param manifestJson 已验证的 Plugin 4.0 声明 JSON
     * @param bundleSha256 安装 ZIP 的 SHA-256，用于内容验证和去重
     * @param signerKeyId 签名公钥标识；未签名时可为空
     * @param signatureVerified 签名验证结果；只证明来源，不授予执行权限
     * @param sourceConfirmed 用户是否显式确认插件来源
     * @param permissionsApproved 用户是否独立批准插件权限
     * @param enabled 是否允许新调用使用该资源；禁用不删除历史
     */
    record PluginRecordDraft(
            String id,
            String version,
            String installPath,
            String manifestJson,
            String bundleSha256,
            String signerKeyId,
            boolean signatureVerified,
            boolean sourceConfirmed,
            boolean permissionsApproved,
            boolean enabled) {}
}
