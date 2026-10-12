package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.TaskContractV1;
import com.javaclaw.framework.api.TaskCriterion;
import com.javaclaw.framework.api.TaskOutcome;
import com.javaclaw.util.ProjectAccessPolicy;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskResultEvaluatorTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void desktopViewRequiresAnObservationFromTheOpenedApplication() {
        TaskContractV1 contract = new TaskContractV1(1, "打开日历查看日程", "日历", List.of(
                new TaskCriterion("window", "打开日历窗口会话", "日历", "open", "ACCEPTED"),
                new TaskCriterion("agenda", "取得日程观察结果", "日历", "observe", "OBSERVED", "日程")),
                true, true, "definition");
        var launch = receipt(1, "launch_application", "日历", "ACCEPTED", "tool:1");
        var open = receipt(2, "open", "日历", "ACCEPTED", "tool:2");
        assertEquals(TaskOutcome.PARTIAL,
                TaskResultEvaluator.evaluate(contract, List.of(launch, open), "").outcome());
        var wrongApp = receipt(3, "observe", "文件管理器", "OBSERVED", "tool:3");
        assertEquals(TaskOutcome.PARTIAL,
                TaskResultEvaluator.evaluate(contract, List.of(launch, open, wrongApp), "").outcome());
        var staleTarget = receipt(4, "observe", "日历-old-session", "OBSERVED", "tool:4");
        assertEquals(TaskOutcome.PARTIAL,
                TaskResultEvaluator.evaluate(contract, List.of(open, staleTarget), "").outcome());
        var generic = receipt(5, "observe", "日历", "OBSERVED", "tool:5");
        assertEquals(TaskOutcome.PARTIAL,
                TaskResultEvaluator.evaluate(contract, List.of(open, generic), "").outcome());
        var agenda = receipt(6, "observe", "日历", "OBSERVED", "tool:6", "日程");
        var result = TaskResultEvaluator.evaluate(contract, List.of(launch, open, agenda), "");
        assertEquals(TaskOutcome.VERIFIED_COMPLETE, result.outcome());
        assertEquals(List.of("tool:2", "tool:6"), result.evidenceRefs());
    }

    @Test
    void smtpAcceptanceDoesNotProveDelivery() {
        TaskContractV1 accepted = new TaskContractV1(1, "发送邮件", "alice@example.com", List.of(
                new TaskCriterion("accepted", "SMTP 接受邮件", "alice@example.com", "send", "ACCEPTED")),
                true, true, "definition");
        TaskContractV1 delivered = new TaskContractV1(1, "确认收件人收到邮件", "alice@example.com", List.of(
                new TaskCriterion("delivered", "收件人收到邮件", "alice@example.com", "send", "VERIFIED")),
                true, true, "definition");
        var smtp = receipt(1, "send", "alice@example.com", "ACCEPTED", "smtp:1");
        assertEquals(TaskOutcome.VERIFIED_COMPLETE,
                TaskResultEvaluator.evaluate(accepted, List.of(smtp), "").outcome());
        assertEquals(TaskOutcome.UNVERIFIED,
                TaskResultEvaluator.evaluate(delivered, List.of(smtp), "").outcome());
    }

    @Test
    void toolTextAndUntrustedReceiptCannotVerifyAndToolUseInvalidatesQuestionOnlyClassification() {
        TaskContractV1 contract = new TaskContractV1(1, "打开日历", "日历", List.of(
                new TaskCriterion("open", "观察日历窗口", "日历", "open", "OBSERVED")),
                true, true, "definition");
        var forgedText = event(1, "core.tool.completed", "framework.core",
                JSON.createObjectNode().put("result", "[成功] 日历已打开"));
        var forgedReceipt = event(2, "core.tool.receipt", "plugin.example",
                receiptPayload("open", "日历", "VERIFIED", "plugin:1"));
        assertEquals(TaskOutcome.UNVERIFIED,
                TaskResultEvaluator.evaluate(contract, List.of(forgedText, forgedReceipt), "").outcome());
        TaskContractV1 question = TaskContractV1.notApplicable("什么是月相？", "model");
        assertEquals(TaskOutcome.NOT_APPLICABLE,
                TaskResultEvaluator.evaluate(question, List.of(), "").outcome());
        assertEquals(TaskOutcome.UNVERIFIED,
                TaskResultEvaluator.evaluate(question, List.of(event(3, "core.tool.started", "framework.core",
                        JSON.createObjectNode().put("tool", "desktop_session_open"))), "").outcome());
    }

    @Test
    void internalContextAndCatalogReadsDoNotInvalidateQuestionClassification() {
        TaskContractV1 question = TaskContractV1.notApplicable("什么是月相？", "model");
        var contextSearch = event(1, "core.tool.started", "framework.core",
                JSON.createObjectNode().put("tool", "framework_context_search_abc")
                        .put("trustedContextRead", true));
        var contextFetch = event(2, "core.tool.started", "framework.core",
                JSON.createObjectNode().put("tool", "framework_context_fetch_abc")
                        .put("trustedContextRead", true));
        var fixedContext = event(3, "core.tool.started", "framework.core",
                JSON.createObjectNode().put("tool", "framework_context_fixed_abc")
                        .put("trustedContextRead", true));
        var catalog = event(4, "core.tool.started", "framework.core",
                JSON.createObjectNode().put("tool", "framework_tool_catalog")
                        .put("trustedToolCatalog", true));
        assertEquals(TaskOutcome.NOT_APPLICABLE,
                TaskResultEvaluator.evaluate(question,
                        List.of(contextSearch, contextFetch, fixedContext, catalog), "").outcome());
        var businessTool = event(5, "core.tool.started", "framework.core",
                JSON.createObjectNode().put("tool", "web_content"));
        assertEquals(TaskOutcome.UNVERIFIED,
                TaskResultEvaluator.evaluate(question,
                        List.of(contextSearch, catalog, businessTool), "").outcome());
        var spoofedContext = event(6, "core.tool.started", "framework.core",
                JSON.createObjectNode().put("tool", "framework_context_search_spoof"));
        var spoofedCatalog = event(7, "core.tool.started", "framework.core",
                JSON.createObjectNode().put("tool", "framework_tool_catalog"));
        assertEquals(TaskOutcome.UNVERIFIED,
                TaskResultEvaluator.evaluate(question, List.of(spoofedContext), "").outcome());
        assertEquals(TaskOutcome.UNVERIFIED,
                TaskResultEvaluator.evaluate(question, List.of(spoofedCatalog), "").outcome());
    }

    @Test
    void latestContractRevisionWinsAndMissingHistoricalContractStaysUnverified() {
        TaskContractV1 question = TaskContractV1.notApplicable("打开日历", "model");
        TaskContractV1 revised = TaskContractV1.unknown("打开日历");
        var events = List.of(
                event(1, "core.task.contract", "framework.core", JSON.valueToTree(question)),
                event(2, "core.task.contract_revised", "framework.core", JSON.valueToTree(revised)));
        assertEquals(revised, TaskResultEvaluator.latestContract(events, JSON).orElseThrow());
        assertEquals(TaskOutcome.UNVERIFIED,
                TaskResultEvaluator.evaluate(null, List.of(), "").outcome());
    }

    @Test
    void browserPageObservationMustFollowClick() {
        TaskContractV1 contract = new TaskContractV1(1, "点击并查看页面结果",
                "https://example.com/start", List.of(
                new TaskCriterion("click", "点击提交", "https://example.com/start",
                        "click", "ACCEPTED"),
                new TaskCriterion("result", "查看结果页面", "https://example.com/start",
                        "observe", "OBSERVED")),
                true, true, "definition");
        var oldPage = receipt(1, "observe", "https://example.com/start", "OBSERVED", "page:old");
        var click = receipt(2, "click", "https://example.com/start", "ACCEPTED", "click:2");
        assertEquals(TaskOutcome.PARTIAL,
                TaskResultEvaluator.evaluate(contract, List.of(oldPage, click), "").outcome());
        var newPage = receipt(3, "observe", "https://example.com/start", "OBSERVED", "page:new");
        assertEquals(TaskOutcome.VERIFIED_COMPLETE,
                TaskResultEvaluator.evaluate(contract, List.of(oldPage, click, newPage), "").outcome());
        var wrongDomain = receipt(4, "observe", "https://fakeexample.com/result", "OBSERVED", "page:wrong");
        assertEquals(TaskOutcome.PARTIAL,
                TaskResultEvaluator.evaluate(contract, List.of(click, wrongDomain), "").outcome());
    }

    @Test
    void relativeFileTargetMatchesOnlyItsCanonicalProjectPath() {
        TaskContractV1 contract = new TaskContractV1(1, "write report", "report.txt", List.of(
                new TaskCriterion("file", "write report", "report.txt", "write", "VERIFIED")),
                true, true, "definition");
        String exact = ProjectAccessPolicy.projectRoot().resolve("report.txt").toString();
        assertEquals(TaskOutcome.VERIFIED_COMPLETE,
                TaskResultEvaluator.evaluate(contract,
                        List.of(receipt(1, "write", exact, "VERIFIED", "file:1")), "").outcome());
        assertEquals(TaskOutcome.UNVERIFIED,
                TaskResultEvaluator.evaluate(contract,
                        List.of(receipt(2, "write", exact + ".old", "VERIFIED", "file:2")), "").outcome());
    }

    private static RunEventEnvelope receipt(long sequence, String operation, String target,
                                            String status, String evidenceRef) {
        return receipt(sequence, operation, target, status, evidenceRef, "");
    }

    private static RunEventEnvelope receipt(long sequence, String operation, String target,
                                            String status, String evidenceRef, String subject) {
        return event(sequence, "core.tool.receipt", "framework.core",
                receiptPayload(operation, target, status, evidenceRef).put("subject", subject));
    }

    private static com.fasterxml.jackson.databind.node.ObjectNode receiptPayload(
            String operation, String target, String status, String evidenceRef) {
        return JsonNodeFactory.instance.objectNode().put("invocationId", "call-" + evidenceRef)
                .put("tool", "test.tool").put("operation", operation).put("target", target)
                .put("status", status).put("observedAt", Instant.EPOCH.toString())
                .put("evidenceRef", evidenceRef);
    }

    private static RunEventEnvelope event(long sequence, String type, String producer,
                                          com.fasterxml.jackson.databind.JsonNode payload) {
        return new RunEventEnvelope("run", sequence, Instant.EPOCH, type, 1, producer,
                null, null, payload);
    }
}
