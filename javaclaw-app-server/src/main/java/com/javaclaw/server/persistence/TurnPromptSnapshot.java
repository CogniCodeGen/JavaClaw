package com.javaclaw.server.persistence;

import java.util.List;
import java.util.Objects;

import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.InstructionResolution;
import com.javaclaw.api.PromptSourceMetadata;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.runtime.ModelInstructions;

/**
 * Turn 创建事务冻结的完整 Prompt；仅执行链读取正文，管理接口只返回来源元数据。
 *
 * @param coreInstructionRevision 平台发行版本
 * @param role 精确 Role 版本
 * @param provider 精确 Provider/model
 * @param instructions 项目约定来源快照
 * @param sources 前五层按拼接顺序排列的来源、revision 与摘要
 * @param modelInstructions 分层模型输入；不允许被外部上下文覆盖
 */
public record TurnPromptSnapshot(
        String coreInstructionRevision,
        AgentRoleRef role,
        ProviderRef provider,
        InstructionResolution instructions,
        List<PromptSourceMetadata> sources,
        ModelInstructions modelInstructions) {
    /** 校验来源与正文并冻结来源集合。 */
    public TurnPromptSnapshot {
        Objects.requireNonNull(coreInstructionRevision, "coreInstructionRevision");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(instructions, "instructions");
        sources = List.copyOf(sources);
        Objects.requireNonNull(modelInstructions, "modelInstructions");
    }
}
