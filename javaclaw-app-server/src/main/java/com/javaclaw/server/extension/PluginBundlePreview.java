package com.javaclaw.server.extension;

import java.util.List;

/**
 * 安装前的不可变 Plugin Bundle 审阅快照；没有运行插件或批准任何权限。
 *
 * @param sha256 审阅的附件摘要；安装必须使用同一附件
 * @param id 插件标识
 * @param name 展示名
 * @param version 版本
 * @param signatureVerified 是否已验证受信公钥签名
 * @param signerKeyId 公钥标识，未签名为空
 * @param requiresPermissions 是否声明工作区或网络访问
 * @param permissions 每个进程的有界权限摘要
 */
public record PluginBundlePreview(
        String sha256,
        String id,
        String name,
        String version,
        boolean signatureVerified,
        String signerKeyId,
        boolean requiresPermissions,
        List<String> permissions) {
    /** 固定权限列表，审阅之后不能通过修改客户端集合改变批准范围。 */
    public PluginBundlePreview {
        permissions = List.copyOf(permissions);
    }
}
