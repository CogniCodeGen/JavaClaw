package com.javaclaw.server.persistence;

import java.util.Objects;

import com.javaclaw.api.AgentProfileRef;
import com.javaclaw.api.InstructionResolution;
import com.javaclaw.api.ProviderRef;

/**
 * Turn 创建时冻结并持久化的完整 system prompt 来源与正文。
 *
 * <p>该类型只允许 Turn 执行链读取；Diagnostics、管理 RPC 和日志不得返回 {@code systemInstruction}。
 *
 * @param coreInstructionRevision 内置系统说明版本
 * @param profile 精确 Agent Profile 版本
 * @param provider 精确 Provider/model 版本
 * @param instructions 项目约定脱敏来源清单
 * @param systemInstruction 最终交给模型的完整 system prompt
 */
public record TurnPromptSnapshot(
        String coreInstructionRevision,
        AgentProfileRef profile,
        ProviderRef provider,
        InstructionResolution instructions,
        String systemInstruction) {
    /** 校验来源身份和正文。 */
    public TurnPromptSnapshot {
        coreInstructionRevision = text(coreInstructionRevision, "coreInstructionRevision");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(instructions, "instructions");
        systemInstruction = text(systemInstruction, "systemInstruction");
    }

    private static String text(String value, String name) {
        String checked = Objects.requireNonNull(value, name).strip();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return checked;
    }
}
