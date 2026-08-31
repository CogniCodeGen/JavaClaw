package com.javaclaw.agent.automation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.javaclaw.agent.runtime.ItemSink;
import com.javaclaw.agent.runtime.TurnExecutionContext;
import com.javaclaw.agent.tool.TurnToolSession;
import com.javaclaw.core.api.ModelToolCall;
import com.javaclaw.core.api.StoredItem;
import com.javaclaw.core.api.ThreadItem;

/** 共享确定性评估；命令退出码、明确结果字段或用户确认优先，不接受模型自述作为成功凭据。 */
public final class EvaluationService {
    private final ExecutionInteractions interactions;

    /** 固定确认端口；Loop、SDD 和 Workflow 共用，不创建额外模型循环或预算账户。 */
    public EvaluationService(ExecutionInteractions interactions) {
        this.interactions = java.util.Objects.requireNonNull(interactions, "interactions");
    }

    /** 逐条执行验收并保存 Evaluation；输入工具及网络仍经过原 Turn 的治理链。 */
    public ThreadItem.Evaluation evaluate(
            TurnExecutionContext context,
            ItemSink events,
            TurnToolSession tools,
            String step,
            List<AutomationPlan.Criterion> criteria)
            throws Exception {
        var evidence = new ArrayList<String>();
        var remaining = new ArrayList<String>();
        for (var criterion : criteria) {
            context.throwIfInterrupted();
            StoredItem stored;
            boolean passed;
            String key = step + ":criterion:" + criterion.id();
            if (criterion.kind() == AutomationPlan.CriterionKind.USER_CONFIRMATION) {
                stored = interactions.ask(context, events, key, criterion.description(), List.of("确认", "继续改进", "暂停"));
                passed =
                        stored.item() instanceof ThreadItem.UserInputResponse response && "确认".equals(response.value());
                if (stored.item() instanceof ThreadItem.UserInputResponse response && "暂停".equals(response.value())) {
                    throw new ExecutionPausedException("用户要求暂停验收。");
                }
            } else {
                var result = tools.execute(new ModelToolCall(key, criterion.tool(), criterion.argumentsJson()), key);
                stored = events.append(result.item());
                passed = switch (criterion.kind()) {
                    case COMMAND_EXIT ->
                        result.item() instanceof ThreadItem.CommandExecution command
                                && !command.timedOut()
                                && command.exitCode() == Integer.parseInt(criterion.expected());
                    case RESULT_FIELD ->
                        criterion.expected().equals(fields(result.item()).get(criterion.field()));
                    case USER_CONFIRMATION -> false;
                };
            }
            if (stored != null) {
                evidence.add(stored.id().value());
            }
            if (!passed) {
                remaining.add(criterion.description());
            }
        }
        boolean passed = remaining.isEmpty() && !evidence.isEmpty();
        var evaluation = new ThreadItem.Evaluation(
                step, passed, passed ? "全部验收条件均有实际证据。" : "仍有未满足的验收条件，任务尚未完成。", evidence, remaining);
        events.append(evaluation);
        return evaluation;
    }

    private static Map<String, String> fields(ThreadItem item) {
        return switch (item) {
            case ThreadItem.DynamicToolCall tool -> tool.result();
            case ThreadItem.McpToolCall tool -> tool.result();
            default -> Map.of();
        };
    }
}
