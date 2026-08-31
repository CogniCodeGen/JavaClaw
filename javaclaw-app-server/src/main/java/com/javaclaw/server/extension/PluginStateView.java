package com.javaclaw.server.extension;

import java.time.Instant;

/**
 * Plugin lifecycle projection with no protocol dependency.
 *
 * @param id 资源或声明的稳定标识
 * @param version 插件发布版本字符串
 * @param state 持久生命周期状态
 * @param enabled 是否允许新调用使用该资源；禁用不删除历史
 * @param signatureVerified 签名验证结果；只证明来源，不授予执行权限
 * @param signerKeyId 签名公钥标识；未签名时可为空
 * @param sourceConfirmed 用户是否显式确认插件来源
 * @param permissionsApproved 用户是否独立批准插件权限
 * @param restartCount 插件重启计数，用于崩溃退避和隔离判断
 * @param lastError 最近的脱敏错误；无错误时可为空
 * @param revision 持久修订号，用于乐观锁和缓存失效
 * @param createdAt 创建时间
 * @param updatedAt 最近更新时间；尚未配置的资源可为 null
 */
public record PluginStateView(
        String id,
        String version,
        String state,
        boolean enabled,
        boolean signatureVerified,
        String signerKeyId,
        boolean sourceConfirmed,
        boolean permissionsApproved,
        int restartCount,
        String lastError,
        long revision,
        Instant createdAt,
        Instant updatedAt) {}
