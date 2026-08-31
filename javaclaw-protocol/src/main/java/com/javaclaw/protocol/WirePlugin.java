package com.javaclaw.protocol;

import java.time.Instant;

/**
 * 插件安装、来源确认、权限批准和健康状态；签名有效不等于有执行权限。
 *
 * @param id 服务端资源标识；已保存资源非空
 * @param version 组件或声明的版本字符串
 * @param state 服务端生命周期状态
 * @param enabled 是否启用；禁用不会删除历史记录
 * @param signatureVerified 签名是否验证成功；不等于权限已经批准
 * @param signerKeyId 签名公钥标识；未签名时可为空
 * @param sourceConfirmed 用户是否确认了插件来源
 * @param permissionsApproved 插件声明权限是否得到独立批准
 * @param restartCount 受监督插件的重启次数
 * @param lastError 最近一次脱敏错误；无错误时可为空
 * @param revision 资源修订号，更新时用作 expectedRevision
 * @param createdAt 创建时间；有效持久记录中非空
 * @param updatedAt 最近持久更新时间；已保存资源中非空
 */
public record WirePlugin(
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
