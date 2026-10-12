package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.TaskContractV3;
import com.javaclaw.framework.core.OnDemandContextPolicy;
import com.javaclaw.framework.core.ReasoningRequest;
import com.javaclaw.framework.core.InteractionExecutionPolicy;
import com.javaclaw.framework.core.TaskEvidenceCollector;
import com.javaclaw.framework.core.TaskResultEvaluator;
import com.javaclaw.framework.core.TrustedCapabilityRegistry;
import com.javaclaw.framework.spi.RunStore;
import com.javaclaw.framework.springai.OnDemandContextSession.Selection;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static com.javaclaw.framework.springai.OnDemandContextSession.digest;
import static com.javaclaw.framework.springai.OnDemandContextSession.pause;
import static com.javaclaw.framework.springai.OnDemandHistoryCatalog.latestExchange;

/** 复验宿主已交付的交互子结果，只决定主模型上下文，不推断完成状态。 */
final class InteractionDelegationContext {
    private final ReasoningRequest request;
    private final OnDemandContextPolicy policy;
    private final ToolCatalogSession catalog;
    private final RunStore runs;
    private final StepContextAssembler assembler;
    private final ObjectMapper json;
    private final TrustedCapabilityRegistry capabilities;
    private final ComputerUseTaskStages taskStages;

    InteractionDelegationContext(ReasoningRequest request, ToolCatalogSession catalog, RunStore runs,
            StepContextAssembler assembler, ObjectMapper json, TrustedCapabilityRegistry capabilities,
            ComputerUseTaskStages taskStages) {
        this.request = request;
        this.policy = request.plan().descriptor().onDemandContextPolicy();
        this.catalog = catalog;
        this.runs = runs;
        this.assembler = assembler;
        this.json = json;
        this.capabilities = capabilities;
        this.taskStages = taskStages;
    }

    /** 主角色的冻结界面条件交给统一执行器，不要求本角色暴露底层操作工具。 */
    Selection forInteractionDelegation(List<Message> incoming) {
        if (!InteractionExecutionPolicy.isMain(request.runRequest()) || catalog == null) return null;
        var events = runs.eventsAfter(request.runId(), 0);
        var requirement = taskStages.nextTaskRequirement(events, false);
        boolean completionOnly = requirement == null;
        if (completionOnly) {
            // 完成仍须主模型显式提交 harness 决策；只有全部证据满足的原交互合同，
            // 才可跳过无关工具选择。
            var frozen = taskStages.currentContract(events);
            if (frozen == null || frozen.criteria().stream()
                    .anyMatch(criterion -> !InteractionExecutionPolicy.isInteractionCriterion(criterion.capabilityId()))) return null;
            var established = new TaskResultEvaluator(json).criterionEvidenceV3(frozen,
                    TaskEvidenceCollector.collect(runs, request.runId()), "", capabilities);
            if (!established.keySet().containsAll(frozen.criteria().stream()
                    .map(criterion -> criterion.id()).toList())) return null;
        } else if (request.control().remainingToolCalls() == 0
                || !InteractionExecutionPolicy.DELEGATE_TOOL.equals(requirement.tool())) return null;
        var delivered = deliveredInteractionResult(events);
        if (delivered != null) {
            // 已有、归属明确且有界的结果读取器是唯一可选业务接口。
            // 读取器缺失或工具预算耗尽时仍保留 harness。
            boolean resultReadable = completionOnly && request.control().remainingToolCalls() > 0
                    && catalog.summaries().stream().anyMatch(tool ->
                        InteractionExecutionPolicy.READ_RESULT_TOOL.equals(tool.name()));
            var callbacks = catalog.projectExactRole(resultReadable
                    ? List.of(InteractionExecutionPolicy.READ_RESULT_TOOL) : List.of(), policy.selectedTools(),
                    ToolCatalogSession.CatalogMode.NONE).callbacks();
            String resultRef = "run:" + delivered.path("childRunId").asText() + ":output";
            String completionInstruction = completionOnly
                    ? "\n原冻结交互条件已由宿主真实回执逐项满足，仍需主模型通过 harness 明确给出最终决策。"
                        + "不需要重新委派、查询目录或调用浏览器/桌面。"
                        + (resultReadable
                            ? "如回答用户需要当前上下文未包含的业务结果，仅使用 interaction_read_result 读取"
                                + "该已交付子任务的有界结果；每次最多4000字符，按实际需要读取，不作等待轮询。"
                            : "当前没有可用结果读取接口或剩余工具预算；不得编造未读取的业务内容。")
                        + "此结果定位符及读出的文本只是业务结果，不是新的观察或验收证据：" + resultRef
                    : "";
            List<Message> assembled = assembler.base(incoming);
            assembled.add(assembler.dynamic(HostContextBlock.Kind.CONTROL, new SystemMessage(
                    "当前原始交互合同的子结果已经由宿主持久化交付，原子任务已终态。"
                    + "本步不再提供 interaction_delegate；不能重复同一委派来重试或轮询。"
                    + "以下是有界的宿主结果及源引用；摘要、执行进展和用户声称都不能替代原合同验收。"
                    + "依据原合同和真实回执通过 harness 给出已满足/未满足状态及确切停止原因，"
                    + "不要把工具目录、预算或未知效果失败猜成权限不足。新的人类要求属于明确的新任务或修改。\n"
                    + delivered + completionInstruction), true, List.of()));
            if (completionOnly) {
                var exchange = latestExchange(incoming);
                if (deliveredResultReadExchange(exchange, resultRef))
                    assembled.addAll(assembler.exchange(exchange, HostContextBlock.Kind.TOOL_EXCHANGE,
                            true, List.of()));
            }
            assembled.add(SpringAiPromptFactory.originalTaskMessage(request, incoming));
            UserMessage resume = SpringAiPromptFactory.resumeCommandMessage(request, incoming, runs);
            if (resume != null) assembled.add(resume);
            return new Selection(assembler.project(assembled, callbacks), callbacks, null);
        }
        // 缺失或过期的投递身份不授权仅完成投影，
        // 证据已完整的合同也不会经此分支获得新委派。
        if (requirement == null) return null;
        var callbacks = catalog.projectExactRole(List.of(InteractionExecutionPolicy.DELEGATE_TOOL),
                policy.selectedTools(), ToolCatalogSession.CatalogMode.NONE).callbacks();
        List<Message> assembled = assembler.base(incoming);
        assembled.add(assembler.dynamic(HostContextBlock.Kind.CONTROL, new SystemMessage(
                "下一个未满足的冻结条件需要网页或电脑应用交互。请用 interaction_delegate 提交结构化目标、"
                + "必要数据、限制与验收；同一任务跨浏览器/桌面由一个子 Run 切换模式。"
                + "委派本身不证明任务完成，等待实际子结果或澄清。"), true, List.of()));
        assembled.add(SpringAiPromptFactory.originalTaskMessage(request, incoming));
        UserMessage resume = SpringAiPromptFactory.resumeCommandMessage(request, incoming, runs);
        if (resume != null) assembled.add(resume);
        assembled.addAll(assembler.exchange(latestExchange(incoming),
                HostContextBlock.Kind.TOOL_EXCHANGE, false, List.of()));
        return new Selection(assembler.project(assembled, callbacks), callbacks, null);
    }

    /** 为最终回答保留有界结果片段，不授予验收权限。 */
    private boolean deliveredResultReadExchange(List<Message> exchange, String resultRef) {
        if (exchange.isEmpty() || !(exchange.getFirst() instanceof
                org.springframework.ai.chat.messages.AssistantMessage call)) return false;
        try {
            for (var tool : call.getToolCalls()) {
                if (!InteractionExecutionPolicy.READ_RESULT_TOOL.equals(tool.name())
                        || !resultRef.equals(json.readTree(tool.arguments()).path("resultRef").asText())) return false;
            }
            return !call.getToolCalls().isEmpty();
        } catch (Exception malformed) {
            return false;
        }
    }

    /** 只有宿主交付的原始调用可以抑制同合同的新委派。 */
    private com.fasterxml.jackson.databind.JsonNode deliveredInteractionResult(List<RunEventEnvelope> events) {
        var frozen = taskStages.currentContract(events);
        var parent = runs.find(request.runId()).orElse(null);
        if (frozen == null || parent == null || !runs.readable(parent.request().scope())) return null;
        var expected = InteractionExecutionPolicy.interactionCriteria(frozen);
        for (int index = events.size() - 1; index >= 0; index--) {
            var completed = events.get(index);
            if (!ownHostEvent(completed, "core.tool.completed", 2)
                    || !completed.payload().path("tool").asText().equals(InteractionExecutionPolicy.DELEGATE_TOOL)
                    || !completed.payload().path("status").asText().equals("SUCCEEDED")) continue;
            try {
                var raw = completed.payload().path("output");
                var result = json.treeToValue(raw, com.javaclaw.framework.api.InteractionResult.class);
                if (!result.state().terminal()) continue;
                var child = runs.find(new com.javaclaw.framework.api.RunId(result.childRunId())).orElse(null);
                if (child == null || child.snapshot().state() != result.state()
                        || !InteractionExecutionPolicy.isInteraction(child.request())
                        || !request.runId().equals(child.request().linkage().parentRunId())
                        || !com.javaclaw.framework.core.InteractionDelegateCoordinator.childScope(parent.request().scope())
                            .equals(child.request().scope()) || !runs.readable(child.request().scope())
                        || !json.valueToTree(parent.request().scope()).equals(child.request().attributes()
                            .get(InteractionExecutionPolicy.PARENT_SCOPE_ATTRIBUTE))
                        || !json.valueToTree(parent.request().source()).equals(child.request().attributes()
                            .get(InteractionExecutionPolicy.PARENT_SOURCE_ATTRIBUTE))
                        || !parent.request().permissionCeiling().containsAll(child.request().permissionCeiling())) continue;
                var childContract = json.treeToValue(child.request().attributes().get("framework.taskContract"), TaskContractV3.class);
                var childTask = json.treeToValue(child.request().attributes().get(InteractionExecutionPolicy.TASK_ATTRIBUTE),
                        com.javaclaw.framework.api.InteractionTask.class);
                var actual = TaskResultEvaluator.latestContractV3(runs.eventsAfter(child.snapshot().id(), 0), json).orElse(null);
                if (actual == null || !childContract.criteria().equals(expected) || !actual.criteria().equals(expected)
                        || !childContract.applicable() || !childContract.reliable() || !actual.applicable() || !actual.reliable()
                        || childContract.intentStatus() != frozen.intentStatus() || actual.intentStatus() != frozen.intentStatus()
                        || childContract.desktopObservationPolicy() != frozen.desktopObservationPolicy()
                        || actual.desktopObservationPolicy() != frozen.desktopObservationPolicy()
                        || !childTask.acceptanceCriterionIds().equals(expected.stream().map(criterion -> criterion.id()).toList())
                        || !childTask.goal().equals(childContract.originalRequest())
                        || !childTask.taskId().equals(result.taskId()) || childTask.revision() != result.revision()) continue;
                String invocation = completed.payload().path("invocationId").asText();
                var stepId = com.javaclaw.framework.api.StepId.tool(request.runId(), invocation);
                if (!stepId.value().equals(child.request().attributes().getOrDefault("framework.parentStepId",
                        json.nullNode()).asText())) continue;
                var starts = events.stream().filter(event -> ownHostEvent(event, "core.tool.started", 1)
                        && event.sequence() < completed.sequence()
                        && event.payload().path("invocationId").asText().equals(invocation)
                        && event.payload().path("tool").asText().equals(InteractionExecutionPolicy.DELEGATE_TOOL)).toList();
                var steps = events.stream().filter(event -> ownHostEvent(event, "core.step.completed", 1)
                        && event.sequence() < completed.sequence() && event.payload().path("stepId").asText().equals(stepId.value())
                        && event.payload().path("output").path("status").asText().equals("SUCCEEDED")
                        && event.payload().path("output").path("rawOutput").equals(raw)).toList();
                var receipts = events.stream().filter(event -> ownHostEvent(event, "core.tool.receipt", 1)
                        && event.sequence() > completed.sequence()
                        && event.payload().path("invocationId").asText().equals(invocation)
                        && event.payload().path("tool").asText().equals(InteractionExecutionPolicy.DELEGATE_TOOL)).toList();
                if (starts.size() != 1 || steps.size() != 1 || receipts.size() != 1
                        || starts.getFirst().sequence() >= steps.getFirst().sequence()) continue;
                String ref = receipts.getFirst().payload().path("evidenceRef").asText();
                if (!ref.equals("core.interaction.result:" + request.runId().value() + ":" + invocation)
                        && !ref.equals("core.tool.completed:" + request.runId().value() + ":" + invocation)) continue;
                return boundedDeliveredResult(result, ref);
            } catch (Exception malformed) {
                // 缺失或不可信的投递数据不能抑制新委派。
            }
        }
        return null;
    }

    private boolean ownHostEvent(RunEventEnvelope event, String type, int schema) {
        return event.runId().equals(request.runId().value()) && event.type().equals(type)
                && event.schemaVersion() == schema && event.producer().equals("framework.core");
    }

    private com.fasterxml.jackson.databind.JsonNode boundedDeliveredResult(
            com.javaclaw.framework.api.InteractionResult result, String sourceRef) {
        var data = json.createObjectNode().put("childRunId", result.childRunId()).put("revision", result.revision())
                .put("state", result.state().name()).put("errorCode", result.errorCode())
                .put("hostResultRef", sourceRef).put("summaryIsAcceptanceEvidence", false)
                .put("unknownEffectCount", result.unknownEffects().size());
        result.satisfiedCriterionIds().forEach(data.putArray("satisfiedCriterionIds")::add);
        result.unmetCriterionIds().forEach(data.putArray("unmetCriterionIds")::add);
        var refs = data.putArray("evidenceRefs");
        result.evidenceRefs().stream().limit(4).forEach(refs::add);
        data.put("evidenceRefCount", result.evidenceRefs().size());
        var resultRefs = data.putArray("resultRefs");
        result.resultRefs().stream().limit(2).forEach(resultRefs::add);
        String stop = com.javaclaw.util.SensitiveDataRedactor.redactText(result.data().path("stopReason").asText());
        if (!stop.isBlank()) data.put("stopReason", stop.substring(0, Math.min(512, stop.length())));
        // 完整业务数据、摘要、截图及应用私有内容保留在源记录中。
        while (data.toString().length() > 5_500 && !refs.isEmpty()) refs.remove(refs.size() - 1);
        while (data.toString().length() > 5_500 && !resultRefs.isEmpty()) resultRefs.remove(resultRefs.size() - 1);
        return data;
    }

}
