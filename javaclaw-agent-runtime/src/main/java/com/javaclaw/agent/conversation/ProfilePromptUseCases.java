package com.javaclaw.agent.conversation;

import java.util.List;

import com.javaclaw.core.api.AgentTurn;
import com.javaclaw.core.api.ThreadId;
import com.javaclaw.core.api.WorkspaceId;

/** 提示词预览与显式优化任务；只能创建建议，保存始终使用 ProfileUseCases 的 revision 校验。 */
public interface ProfilePromptUseCases {
    /** 只编译构成与能力匹配，不调用模型、不发现外部服务、不改变 Profile。 */
    Preview preview(String profileId, WorkspaceId workspaceId);

    /** 在指定 Thread 创建无工具草稿 Turn；同一幂等键重放原 Turn，参数变化或过时 revision 被拒绝。 */
    AgentTurn optimize(ThreadId threadId, String profileId, String draft, long expectedRevision, String idempotencyKey);

    /**
     * 预览编辑层、固定层版本和当前已知工具；不发起外部发现。
     *
     * @param profileId Profile 标识
     * @param revision 生成预览时的 Profile 版本
     * @param editablePrompt 用户可编辑原稿，不包含固定底座
     * @param purpose 当前调用用途
     * @param layers 内置模板有序引用
     * @param tools 已知工具名称，不等于执行授权
     * @param warnings 能力未验证或不匹配的提示
     */
    record Preview(
            String profileId,
            long revision,
            String editablePrompt,
            String purpose,
            List<Layer> layers,
            List<String> tools,
            List<String> warnings) {
        /** 固定列表，确保预览不会反向修改 Profile 或模板目录。 */
        public Preview {
            layers = List.copyOf(layers);
            tools = List.copyOf(tools);
            warnings = List.copyOf(warnings);
        }
    }

    /**
     * 随代码发布且不可直接编辑的模板版本摘要。
     *
     * @param id 模板稳定标识
     * @param version 正数模板版本
     * @param sha256 正文摘要，不包含用户私密上下文
     */
    record Layer(String id, int version, String sha256) {}
}
