package com.javaclaw.desktop;

import com.javaclaw.sdk.model.ApprovalItemContent;
import com.javaclaw.sdk.model.ArtifactItemContent;
import com.javaclaw.sdk.model.CheckpointItemContent;
import com.javaclaw.sdk.model.CommandItemContent;
import com.javaclaw.sdk.model.EffectReceiptItemContent;
import com.javaclaw.sdk.model.ErrorItemContent;
import com.javaclaw.sdk.model.EvaluationItemContent;
import com.javaclaw.sdk.model.FileChangeItemContent;
import com.javaclaw.sdk.model.ImageItemContent;
import com.javaclaw.sdk.model.ItemInfo;
import com.javaclaw.sdk.model.McpItemContent;
import com.javaclaw.sdk.model.PlanItemContent;
import com.javaclaw.sdk.model.PromptDraftItemContent;
import com.javaclaw.sdk.model.SubagentItemContent;
import com.javaclaw.sdk.model.TextItemContent;
import com.javaclaw.sdk.model.UserInputItemContent;
import com.javaclaw.sdk.model.UserMessageItemContent;

/** 消息与自动化产物共用的纯展示投影；已知 Item 不再作为原始协议 JSON 泄漏到界面。 */
final class ItemPresenter {
    private ItemPresenter() {}

    static String text(ItemInfo item) {
        return switch (item.content()) {
            case null -> "STARTED".equals(item.state()) ? "正在执行…" : "没有可展示内容";
            case TextItemContent value -> value.text();
            case UserMessageItemContent value ->
                value.text()
                        + (value.attachments().isEmpty()
                                ? ""
                                : "\n\n附件："
                                        + value.attachments().stream()
                                                .map(com.javaclaw.sdk.model.TurnInput.Attachment::displayName)
                                                .collect(java.util.stream.Collectors.joining("、")));
            case ErrorItemContent value -> value.code() + "： " + value.message();
            case ApprovalItemContent value -> "需要批准：" + value.reason();
            case UserInputItemContent value -> "需要补充信息：" + value.prompt();
            case PlanItemContent value -> plan(value);
            case PromptDraftItemContent value ->
                value.draft() + "\n\n主要变化：\n" + String.join("\n", value.changes()) + "\n\n注意事项：\n"
                        + String.join("\n", value.warnings());
            case ArtifactItemContent value ->
                value.name() + " · 修订 " + value.revision() + "\n\n" + value.content()
                        + (value.sources().isEmpty() ? "" : "\n\n来源：" + String.join("、", value.sources()));
            case CheckpointItemContent value ->
                "检查点 · " + DesktopPresentationMapper.status(value.status()) + " · " + value.stepId() + "\n"
                        + value.summary()
                        + "\n已完成步骤 / 迭代：" + value.iteration() + " · 调用：" + value.usedModelCalls()
                        + " · Token：" + value.usedTokens() + " · 活动秒数：" + value.elapsedMillis() / 1000;
            case EvaluationItemContent value ->
                (value.passed() ? "验收通过" : "尚未通过验收") + " · "
                        + DesktopPresentationMapper.status(value.scope()) + "\n" + value.summary()
                        + (value.remaining().isEmpty() ? "" : "\n剩余条件：\n" + String.join("\n", value.remaining()))
                        + "\n证据 Item：" + String.join("、", value.evidenceItemIds());
            case EffectReceiptItemContent value ->
                "执行凭据 · " + value.tool() + " · " + DesktopPresentationMapper.status(value.state()) + "\n"
                        + value.summary()
                        + ("UNKNOWN".equals(value.state()) ? "\n结果未知：必须查询或人工确认，不会自动重发。" : "");
            case CommandItemContent value ->
                String.join(" ", value.argv()) + "\n退出码：" + value.exitCode()
                        + (value.timedOut() ? " · 已超时" : "") + (value.truncated() ? " · 输出已限流" : "")
                        + "\n" + value.stdout() + (value.stderr().isBlank() ? "" : "\n标准错误：\n" + value.stderr());
            case FileChangeItemContent value -> value.change() + " · " + value.path() + "\n" + value.diff();
            case McpItemContent value ->
                value.server() + " · " + value.tool() + " · " + DesktopPresentationMapper.status(value.status()) + "\n"
                        + value.output();
            case ImageItemContent value -> value.description() + "\n附件：" + value.uri();
            case SubagentItemContent value ->
                "子智能体 · " + value.childThreadId() + "\n任务：" + value.task() + "\n" + value.result();
            default ->
                "执行记录 · " + DesktopPresentationMapper.status(item.content().kind()) + " · "
                        + DesktopPresentationMapper.status(item.state());
        };
    }

    private static String plan(PlanItemContent value) {
        var text = new StringBuilder("目标：")
                .append(value.goal())
                .append("\n范围：")
                .append(value.scope())
                .append("\n\n");
        int index = 0;
        for (var step : value.steps()) {
            text.append(++index)
                    .append(". ")
                    .append(step.description())
                    .append(" · ")
                    .append(DesktopPresentationMapper.status(step.status()))
                    .append('\n');
        }
        if (!value.dependencies().isEmpty()) {
            text.append("\n依赖：\n").append(String.join("\n", value.dependencies()));
        }
        if (!value.acceptanceCriteria().isEmpty()) {
            text.append("\n验收条件：\n").append(String.join("\n", value.acceptanceCriteria()));
        }
        if (!value.risks().isEmpty()) {
            text.append("\n风险：\n").append(String.join("\n", value.risks()));
        }
        if (!value.openQuestions().isEmpty()) {
            text.append("\n待决策：\n").append(String.join("\n", value.openQuestions()));
        }
        return text.toString();
    }
}
