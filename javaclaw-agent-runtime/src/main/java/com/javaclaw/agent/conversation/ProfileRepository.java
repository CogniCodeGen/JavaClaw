package com.javaclaw.agent.conversation;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.core.api.ExecutionProfile;
import com.javaclaw.core.api.ProfileKind;
import com.javaclaw.sandbox.api.SandboxMode;

/** Feature-owned persistence contract implemented by infrastructure modules. */
public interface ProfileRepository {
    /** 列出已保存的版本化 Profile；不返回模型凭据。 */
    List<ExecutionProfile> list();

    /** 按标识查找 Profile；不存在返回 Optional.empty。 */
    Optional<ExecutionProfile> find(String id);

    /** 创建或按预期修订号保存 Profile，幂等键用于去重；返回新版本的完整定义。 */
    ExecutionProfile put(ProfileDraft draft, long expectedRevision, String idempotencyKey);

    /** 按版本删除 Profile；返回是否完成删除，已开始 Turn 的配置快照不被改写。 */
    boolean delete(String id, long expectedRevision, String idempotencyKey);

    /**
     * Profile 保存草稿；集合在构造时复制，权限和预算由服务/持久层校验。
     *
     * @param id 资源标识；创建草稿允许为空由 Repository 分配，已保存记录非空
     * @param name 展示名称；保存时要求非空白
     * @param kind 执行场景，保存时要求非空
     * @param provider 云模型 Provider 标识，保存时要求非空白
     * @param model 模型标识，保存时要求非空白
     * @param systemPrompt 系统提示词；null 归一为空字符串
     * @param enabledTools 工具名快照；null 归一为空集合
     * @param requestedSandboxMode 请求的权限模式；PLAN 必须只读，持久 Profile 不允许 HOST_FULL_ACCESS
     * @param maxIterations 迭代次数预算，保存时限定 1 到 1000
     * @param maxModelCalls 模型调用次数预算，保存时限定 1 到 1000
     * @param attributes 扩展属性；null 归一为空 Map，不应携带凭据
     */
    record ProfileDraft(
            String id,
            String name,
            ProfileKind kind,
            String provider,
            String model,
            String systemPrompt,
            Set<String> enabledTools,
            SandboxMode requestedSandboxMode,
            int maxIterations,
            int maxModelCalls,
            Map<String, String> attributes) {
        /** 归一提示词并复制工具/属性集合；不绕过保存阶段的 Profile 权限校验。 */
        public ProfileDraft {
            systemPrompt = systemPrompt == null ? "" : systemPrompt;
            enabledTools = enabledTools == null ? Set.of() : Set.copyOf(enabledTools);
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }
}
