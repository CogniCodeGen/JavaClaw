package com.javaclaw.desktop.settings;

import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import com.javaclaw.api.AgentRole;

/**
 * 角色冲突的只读比较快照，不推进本地保存 revision。
 *
 * @param expectedRevision 本地草稿原来基于的正数版本
 * @param draft 原始本地草稿，允许尚未通过领域校验
 * @param latest SDK 返回的最新角色；角色不在目录时为空
 */
record AgentRoleComparison(long expectedRevision, AgentRoleDraft draft, Optional<AgentRole> latest) {
    AgentRoleComparison {
        Objects.requireNonNull(draft, "draft");
        latest = Objects.requireNonNull(latest, "latest");
    }

    String text() {
        return "本地草稿 · 基于版本 " + expectedRevision + "\n" + describe(draft)
                + "\n\n服务端快照\n"
                + latest.map(role -> "版本 " + role.revision() + "\n" + describe(AgentRoleDraft.from(role)))
                        .orElse("最新目录中未找到此角色；本地草稿仍保留。");
    }

    private static String describe(AgentRoleDraft value) {
        return "标识：" + value.id() + "\n名称：" + value.name() + "\n用途：" + value.description()
                + "\n状态：" + value.lifecycle() + "\n固定模型："
                + value.provider()
                        .map(provider ->
                                provider.endpointId() + "@" + provider.endpointRevision() + " / " + provider.model())
                        .orElse("继承")
                + "\n推理：" + value.reasoning().map(Enum::name).orElse("继承")
                + "\n权限收窄：" + value.constraint() + "\n能力上限：\n" + value.capabilities()
                + "\nSkill 上限：\n" + value.skills() + "\n角色指令：\n" + value.developerInstructions()
                + "\n扩展：\n"
                + value.extensions().entrySet().stream()
                        .sorted(java.util.Map.Entry.comparingByKey())
                        .map(entry -> entry.getKey() + " = " + entry.getValue())
                        .collect(Collectors.joining("\n"));
    }
}
