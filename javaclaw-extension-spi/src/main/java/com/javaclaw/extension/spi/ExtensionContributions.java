package com.javaclaw.extension.spi;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ToolDescriptor;

/** 各类贡献点的不可变描述。 */
public final class ExtensionContributions {
    private ExtensionContributions() {}

    /**
     * Tool 贡献。
     *
     * @param contributionId 扩展内标识
     * @param descriptor 工具描述
     * @param handler 执行入口
     */
    public record Tool(String contributionId, ToolDescriptor descriptor, ExtensionHandler handler)
            implements ExtensionContribution {
        /** 校验 Tool 描述。 */
        public Tool {
            contributionId = text(contributionId, "contributionId");
            Objects.requireNonNull(descriptor, "descriptor");
            Objects.requireNonNull(handler, "handler");
        }

        @Override
        public ContributionKind kind() {
            return ContributionKind.TOOL;
        }
    }

    /**
     * 无副作用 Query 贡献。
     *
     * @param contributionId 扩展内标识
     * @param operations 可调用操作名
     * @param handler 执行入口
     */
    public record Query(String contributionId, Set<String> operations, ExtensionHandler handler)
            implements ExtensionContribution {
        /** 复制操作集合。 */
        public Query {
            contributionId = text(contributionId, "contributionId");
            operations = textSet(operations, "operations");
            Objects.requireNonNull(handler, "handler");
        }

        @Override
        public ContributionKind kind() {
            return ContributionKind.QUERY;
        }
    }

    /**
     * 有副作用 Command 贡献。
     *
     * @param contributionId 扩展内标识
     * @param operations 可调用操作名
     * @param handler 执行入口
     */
    public record Command(String contributionId, Set<String> operations, ExtensionHandler handler)
            implements ExtensionContribution {
        /** 复制操作集合。 */
        public Command {
            contributionId = text(contributionId, "contributionId");
            operations = textSet(operations, "operations");
            Objects.requireNonNull(handler, "handler");
        }

        @Override
        public ContributionKind kind() {
            return ContributionKind.COMMAND;
        }
    }

    /**
     * ViewSchema 贡献。
     *
     * @param contributionId 扩展内标识
     * @param view 页面 schema
     */
    public record View(String contributionId, ViewSchema view) implements ExtensionContribution {
        /** 校验页面。 */
        public View {
            contributionId = text(contributionId, "contributionId");
            Objects.requireNonNull(view, "view");
        }

        @Override
        public ContributionKind kind() {
            return ContributionKind.VIEW;
        }
    }

    /**
     * 多 Turn 编排贡献。
     *
     * @param contributionId 扩展内标识
     * @param operations 可由 {@code extension/command} 调用的操作名
     * @param orchestrator 编排入口
     */
    public record Orchestrator(String contributionId, Set<String> operations, TurnOrchestrator orchestrator)
            implements ExtensionContribution {
        /** 校验编排器。 */
        public Orchestrator {
            contributionId = text(contributionId, "contributionId");
            operations = textSet(operations, "operations");
            Objects.requireNonNull(orchestrator, "orchestrator");
        }

        @Override
        public ContributionKind kind() {
            return ContributionKind.ORCHESTRATOR;
        }
    }

    /**
     * 持久定时触发贡献。
     *
     * @param contributionId 扩展内标识
     * @param minimumInterval 平台允许的最短重复间隔
     * @param commandOperation 触发的 command 操作
     */
    public record Timer(String contributionId, Duration minimumInterval, String commandOperation)
            implements ExtensionContribution {
        /** 校验间隔与操作。 */
        public Timer {
            contributionId = text(contributionId, "contributionId");
            Objects.requireNonNull(minimumInterval, "minimumInterval");
            if (minimumInterval.isNegative() || minimumInterval.isZero()) {
                throw new IllegalArgumentException("minimumInterval must be positive");
            }
            commandOperation = text(commandOperation, "commandOperation");
        }

        @Override
        public ContributionKind kind() {
            return ContributionKind.TIMER;
        }
    }

    /**
     * 明确允许 Schedule 调用的 command operation。
     *
     * <p>该贡献只授予“可被调度”的能力声明，实际调用仍走原 command handler、幂等键、revision 和实时扩展状态校验。
     *
     * @param contributionId 扩展内标识
     * @param commandOperation 已存在的 command operation
     * @param displayName 管理中心可见名称
     * @param catalogVisible 是否允许用户为新 Schedule 选择
     * @param fields 固定 payload 允许的完整标量字段
     * @param expectedRevision 命令目标精确 revision；无版本目标为 0
     */
    public record SchedulableAction(
            String contributionId,
            String commandOperation,
            String displayName,
            boolean catalogVisible,
            List<ScheduleTargetCatalogPort.ActionField> fields,
            long expectedRevision)
            implements ExtensionContribution {
        /** 校验声明字段。 */
        public SchedulableAction {
            contributionId = text(contributionId, "contributionId");
            commandOperation = text(commandOperation, "commandOperation");
            displayName = text(displayName, "displayName");
            fields = List.copyOf(fields);
            if (fields.size() > 32
                    || fields.stream()
                                    .map(ScheduleTargetCatalogPort.ActionField::name)
                                    .distinct()
                                    .count()
                            != fields.size()) {
                throw new IllegalArgumentException("schedulable action fields must be unique and not exceed 32");
            }
            if (expectedRevision < 0) {
                throw new IllegalArgumentException("expectedRevision must not be negative");
            }
        }

        @Override
        public ContributionKind kind() {
            return ContributionKind.SCHEDULABLE_ACTION;
        }
    }

    /**
     * 可由 Schedule 固定到精确 revision 的业务 Definition 目录。
     *
     * @param contributionId 扩展内标识
     * @param displayName Definition 类别名称
     * @param provider 当前 Workspace 的权威目录回调
     */
    public record SchedulableDefinition(
            String contributionId, String displayName, SchedulableDefinitionProvider provider)
            implements ExtensionContribution {
        /** 校验目录描述。 */
        public SchedulableDefinition {
            contributionId = text(contributionId, "contributionId");
            displayName = text(displayName, "displayName");
            Objects.requireNonNull(provider, "provider");
        }

        @Override
        public ContributionKind kind() {
            return ContributionKind.SCHEDULABLE_ACTION;
        }
    }

    /**
     * 文本或资源型贡献，适用于 Context、Skill、MCP、Hook 与 Service 描述。
     *
     * @param contributionId 扩展内标识
     * @param kind 允许的贡献类别
     * @param descriptor 规范化描述
     */
    public record Resource(String contributionId, ContributionKind kind, CanonicalPayload descriptor)
            implements ExtensionContribution {
        /** 限制为无专用运行接口的资源类别。 */
        public Resource {
            contributionId = text(contributionId, "contributionId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(descriptor, "descriptor");
            if (!List.of(
                            ContributionKind.CONTEXT,
                            ContributionKind.SKILL,
                            ContributionKind.MCP,
                            ContributionKind.HOOK,
                            ContributionKind.SERVICE)
                    .contains(kind)) {
                throw new IllegalArgumentException("resource contribution kind is not supported: " + kind);
            }
        }
    }

    private static String text(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    private static Set<String> textSet(Set<String> values, String name) {
        Set<String> copied = Set.copyOf(values);
        if (copied.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        if (copied.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException(name + " must contain non-blank values");
        }
        return copied;
    }
}
