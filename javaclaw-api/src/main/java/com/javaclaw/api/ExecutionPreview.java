package com.javaclaw.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 下一 Turn 的轻量本地配置预览；不创建 Turn、不读取项目资料，也不请求模型。
 *
 * <p>精确版本与锁定状态仅代表本次查询快照，启动 Turn 时仍须经过服务端权威校验。
 *
 * @param role 解析出的精确 Agent 引用；范围不可用时为空
 * @param provider 解析出的精确模型引用；尚未选择或范围不可用时为空
 * @param reasoning 有效思考偏好；沿用 Provider 默认时为空，NONE 明确表示关闭
 * @param modelLocked Agent 是否固定模型
 * @param reasoningLocked Agent 是否固定思考偏好
 * @param provenance 按解析顺序记录的来源，不含 Prompt、凭据或权限正文，不可空
 * @param blockers 本地启动阻塞项；空集合表示配置具备启动条件，不保证远端请求成功
 */
public record ExecutionPreview(
        Optional<AgentRoleRef> role,
        Optional<ProviderRef> provider,
        Optional<ReasoningPreference> reasoning,
        boolean modelLocked,
        boolean reasoningLocked,
        List<ConfigurationProvenance> provenance,
        List<ExecutionBlocker> blockers) {
    /** 校验可选引用并冻结来源和阻塞列表。 */
    public ExecutionPreview {
        role = Objects.requireNonNull(role, "role");
        provider = Objects.requireNonNull(provider, "provider");
        reasoning = Objects.requireNonNull(reasoning, "reasoning");
        provenance = List.copyOf(Objects.requireNonNull(provenance, "provenance"));
        blockers = List.copyOf(Objects.requireNonNull(blockers, "blockers"));
        if (blockers.isEmpty() && (role.isEmpty() || provider.isEmpty())) {
            throw new IllegalArgumentException("就绪预览必须包含 Agent 与模型");
        }
    }

    /** @return 本次本地查询是否没有启动阻塞项；不表示已调用或验证远端模型 */
    public boolean ready() {
        return blockers.isEmpty();
    }
}
