package com.javaclaw.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 用户确认前的不可变 Role 导入预览，不创建或更新运行时 Role。
 *
 * @param previewId 服务器持久化的预览标识
 * @param roleId 导入目标稳定标识
 * @param spec 经过字段与安全验证的候选定义
 * @param contentDigest 规范化 UTF-8 内容 SHA-256
 * @param unresolvedModel 尚未唯一映射 Provider 的模型名称，存在时必须由用户选择
 * @param changedFields 与当前目标版本不同的字段名
 * @param format 明确选择的交换模式
 */
public record AgentRoleFilePreview(
        String previewId,
        String roleId,
        AgentRoleSpec spec,
        String contentDigest,
        Optional<String> unresolvedModel,
        List<String> changedFields,
        AgentRoleFileFormat format) {
    /** 校验预览标识、摘要和非空容器。 */
    public AgentRoleFilePreview {
        previewId = Preconditions.identifier(previewId, "previewId");
        roleId = Preconditions.identifier(roleId, "roleId");
        Objects.requireNonNull(spec, "spec");
        contentDigest = Preconditions.digest(contentDigest, "contentDigest");
        unresolvedModel = Objects.requireNonNull(unresolvedModel, "unresolvedModel");
        changedFields = List.copyOf(Objects.requireNonNull(changedFields, "changedFields"));
        Objects.requireNonNull(format, "format");
    }
}
