package com.javaclaw.protocol;

import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.AgentRoleFileFormat;
import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.ProviderRef;

/** Role 文件预览、确认导入和导出契约；文件内容永远不能授予执行权限。 */
public final class AgentRoleFileRpcContracts {
    private AgentRoleFileRpcContracts() {}

    /**
     * 创建待确认文件预览。
     *
     * @param roleId 导入目标稳定标识
     * @param content UTF-8 文本；编码后最多 1 MiB，服务端验证所有字段
     * @param format 明确的兼容或无损模式
     */
    public record PreviewPayload(String roleId, String content, AgentRoleFileFormat format) {
        /** 校验目标与必填内容。 */
        public PreviewPayload {
            roleId = new AgentRoleRef(roleId, 1).id();
            Objects.requireNonNull(content, "content");
            Objects.requireNonNull(format, "format");
        }
    }

    /**
     * 提交用户已确认的不可变预览。
     *
     * @param previewId 服务端预览标识；不可由客户端替换预览定义
     * @param modelMapping 模型无法唯一映射时的显式选择；否则为空
     */
    public record CommitPayload(String previewId, Optional<ProviderRef> modelMapping) {
        /** 校验预览标识和可选模型。 */
        public CommitPayload {
            previewId = Objects.requireNonNull(previewId, "previewId").strip();
            if (previewId.isEmpty()) {
                throw new IllegalArgumentException("previewId must not be blank");
            }
            modelMapping = Objects.requireNonNull(modelMapping, "modelMapping");
        }
    }

    /**
     * 导出精确 Role 版本。
     *
     * @param role 精确角色引用
     * @param format 明确的兼容或无损模式
     */
    public record ExportPayload(AgentRoleRef role, AgentRoleFileFormat format) {
        /** 校验版本与格式。 */
        public ExportPayload {
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(format, "format");
        }
    }
}
