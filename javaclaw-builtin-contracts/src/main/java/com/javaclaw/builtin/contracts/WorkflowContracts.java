package com.javaclaw.builtin.contracts;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.TurnId;

/** Workflow 的安全 Graph Definition 与服务端恢复状态；所有控制逻辑均由平台解释。 */
public final class WorkflowContracts {
    private WorkflowContracts() {}

    /** 平台支持的固定节点类别。 */
    public enum NodeKind {
        /** 唯一起点。 */
        START,
        /** 一个或多个终点。 */
        END,
        /** 受预算 Thin Harness Turn。 */
        TURN,
        /** 冻结目录中的精确 Tool。 */
        TOOL,
        /** 固定字段比较条件。 */
        CONDITION,
        /** 固定结构化字段变换。 */
        TRANSFORM,
        /** 受治理用户输入。 */
        USER_INPUT,
        /** 把一个字段加入执行输出。 */
        OUTPUT
    }

    /** CONDITION 节点允许的固定比较。 */
    public enum ConditionOperator {
        /** 字符串完全相等。 */
        EQUALS,
        /** 字符串不相等。 */
        NOT_EQUALS,
        /** 字段存在。 */
        EXISTS,
        /** 字段不存在。 */
        NOT_EXISTS
    }

    /** TRANSFORM 节点允许的固定变换。 */
    public enum TransformOperation {
        /** 写入固定字符串。 */
        SET,
        /** 复制另一个字段。 */
        COPY,
        /** 删除字段。 */
        REMOVE
    }

    /**
     * 精确 Tool 配置。
     *
     * @param toolName 冻结目录中的全局工具名
     * @param arguments 规范化对象参数
     * @param resultField 保存完整规范结果的变量名
     */
    public record ToolConfig(String toolName, CanonicalPayload arguments, String resultField) {
        /** 校验工具配置。 */
        public ToolConfig {
            toolName = ContractValidation.text(toolName, "toolName");
            Objects.requireNonNull(arguments, "arguments");
            resultField = ContractValidation.text(resultField, "resultField");
        }
    }

    /**
     * 固定字段条件。
     *
     * @param field 变量字段
     * @param operator 比较运算
     * @param expected EQUALS/NOT_EQUALS 的期望字符串，存在性判断时为空
     */
    public record ConditionConfig(String field, ConditionOperator operator, Optional<String> expected) {
        /** 校验比较字段互斥关系。 */
        public ConditionConfig {
            field = ContractValidation.text(field, "field");
            Objects.requireNonNull(operator, "operator");
            expected = optionalText(expected, "expected");
            boolean comparison = operator == ConditionOperator.EQUALS || operator == ConditionOperator.NOT_EQUALS;
            if (comparison != expected.isPresent()) {
                throw new IllegalArgumentException("condition expected value does not match operator");
            }
        }
    }

    /**
     * 固定字段变换。
     *
     * @param operation 变换种类
     * @param target 目标字段
     * @param source COPY 的来源字段
     * @param value SET 的固定字符串
     */
    public record TransformConfig(
            TransformOperation operation, String target, Optional<String> source, Optional<String> value) {
        /** 校验变换字段互斥关系。 */
        public TransformConfig {
            Objects.requireNonNull(operation, "operation");
            target = ContractValidation.text(target, "target");
            source = optionalText(source, "source");
            value = optionalText(value, "value");
            boolean valid =
                    switch (operation) {
                        case SET -> source.isEmpty() && value.isPresent();
                        case COPY -> source.isPresent() && value.isEmpty();
                        case REMOVE -> source.isEmpty() && value.isEmpty();
                    };
            if (!valid) {
                throw new IllegalArgumentException("transform fields do not match operation");
            }
        }
    }

    /**
     * 用户输入配置。
     *
     * @param prompt 用户可见问题
     * @param responseSchema 非 Secret 的受限对象 Schema
     * @param responseField 保存规范响应的变量名
     * @param timeout 等待时长，最多一天
     */
    public record UserInputConfig(
            String prompt, CanonicalPayload responseSchema, String responseField, Duration timeout) {
        /** 校验输入配置和等待上限。 */
        public UserInputConfig {
            prompt = ContractValidation.text(prompt, "prompt");
            Objects.requireNonNull(responseSchema, "responseSchema");
            responseField = ContractValidation.text(responseField, "responseField");
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofDays(1)) > 0) {
                throw new IllegalArgumentException("input timeout must be within one day");
            }
        }
    }

    /**
     * 输出配置。
     *
     * @param field 要加入输出列表的变量字段
     */
    public record OutputConfig(String field) {
        /** 校验输出字段。 */
        public OutputConfig {
            field = ContractValidation.text(field, "field");
        }
    }

    /**
     * 节点的互斥强类型配置。
     *
     * @param tool TOOL 配置
     * @param condition CONDITION 配置
     * @param transform TRANSFORM 配置
     * @param input USER_INPUT 配置
     * @param output OUTPUT 配置
     */
    public record NodeConfig(
            Optional<ToolConfig> tool,
            Optional<ConditionConfig> condition,
            Optional<TransformConfig> transform,
            Optional<UserInputConfig> input,
            Optional<OutputConfig> output) {
        /** 复制 Optional，并拒绝多个配置同时存在。 */
        public NodeConfig {
            tool = Objects.requireNonNull(tool, "tool");
            condition = Objects.requireNonNull(condition, "condition");
            transform = Objects.requireNonNull(transform, "transform");
            input = Objects.requireNonNull(input, "input");
            output = Objects.requireNonNull(output, "output");
            long count = List.of(tool, condition, transform, input, output).stream()
                    .filter(Optional::isPresent)
                    .count();
            if (count > 1) {
                throw new IllegalArgumentException("Workflow node config must be mutually exclusive");
            }
        }

        /**
         * 创建无领域配置的节点配置。
         *
         * @return 空配置
         */
        public static NodeConfig empty() {
            return new NodeConfig(
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        }

        private Optional<NodeKind> configuredKind() {
            if (tool.isPresent()) {
                return Optional.of(NodeKind.TOOL);
            }
            if (condition.isPresent()) {
                return Optional.of(NodeKind.CONDITION);
            }
            if (transform.isPresent()) {
                return Optional.of(NodeKind.TRANSFORM);
            }
            if (input.isPresent()) {
                return Optional.of(NodeKind.USER_INPUT);
            }
            return output.isPresent() ? Optional.of(NodeKind.OUTPUT) : Optional.empty();
        }
    }

    /**
     * Graph 节点。
     *
     * @param id 稳定节点标识
     * @param kind 固定节点类别
     * @param name 名称
     * @param instruction TURN 节点指令；其他节点为空
     * @param config 受节点类别约束的强类型配置
     */
    public record Node(String id, NodeKind kind, String name, Optional<String> instruction, NodeConfig config) {
        /** 校验节点指令与配置严格匹配其类别。 */
        public Node {
            id = ContractValidation.text(id, "id");
            Objects.requireNonNull(kind, "kind");
            name = ContractValidation.text(name, "name");
            instruction = optionalText(instruction, "instruction");
            Objects.requireNonNull(config, "config");
            requireNodeShape(kind, instruction, config);
        }
    }

    /**
     * 有向边。
     *
     * @param from 起点节点
     * @param to 终点节点
     * @param branch CONDITION 使用 TRUE/FALSE；其他节点为空
     */
    public record Edge(String from, String to, Optional<String> branch) {
        /** 校验边。 */
        public Edge {
            from = ContractValidation.text(from, "from");
            to = ContractValidation.text(to, "to");
            branch = optionalText(branch, "branch").map(String::toUpperCase);
        }
    }

    /**
     * 用户可编辑 Graph Definition。
     *
     * @param id 定义标识
     * @param revision 乐观锁版本
     * @param name 名称
     * @param nodes 节点
     * @param edges 有向边
     * @param maxVisits 全 Execution 节点访问上限
     * @param updatedAt 更新时间
     */
    public record Definition(
            String id, long revision, String name, List<Node> nodes, List<Edge> edges, int maxVisits, Instant updatedAt)
            implements VersionedExtensionDocument {
        /** 校验唯一 START、END、边形状、可达性和访问上限。 */
        public Definition {
            id = ContractValidation.text(id, "id");
            revision = ContractValidation.revision(revision);
            name = ContractValidation.text(name, "name");
            nodes = List.copyOf(nodes);
            edges = List.copyOf(edges);
            if (maxVisits < 1 || maxVisits > 10_000) {
                throw new IllegalArgumentException("maxVisits must be between 1 and 10000");
            }
            WorkflowGraph.validate(nodes, edges);
            updatedAt = ContractValidation.instant(updatedAt, "updatedAt");
        }
    }

    /**
     * 服务端持久化的 Graph 恢复指针。
     *
     * @param currentNodeId 下一次要推进的节点
     * @param variables 平台解释的结构化字符串变量
     * @param visits 每个节点已访问次数
     * @param pendingInputRequestId 等待中的 InputRequest
     * @param pendingInputTurnId InputRequest 所属 Turn
     * @param outputs 已显式输出的不可变值
     */
    public record Checkpoint(
            String currentNodeId,
            Map<String, String> variables,
            Map<String, Integer> visits,
            Optional<String> pendingInputRequestId,
            Optional<TurnId> pendingInputTurnId,
            List<String> outputs) {
        /** 校验等待身份成对出现，并复制恢复状态。 */
        public Checkpoint {
            currentNodeId = ContractValidation.text(currentNodeId, "currentNodeId");
            variables = Map.copyOf(Objects.requireNonNull(variables, "variables"));
            visits = Map.copyOf(Objects.requireNonNull(visits, "visits"));
            pendingInputRequestId = optionalText(pendingInputRequestId, "pendingInputRequestId");
            pendingInputTurnId = Objects.requireNonNull(pendingInputTurnId, "pendingInputTurnId");
            outputs = List.copyOf(Objects.requireNonNull(outputs, "outputs"));
            if (pendingInputRequestId.isPresent() != pendingInputTurnId.isPresent()) {
                throw new IllegalArgumentException("pending input request and Turn must appear together");
            }
            if (visits.values().stream().anyMatch(value -> value == null || value < 0)) {
                throw new IllegalArgumentException("Workflow visits must not be negative");
            }
        }
    }

    /**
     * 恢复一个已决议 USER_INPUT 的 Execution。
     *
     * @param jobId Job 标识
     * @param requestId InputRequest 标识
     */
    public record ContinueInput(String jobId, String requestId) {
        /** 校验恢复身份。 */
        public ContinueInput {
            jobId = ContractValidation.text(jobId, "jobId");
            requestId = ContractValidation.text(requestId, "requestId");
        }
    }

    private static void requireNodeShape(NodeKind kind, Optional<String> instruction, NodeConfig config) {
        boolean turnInstruction = kind == NodeKind.TURN;
        Optional<NodeKind> configuredKind = config.configuredKind();
        boolean requiresConfig = kind != NodeKind.START && kind != NodeKind.END && kind != NodeKind.TURN;
        if (instruction.isPresent() != turnInstruction
                || configuredKind.isPresent() != requiresConfig
                || configuredKind.filter(value -> value != kind).isPresent()) {
            throw new IllegalArgumentException("Workflow node fields do not match kind");
        }
    }

    private static Optional<String> optionalText(Optional<String> value, String name) {
        return Objects.requireNonNull(value, name).map(entry -> ContractValidation.text(entry, name));
    }
}
