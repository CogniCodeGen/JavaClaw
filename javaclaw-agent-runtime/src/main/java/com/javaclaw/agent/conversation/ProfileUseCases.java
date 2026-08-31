package com.javaclaw.agent.conversation;

import java.util.List;

import com.javaclaw.core.api.ApprovalPolicy;
import com.javaclaw.core.api.ExecutionProfile;
import com.javaclaw.core.api.ResolvedTurnConfig;
import com.javaclaw.core.api.Workspace;

/** Versioned Profile administration and Turn resolution boundary. */
public interface ProfileUseCases {
    /** 列出已保存的版本化 Profile；不返回模型凭据。 */
    List<ExecutionProfile> list();

    /** 读取指定 Profile；不存在时抛出 NoSuchElementException。 */
    ExecutionProfile read(String id);

    /** 校验 PLAN 只读且拒绝将 HOST_FULL_ACCESS 保存为 Profile 默认权限，再按版本写入。 */
    ExecutionProfile put(ProfileRepository.ProfileDraft draft, long expectedRevision, String idempotencyKey);

    /** 按版本删除 Profile；返回是否完成删除，已开始 Turn 的配置快照不被改写。 */
    boolean delete(String id, long expectedRevision, String idempotencyKey);

    /** 按 Profile 和 Workspace 生成固定 TurnConfig，追加 .git/.javaclaw 保护根并禁用原始网络；null 审批/推理选项使用默认值。 */
    ResolvedTurnConfig resolve(
            String profileId, Workspace workspace, ApprovalPolicy approvalPolicy, String reasoningEffort);
}
