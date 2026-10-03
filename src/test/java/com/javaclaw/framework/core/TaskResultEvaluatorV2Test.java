package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.TaskContractV2;
import com.javaclaw.framework.api.TaskCriterion;
import com.javaclaw.framework.api.TaskOutcome;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskResultEvaluatorV2Test {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String OLD = "123e4567-e89b-42d3-a456-426614174000";
    private static final String NEW = "123e4567-e89b-42d3-a456-426614174001";

    @Test
    void requiresFreshViewProofOnSameExactSessionAndWindowAfterClick() {
        TaskContractV2 contract = contract();
        var open = open(1);
        var oldView = observe(2, OLD, "最近项目", true, "session-1", "window-1");
        var click = click(3, "ACCEPTED", "window-1");
        assertEquals(TaskOutcome.PARTIAL, TaskResultEvaluator.evaluateV2(contract,
                List.of(open, oldView, click), "").outcome());
        assertEquals(TaskOutcome.PARTIAL, TaskResultEvaluator.evaluateV2(contract,
                List.of(open, click, observe(4, NEW, "下载目录", true,
                        "session-2", "window-1")), "").outcome());
        assertEquals(TaskOutcome.PARTIAL, TaskResultEvaluator.evaluateV2(contract,
                List.of(open, click, observe(4, NEW, "下载目录", true,
                        "session-1", "window-2")), "").outcome());
        assertEquals(TaskOutcome.PARTIAL, TaskResultEvaluator.evaluateV2(contract,
                List.of(open, click, observe(4, OLD, "下载目录", true,
                        "session-1", "window-1")), "").outcome());
        assertEquals(TaskOutcome.PARTIAL, TaskResultEvaluator.evaluateV2(contract,
                List.of(open, click, observe(4, NEW, "下载目录", false,
                        "session-1", "window-1")), "").outcome());
        assertEquals(TaskOutcome.VERIFIED_COMPLETE, TaskResultEvaluator.evaluateV2(contract,
                List.of(open, oldView, click, observe(4, NEW, "下载目录", true,
                        "session-1", "window-1")), "").outcome());
    }

    @Test
    void uncertainClickCanBeReconciledByFinalViewStateButNotByNavigationText() {
        TaskContractV2 contract = contract();
        var unknown = click(3, "UNKNOWN", "window-1");
        var baseline = observe(2, OLD, "最近项目", true, "session-1", "window-1");
        var navOnly = observe(4, NEW, "下载目录", false, "session-1", "window-1");
        assertEquals(TaskOutcome.PARTIAL, TaskResultEvaluator.evaluateV2(contract,
                List.of(open(1), baseline, unknown, navOnly), "").outcome());
        assertTrue(TaskResultEvaluator.verifiedActionEvidence(contract,
                List.of(open(1), baseline, unknown, navOnly)).isEmpty());

        var finalView = observe(4, NEW, "下载目录", true, "session-1", "window-1");
        assertEquals(TaskOutcome.VERIFIED_COMPLETE, TaskResultEvaluator.evaluateV2(contract,
                List.of(open(1), baseline, unknown, finalView), "").outcome());
        assertEquals(List.of(new TaskResultEvaluator.VerifiedActionEvidence(
                        "call-3", "session-1", "window-1", OLD, NEW)),
                TaskResultEvaluator.verifiedActionEvidence(contract,
                        List.of(open(1), baseline, unknown, finalView)));
        TaskContractV2 explicitClick = new TaskContractV2(2, "点击并查看下载目录", "文件管理器", List.of(
                new TaskCriterion("open", "打开 文件管理器", "文件管理器", "open", "ACCEPTED"),
                new TaskCriterion("click", "点击下载目录", "文件管理器", "click", "ACCEPTED"),
                new TaskCriterion("view", "查看下载目录主页面", "文件管理器", "observe", "OBSERVED", "下载目录")),
                true, true, "definition");
        assertEquals(TaskOutcome.VERIFIED_COMPLETE, TaskResultEvaluator.evaluateV2(explicitClick,
                List.of(open(1), baseline, unknown, finalView), "").outcome());
    }

    @Test
    void terminalViewDoesNotReconcileAnUncertainClickWithoutAProvedTransition() {
        TaskContractV2 contract = contract();
        var unknown = click(3, "UNKNOWN", "window-1");
        var downloads = observe(4, NEW, "下载目录", true, "session-1", "window-1");

        var alreadyInDownloads = observe(2, OLD, "下载目录", true,
                "session-1", "window-1");
        assertEquals(TaskOutcome.VERIFIED_COMPLETE, TaskResultEvaluator.evaluateV2(contract,
                List.of(open(1), alreadyInDownloads, unknown, downloads), "").outcome());
        assertTrue(TaskResultEvaluator.verifiedActionEvidence(contract,
                List.of(open(1), alreadyInDownloads, unknown, downloads)).isEmpty());

        var unclassified = observe(2, OLD, "最近项目", false,
                "session-1", "window-1");
        assertEquals(TaskOutcome.VERIFIED_COMPLETE, TaskResultEvaluator.evaluateV2(contract,
                List.of(open(1), unclassified, unknown, downloads), "").outcome());
        assertTrue(TaskResultEvaluator.verifiedActionEvidence(contract,
                List.of(open(1), unclassified, unknown, downloads)).isEmpty());
    }

    @Test
    void laterInputOnTheSameWindowCannotValidateAnEarlierUncertainClick() {
        TaskContractV2 contract = contract();
        ObjectNode secondClickPayload = ((ObjectNode) click(5, "ACCEPTED", "window-1")
                .payload()).deepCopy();
        ((ObjectNode) secondClickPayload.path("metadata")).put("observationId",
                "123e4567-e89b-42d3-a456-426614174002");
        var intermediate = observe(4, "123e4567-e89b-42d3-a456-426614174002",
                "文档目录", true, "session-1", "window-1");
        var receipts = List.of(open(1), observe(2, OLD, "最近项目", true,
                "session-1", "window-1"), click(3, "UNKNOWN", "window-1"),
                intermediate, event(5, secondClickPayload),
                observe(6, NEW, "下载目录", true, "session-1", "window-1"));

        assertEquals(TaskOutcome.VERIFIED_COMPLETE,
                TaskResultEvaluator.evaluateV2(contract, receipts, "").outcome());
        assertTrue(TaskResultEvaluator.verifiedActionEvidence(contract, receipts).isEmpty());
    }

    @Test
    void localCheckpointVerifiesOnlyTheAdjacentPredeclaredViewBeforeTaskCompletion() {
        TaskContractV2 multipleViews = new TaskContractV2(2, "打开应用并依次查看下载目录和文档目录", "文件管理器",
                List.of(new TaskCriterion("open", "打开应用", "文件管理器", "open", "ACCEPTED"),
                        new TaskCriterion("click-downloads", "打开下载目录", "文件管理器", "click", "ACCEPTED"),
                        new TaskCriterion("downloads", "看到下载目录", "文件管理器", "observe", "OBSERVED", "下载目录"),
                        new TaskCriterion("click-documents", "打开文档目录", "文件管理器", "click", "ACCEPTED"),
                        new TaskCriterion("documents", "看到文档目录", "文件管理器", "observe", "OBSERVED", "文档目录")),
                true, true, "definition");
        ObjectNode uncertainPayload = ((ObjectNode) click(3, "UNKNOWN", "window-1").payload()).deepCopy();
        ((ObjectNode) uncertainPayload.path("metadata")).put("delivery", "MAYBE_SENT");
        var uncertain = event(3, uncertainPayload);
        var baseline = observe(2, OLD, "最近项目", true, "session-1", "window-1");
        var downloads = observe(4, NEW, "下载目录", true, "session-1", "window-1");
        assertEquals(TaskOutcome.PARTIAL, TaskResultEvaluator.evaluateV2(multipleViews,
                List.of(open(1), baseline, uncertain, downloads), "").outcome());
        var proof = new TaskResultEvaluator.VerifiedActionEvidence(
                "call-3", "session-1", "window-1", OLD, NEW);
        assertEquals(List.of(new TaskResultEvaluator.VerifiedCheckpointEvidence(
                        proof, "click-downloads", "downloads", "下载目录", "tool:4")),
                TaskResultEvaluator.verifiedCheckpointEvidence(multipleViews,
                        List.of(open(1), baseline, uncertain, downloads)));
        assertTrue(TaskResultEvaluator.verifiedCheckpointEvidence(multipleViews,
                List.of(open(1), observe(2, OLD, "下载目录", true,
                        "session-1", "window-1"), uncertain, downloads)).isEmpty(),
                "the requested state already present before the click proves no transition");
        assertTrue(TaskResultEvaluator.verifiedCheckpointEvidence(multipleViews,
                List.of(open(1), baseline, uncertain,
                        observe(4, NEW, "文档目录", true, "session-1", "window-1"))).isEmpty(),
                "a later unrelated view criterion must not release the first click");
        assertTrue(TaskResultEvaluator.verifiedCheckpointEvidence(multipleViews,
                List.of(open(1), observe(2, OLD, "最近项目", false,
                        "session-1", "window-1"), uncertain, downloads)).isEmpty(),
                "the baseline must itself contain structured view evidence");
    }

    @Test
    void schemaTwoContractDoesNotImplicitlyUpgradeLegacyEvents() {
        TaskContractV2 contract = contract();
        ObjectNode payload = (ObjectNode) JSON.valueToTree(contract);
        RunEventEnvelope v2 = new RunEventEnvelope("run", 1, Instant.EPOCH,
                "core.task.contract", 2, "framework.core", null, null, payload);
        assertTrue(TaskResultEvaluator.latestContract(List.of(v2), JSON).isEmpty());
        assertEquals(contract, TaskResultEvaluator.latestContractV2(List.of(v2), JSON).orElseThrow());
    }

    @Test
    void desktopClickAndObservationWithoutAnOpenedSessionCannotFallBackToLegacyMatching() {
        TaskContractV2 existingSession = new TaskContractV2(2, "点击应用的下载目录并查看", "文件管理器",
                List.of(new TaskCriterion("click", "点击下载目录", "文件管理器", "click", "ACCEPTED"),
                        new TaskCriterion("view", "查看下载目录", "文件管理器", "observe", "OBSERVED", "下载目录")),
                true, true, "definition");
        var click = click(3, "ACCEPTED", "window-1");
        var visible = observe(4, NEW, "下载目录", true, "session-1", "window-1");
        assertEquals(TaskOutcome.UNVERIFIED, TaskResultEvaluator.evaluateV2(existingSession,
                List.of(click, visible), "").outcome());

        var baseline = observe(2, OLD, "最近项目", false, "session-1", "window-1");
        assertEquals(TaskOutcome.VERIFIED_COMPLETE, TaskResultEvaluator.evaluateV2(existingSession,
                List.of(baseline, click, visible), "").outcome());
        assertEquals(TaskOutcome.UNVERIFIED, TaskResultEvaluator.evaluateV2(existingSession,
                List.of(observe(2, NEW, "最近项目", false, "session-1", "window-1"),
                        click, visible), "").outcome());
        assertEquals(TaskOutcome.UNVERIFIED, TaskResultEvaluator.evaluateV2(existingSession,
                List.of(observe(2, OLD, "最近项目", false, "session-1", "window-2"),
                        click, visible), "").outcome());
        ObjectNode wrongGeneration = ((ObjectNode) baseline.payload()).deepCopy();
        ((ObjectNode) wrongGeneration.path("metadata")).put("windowGeneration", "2");
        assertEquals(TaskOutcome.UNVERIFIED, TaskResultEvaluator.evaluateV2(existingSession,
                List.of(event(2, wrongGeneration), click, visible), "").outcome());

        var uncertain = click(3, "UNKNOWN", "window-1");
        assertEquals(TaskOutcome.VERIFIED_COMPLETE, TaskResultEvaluator.evaluateV2(existingSession,
                List.of(baseline, uncertain, visible), "").outcome());
        assertTrue(TaskResultEvaluator.verifiedActionEvidence(existingSession,
                List.of(baseline, uncertain, visible)).isEmpty(),
                "an unclassified baseline cannot prove what the uncertain click changed");
        assertEquals(List.of(new TaskResultEvaluator.VerifiedActionEvidence(
                        "call-3", "session-1", "window-1", OLD, NEW)),
                TaskResultEvaluator.verifiedActionEvidence(existingSession,
                        List.of(observe(2, OLD, "最近项目", true,
                                "session-1", "window-1"), uncertain, visible)));

        TaskContractV2 observeOnly = new TaskContractV2(2, "查看应用下载目录", "文件管理器",
                List.of(new TaskCriterion("view", "查看下载目录", "文件管理器", "observe", "OBSERVED", "下载目录")),
                true, true, "definition");
        assertEquals(TaskOutcome.VERIFIED_COMPLETE, TaskResultEvaluator.evaluateV2(observeOnly,
                List.of(visible), "").outcome());
        assertEquals(TaskOutcome.UNVERIFIED, TaskResultEvaluator.evaluateV2(observeOnly,
                List.of(observe(4, NEW, "下载目录", false,
                        "session-1", "window-1")), "").outcome());
    }

    @Test
    void browserClickAndObservationWithoutDesktopReceiptsKeepTheirExistingRules() {
        TaskContractV2 browser = new TaskContractV2(2, "点击后查看页面",
                "https://example.com/start", List.of(
                new TaskCriterion("click", "点击链接", "https://example.com/start",
                        "click", "ACCEPTED"),
                new TaskCriterion("view", "查看页面", "https://example.com/start",
                        "observe", "OBSERVED")),
                true, true, "definition");
        ObjectNode click = payload(1, "click", "ACCEPTED", "").put("tool", "web_click")
                .put("target", "https://example.com/start");
        ObjectNode observed = payload(2, "observe", "OBSERVED", "").put("tool", "web_content")
                .put("target", "https://example.com/start");
        assertEquals(TaskOutcome.VERIFIED_COMPLETE, TaskResultEvaluator.evaluateV2(browser,
                List.of(event(1, click), event(2, observed)), "").outcome());
    }

    private static TaskContractV2 contract() {
        return new TaskContractV2(2, "打开文件管理器查看下载目录", "文件管理器", List.of(
                new TaskCriterion("open", "打开文件管理器", "文件管理器", "open", "ACCEPTED"),
                new TaskCriterion("view", "查看下载目录主页面", "文件管理器", "observe", "OBSERVED", "下载目录")),
                true, true, "definition");
    }

    private static RunEventEnvelope open(long sequence) {
        ObjectNode receipt = payload(sequence, "open", "ACCEPTED", "");
        receipt.putObject("metadata").put("sessionId", "session-1")
                .put("targetId", "window-1");
        return event(sequence, receipt);
    }

    private static RunEventEnvelope click(long sequence, String status, String targetId) {
        ObjectNode receipt = payload(sequence, "click", status, "");
        receipt.putObject("metadata").put("sessionId", "session-1")
                .put("targetId", targetId).put("observationId", OLD)
                .put("windowGeneration", "1").put("dispatchAttempted", "true");
        return event(sequence, receipt);
    }

    private static RunEventEnvelope observe(long sequence, String observationId,
            String subject, boolean mainContent, String sessionId, String targetId) {
        ObjectNode receipt = payload(sequence, "observe", "OBSERVED", subject);
        ObjectNode metadata = receipt.putObject("metadata");
        metadata.put("sessionId", sessionId).put("targetId", targetId)
                .put("observationId", observationId).put("windowGeneration", "1")
                .put("contentRevision", "2")
                .put("capturedAtMillis", Long.toString(sequence * 1000 + 100));
        if (mainContent) metadata.put("viewEvidence", "heading:1,1,20,10|content:1,20,90,40");
        return event(sequence, receipt);
    }

    private static ObjectNode payload(long sequence, String operation, String status,
                                      String subject) {
        return JsonNodeFactory.instance.objectNode().put("invocationId", "call-" + sequence)
                .put("tool", "desktop_session_" + operation)
                .put("operation", operation).put("target", "文件管理器").put("status", status)
                .put("observedAt", Instant.ofEpochMilli(sequence * 1000).toString())
                .put("evidenceRef", "tool:" + sequence).put("subject", subject);
    }

    private static RunEventEnvelope event(long sequence, ObjectNode payload) {
        return new RunEventEnvelope("run", sequence, Instant.ofEpochMilli(sequence * 1000),
                "core.tool.receipt", 1, "framework.core", null, null, payload);
    }
}
