package com.javaclaw.agent.automation;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 已验证的编排定义，不包含模型、数据库或表达式执行器。
 *
 * @param kind Loop、Workflow 或 SDD 领域策略
 * @param limits 只能收窄 Profile 的有限预算
 * @param criteria 可执行的验收条件；无条件时要求用户确认
 * @param nodes Workflow 节点，其他策略为空
 * @param specification SDD 的初始规格文本
 * @param openSpecDocuments 显式确认导入的 OpenSpec 文档，只作版本化参考，不驱动完成状态
 */
public record AutomationPlan(
        AutomationKind kind,
        Limits limits,
        List<Criterion> criteria,
        List<Node> nodes,
        String specification,
        Map<String, String> openSpecDocuments) {
    /** 固定经过解析的领域定义；集合对调用方只读。 */
    public AutomationPlan {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(limits, "limits");
        criteria = List.copyOf(criteria);
        nodes = List.copyOf(nodes);
        specification = Objects.requireNonNull(specification, "specification");
        openSpecDocuments = Map.copyOf(openSpecDocuments);
    }

    /**
     * 执行上限；0 只允许在输入解析时出现并转换为有限默认值。
     *
     * @param iterations Loop 最大迭代数或 Workflow 最大步骤数
     * @param modelCalls 模型调用总额度
     * @param tokens token 总额度
     * @param seconds 活动运行最长秒数
     * @param noProgress 无实质增量的连续次数上限
     */
    public record Limits(int iterations, int modelCalls, long tokens, int seconds, int noProgress) {
        /** 所有预算必须为正并受硬上限约束，不能通过恢复扩大。 */
        public Limits {
            if (iterations < 1
                    || iterations > 1_000
                    || modelCalls < 1
                    || modelCalls > 1_000
                    || tokens < 1
                    || tokens > 10_000_000
                    || seconds < 1
                    || seconds > 86_400
                    || noProgress < 1
                    || noProgress > 25) {
                throw new IllegalArgumentException("invalid finite automation limits");
            }
        }
    }

    /** 验收方式；远程已提交不等于已送达，必须由用户指定需要核实的结果。 */
    public enum CriterionKind {
        COMMAND_EXIT,
        RESULT_FIELD,
        USER_CONFIRMATION
    }

    /**
     * 验收步骤；工具只通过 TurnToolSession 执行，不能直接调用进程或网络。
     *
     * @param id 条件的稳定标识
     * @param description 面向用户的验收说明
     * @param kind 核验方式
     * @param tool 实际工具名，用户确认时为空
     * @param argumentsJson 经过结构校验的 JSON 参数对象
     * @param field RESULT_FIELD 的结果字段，其他方式可为空
     * @param expected 期望的退出码、字段值或确认值
     */
    public record Criterion(
            String id,
            String description,
            CriterionKind kind,
            String tool,
            String argumentsJson,
            String field,
            String expected) {}

    /** 有限图支持的节点种类；TRANSFORM/CONDITION 是声明式数据操作，不执行任意代码。 */
    public enum NodeKind {
        START,
        END,
        AGENT,
        TOOL,
        CONDITION,
        TRANSFORM,
        HUMAN_INPUT,
        OUTPUT
    }

    /**
     * 有界 Workflow 节点；所有引用必须在发布时存在，循环必须明确声明 maxVisits。
     *
     * @param id 稳定节点标识
     * @param kind 节点类型
     * @param next 普通后继标识，END 为空
     * @param otherwise CONDITION 不满足时的后继
     * @param maxVisits 允许访问次数，解析时验证循环不能沿用未声明默认值
     * @param parameters 有界、按节点类型验证的声明参数
     */
    public record Node(
            String id, NodeKind kind, String next, String otherwise, int maxVisits, Map<String, String> parameters) {
        /** 复制参数；避免编辑器变更运行中的已固化定义。 */
        public Node {
            parameters = Map.copyOf(parameters);
        }
    }
}
