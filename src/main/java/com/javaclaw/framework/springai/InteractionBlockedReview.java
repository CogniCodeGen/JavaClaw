package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.agent.ToolRiskRegistry;
import com.javaclaw.framework.api.AgentStep;
import com.javaclaw.framework.api.InteractionMode;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.core.InteractionExecutionPolicy;
import com.javaclaw.framework.core.ReasoningRequest;
import com.javaclaw.framework.core.RunStepQuery;
import com.javaclaw.framework.core.TaskResultEvaluator;
import com.javaclaw.framework.spi.RunStore;

import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/** A model's blocker claim cannot hide an unattempted, host-proved desktop prerequisite. */
final class InteractionBlockedReview {
    static final String REPAIR_KIND = "INTERACTION_BLOCKED";
    static final String REASON_CODE = "INTERACTION_BLOCKED_WITH_AVAILABLE_STEP";
    private static final Set<String> PREPARATION_TOOLS = Set.of(
            "desktop_session_targets", "desktop_session_open", "desktop_session_observe");

    private InteractionBlockedReview() { }

    /** Called while the authorized catalog is still open; never dispatches or grants control. */
    static Candidate availableStep(ReasoningRequest request, ToolCatalogSession catalog,
            RunStore runs, String modelStepId, List<RunEventEnvelope> events) {
        if (catalog == null || !InteractionExecutionPolicy.isInteraction(request.runRequest())
                || InteractionExecutionPolicy.activeMode(request.runRequest(), events) != InteractionMode.DESKTOP
                || !request.control().pendingInteractionEffects().isEmpty()
                || request.control().hasPendingDesktopInput()) return null;
        List<JsonNode> runtime;
        try { runtime = catalog.currentRuntimeContext(); }
        catch (RuntimeException unavailable) { return null; }
        if (runtime.stream().anyMatch(context ->
                context.path("kind").asText().equals("desktop.access.current")
                        && context.path("settingEnabled").isBoolean()
                        && !context.path("settingEnabled").booleanValue())) return null;

        var query = new RunStepQuery(runs);
        var steps = query.steps(request.runId());
        AgentStep model = steps.stream().filter(step -> step.kind() == AgentStep.Kind.MODEL
                        && step.state() == AgentStep.State.COMPLETED
                        && step.id().value().equals(modelStepId)).findFirst().orElse(null);
        if (model == null || unresolvedHostFailure(steps)) return null;
        // Use settled host steps and receipts, not the model's prose, a checkpoint,
        // or a tool name mentioned in ordinary conversation. A ready observation
        // clears this prerequisite, so real UI blockers (e.g. login) remain terminal.
        String required = OnDemandDesktopPrerequisites.pendingDesktopPrerequisite(steps, events);
        if (!PREPARATION_TOOLS.contains(required == null ? "" : required)
                || !catalog.trustedHostTool(required)
                || !new PersistedProviderTools(request, query, runs).offeredNames(model).contains(required))
            return null;
        return new Candidate(modelStepId, required);
    }

    /** Reconsider one unsupported early stop before the child becomes an immutable terminal result. */
    static boolean requestRepair(ReasoningRequest request, ObjectMapper json,
            List<RunEventEnvelope> events, Candidate candidate, boolean inputHeadroom) {
        if (candidate == null) return false;
        var contract = TaskResultEvaluator.latestContractV3(events, json).orElse(null);
        if (contract == null || !contract.applicable() || !contract.reliable()) return false;
        var repairs = events.stream().filter(event -> event.runId().equals(request.runId().value())
                && event.type().equals("core.task.repair_requested")
                && event.producer().equals("framework.springai")).toList();
        if (repairs.size() >= 2 || repairs.stream().anyMatch(event ->
                event.payload().path("repairKind").asText().equals(REPAIR_KIND))
                || request.control().remainingToolCalls() == 0
                || request.control().remaining().compareTo(Duration.ofSeconds(3)) <= 0
                || !inputHeadroom) return false;
        String feedback = "宿主复核发现当前 BLOCKED 尚无充分依据：真实工具回执要求下一步调用 "
                + candidate.requiredTool() + "，且该接口确实已提供给刚才的模型步骤。"
                + "这不是权限授予，也不证明业务已完成。NOT_CHECKED 只表示系统权限未知；"
                + "未打开会话、controlGranted=false 或 inputAllowed=false 本身不表示权限被拒绝。"
                + "请在原任务权限、冻结条件和剩余预算内，通过当前宿主路径继续发现、打开或观察，"
                + "仅复制新宿主上下文中的真实目标和会话标识。明确纯观察或禁止交互时使用 control=false；"
                + "必要导航仍须原任务授权、获得控制并取得新观察。"
                + "不得重复启动应用，不得重放已执行或结果未知的点击、输入、发送、删除等副作用。"
                + "刚才自述的历史观察、账号内容或宿主身份不是证据；只回报真实工具观察到的内容。"
                + "若真实宿主结果证明权限拒绝、缺少工具或其他实际阻碍，再提交 BLOCKED 并说明依据。"
                + "此次纠偏仅一次，禁止靠模式切换、重开任务或重复相同失败调用延长它。";
        ObjectNode repair = JsonNodeFactory.instance.objectNode()
                .put("modelStepId", candidate.modelStepId())
                .put("repairKind", REPAIR_KIND)
                .put("reasonCode", REASON_CODE)
                .put("requiredTool", candidate.requiredTool())
                .put("attempt", repairs.size() + 1).put("feedback", feedback);
        request.events().emit("core.task.repair_requested", 3, "framework.springai", repair);
        return true;
    }

    private static boolean unresolvedHostFailure(List<AgentStep> steps) {
        var latest = new LinkedHashMap<String, AgentStep>();
        steps.stream().filter(step -> step.kind() == AgentStep.Kind.TOOL && step.input() != null
                        && ToolRiskRegistry.isDesktopSessionTool(step.input().path("tool").asText()))
                .sorted(Comparator.comparingLong(AgentStep::startSequence))
                .forEach(step -> latest.put(step.input().path("tool").asText(), step));
        for (AgentStep step : latest.values()) {
            if (step.state() != AgentStep.State.COMPLETED || step.output() == null
                    || !step.output().path("status").asText().equals("SUCCEEDED")) return true;
            JsonNode raw = step.output().path("rawOutput");
            // A successful probe invocation can still prove that OS access is unavailable.
            if (raw.path("nextStep").asText().equals("CHECK_PERMISSIONS")
                    || (raw.path("kind").asText().equals("desktop.probe")
                        && raw.path("available").isBoolean() && !raw.path("available").booleanValue()))
                return true;
        }
        return false;
    }

    record Candidate(String modelStepId, String requiredTool) { }
}
