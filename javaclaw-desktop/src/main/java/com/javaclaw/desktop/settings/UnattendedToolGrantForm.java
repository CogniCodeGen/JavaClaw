package com.javaclaw.desktop.settings;

import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ToolIdentity;
import com.javaclaw.api.UnattendedToolGrantDraft;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.protocol.CanonicalJson;

/**
 * 无人值守 Tool Grant 的界面草稿；保留无效文本，直到用户提交时统一校验。
 *
 * @param scheduleId Schedule 定义标识
 * @param scheduleRevision Schedule revision 文本
 * @param producerId 工具来源
 * @param toolName 工具名称
 * @param toolRevision 工具 revision 文本
 * @param catalogRevision 工具目录 revision 文本
 * @param schemaHash 输入 Schema SHA-256
 * @param fixedArguments 固定参数 JSON object
 * @param variableFields 可变化的顶层字符串字段
 * @param maximumUses 最大次数文本
 * @param validityDays 有效天数文本
 */
public record UnattendedToolGrantForm(
        String scheduleId,
        String scheduleRevision,
        String producerId,
        String toolName,
        String toolRevision,
        String catalogRevision,
        String schemaHash,
        String fixedArguments,
        String variableFields,
        String maximumUses,
        String validityDays) {
    /** 归一化空值，但不提前丢弃用户输入。 */
    public UnattendedToolGrantForm {
        scheduleId = text(scheduleId);
        scheduleRevision = text(scheduleRevision);
        producerId = text(producerId);
        toolName = text(toolName);
        toolRevision = text(toolRevision);
        catalogRevision = text(catalogRevision);
        schemaHash = text(schemaHash);
        fixedArguments = text(fixedArguments);
        variableFields = text(variableFields);
        maximumUses = text(maximumUses);
        validityDays = text(validityDays);
    }

    /** @return 空白草稿及安全的最小默认限额 */
    public static UnattendedToolGrantForm empty() {
        return new UnattendedToolGrantForm("", "", "", "", "", "", "", "{}", "", "1", "1");
    }

    /** @return 是否填写了足以表达新授权意图的字段 */
    public boolean dirty() {
        return !scheduleId.isBlank()
                || !scheduleRevision.isBlank()
                || !producerId.isBlank()
                || !toolName.isBlank()
                || !toolRevision.isBlank()
                || !catalogRevision.isBlank()
                || !schemaHash.isBlank()
                || !variableFields.isBlank()
                || !"{}".equals(fixedArguments.strip())
                || !"1".equals(maximumUses.strip())
                || !"1".equals(validityDays.strip());
    }

    /**
     * 构造服务端仍会再次安全校验的强类型草稿。
     *
     * @param workspaceId 所属 Workspace
     * @param json 严格 JSON 规范化器
     * @return 强类型授权草稿
     */
    public UnattendedToolGrantDraft toDraft(WorkspaceId workspaceId, CanonicalJson json) {
        CanonicalPayload arguments = Objects.requireNonNull(json, "json").parse(fixedArguments);
        int uses = boundedInt(maximumUses, "最大次数", 1, 100);
        int days = boundedInt(validityDays, "有效天数", 1, 30);
        return new UnattendedToolGrantDraft(
                Objects.requireNonNull(workspaceId, "workspaceId"),
                scheduleId,
                positive(scheduleRevision, "Schedule revision"),
                new ToolIdentity(producerId, toolName, positive(toolRevision, "工具 revision")),
                positive(catalogRevision, "Catalog revision"),
                schemaHash,
                arguments,
                fields(variableFields),
                uses,
                Duration.ofDays(days));
    }

    private static Set<String> fields(String value) {
        return Arrays.stream(value.split("[,\\s]+"))
                .map(String::strip)
                .filter(token -> !token.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    private static long positive(String value, String name) {
        long parsed = Long.parseLong(value.strip());
        if (parsed < 1) {
            throw new IllegalArgumentException(name + " 必须为正整数");
        }
        return parsed;
    }

    private static int boundedInt(String value, String name, int minimum, int maximum) {
        int parsed = Integer.parseInt(value.strip());
        if (parsed < minimum || parsed > maximum) {
            throw new IllegalArgumentException(name + "必须在 " + minimum + " 至 " + maximum + " 之间");
        }
        return parsed;
    }

    private static String text(String value) {
        return Objects.requireNonNullElse(value, "");
    }
}
