package com.javaclaw.sdk.model;

import java.util.List;
import java.util.Map;

/**
 * 自动化编辑器的有限预算、验收和节点定义；不是第二套 Runtime，保存时服务端仍完整校验。
 *
 * @param maxIterations 最大迭代或步骤数；0 继承有限默认上限
 * @param maxModelCalls 最大模型调用数；0 继承有限默认上限
 * @param maxTokens 最大 token 预算；0 继承有限默认上限
 * @param maxDurationSeconds 最长执行秒数；0 继承有限默认上限
 * @param noProgressLimit 连续无进展停止阈值
 * @param specification SDD 初始规格，其余模式可为空
 * @param criteria 明确验收条件；空列表表示运行时要求用户确认
 * @param nodes Workflow 节点，其他模式为空
 * @param openSpecDocuments 显式导入并确认的 OpenSpec 文档，其他模式为空；只作版本化资料
 */
public record AutomationDefinitionInfo(
        int maxIterations,
        int maxModelCalls,
        int maxTokens,
        int maxDurationSeconds,
        int noProgressLimit,
        String specification,
        List<Criterion> criteria,
        List<Node> nodes,
        Map<String, String> openSpecDocuments) {
    /** 固定编辑快照；保存时不改变已有预算，0 不代表无限。 */
    public AutomationDefinitionInfo {
        criteria = List.copyOf(criteria);
        nodes = List.copyOf(nodes);
        openSpecDocuments = Map.copyOf(openSpecDocuments);
    }

    /**
     * 验收条件；工具参数只在 SDK 内部解析，不向 UI 泄漏 Jackson。
     *
     * @param id 稳定条件标识
     * @param description 可读成功标准
     * @param kind USER_CONFIRMATION、COMMAND_EXIT 或 RESULT_FIELD
     * @param tool 验收工具名；人工验收为空
     * @param arguments 工具参数对象
     * @param field 结果字段，仅 RESULT_FIELD 使用
     * @param expected 预期值或用户确认用语
     */
    public record Criterion(
            String id,
            String description,
            String kind,
            String tool,
            JsonDocument arguments,
            String field,
            String expected) {}

    /**
     * 有界图节点，不支持在 App Server 执行表达式代码。
     *
     * @param id 图内唯一节点标识
     * @param kind START、END、AGENT、TOOL、CONDITION、TRANSFORM、HUMAN_INPUT 或 OUTPUT
     * @param next 常规后继，END 为空
     * @param otherwise CONDITION 的另一后继，其余为空
     * @param maxVisits 节点访问上限，循环节点必须显式设置
     * @param parameters 领域参数；arguments 值为 JSON 文本，其余为字符串
     */
    public record Node(
            String id, String kind, String next, String otherwise, int maxVisits, Map<String, String> parameters) {
        /** 防御性复制参数，编辑状态不修改已提交的节点定义。 */
        public Node {
            parameters = Map.copyOf(parameters);
        }
    }
}
