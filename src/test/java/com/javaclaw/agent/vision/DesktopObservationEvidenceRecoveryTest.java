package com.javaclaw.agent.vision;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.spi.JsonSchemaValidator;
import com.javaclaw.framework.spi.ModelTaskResult;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopObservationEvidenceRecoveryTest {
    private static final int FRAME_WIDTH = 3_024;
    private static final int FRAME_HEIGHT = 1_748;
    private static final List<DesktopObservationCondition> CONDITIONS = List.of(
            new DesktopObservationCondition("observe_local_models", "本地模型列表"));
    private final ObjectMapper json = new ObjectMapper();
    private final JsonSchemaValidator validator = new JsonSchemaValidator();

    @Test
    void 真实改写表格仅保留候选内已逐字可见的数量摘录() throws IOException {
        ObjectNode observed = fixture("paraphrased-label");
        String rawLabel = content(observed).path("label").asText();
        assertFalse(DesktopEvidenceExcerpt.containsVisibleText(observed.path("visibleText").asText(), rawLabel),
                "事故中的改写标签无法通过原有整段 OCR 原文校验");
        AtomicInteger calls = new AtomicInteger();
        VisionPreprocessor vision = new VisionPreprocessor(task -> {
            calls.incrementAndGet();
            assertTrue(validator.validate(task.outputSchema(), observed, "/output").isEmpty());
            assertTrue(task.input().path("instructions").asText().contains("不能把短摘录声称为完整目录"));
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    observed, "fake-vision", 1, 1, false, Map.of()));
        }, RunId.random());

        var result = vision.inspectDesktopFrameStructured(
                new BufferedImage(FRAME_WIDTH, FRAME_HEIGHT, BufferedImage.TYPE_INT_ARGB),
                "查看本地模型", true, CONDITIONS);

        assertNotNull(result);
        assertEquals(1, calls.get(), "摘录修复只使用既有输出，不重新请求模型");
        assertEquals(1, result.conditionEvidence().size());
        var evidence = result.conditionEvidence().getFirst();
        assertEquals("您有 10 个本地模型，占用了 58.96 GB 的磁盘空间。", evidence.content().label());
        assertEquals("observe_local_models", evidence.criterionId());
        assertEquals("本地模型列表", evidence.subject());
        assertEquals(0.96, evidence.confidence());
        assertEquals(0.96, evidence.content().confidence());
        assertEquals(content(observed).path("x").intValue(), evidence.content().x());
        assertTrue(rawLabel.contains(evidence.content().label()));
        assertTrue(result.visibleText().contains("text-embedding-qwen3-em..."));
    }

    @Test
    void 真实超长标签可通过候选Schema但只产出有界连续原文() throws IOException {
        ObjectNode observed = fixture("oversized-label");
        String rawLabel = content(observed).path("label").asText();
        assertTrue(rawLabel.length() > 500, "复现事故中 label 超过 Schema 500 字符的输出");
        assertTrue(validator.validate(DesktopObservationSchema.create(), observed, "/output").isEmpty());

        var result = parse(observed);

        assertEquals(1, result.conditionEvidence().size());
        String excerpt = result.conditionEvidence().getFirst().content().label();
        assertTrue(excerpt.length() <= 500);
        assertTrue(excerpt.length() < rawLabel.length());
        assertTrue(rawLabel.startsWith(excerpt));
        assertTrue(DesktopEvidenceExcerpt.containsVisibleText(result.visibleText(), excerpt));
        assertTrue(result.visibleText().contains("qwen3-0.6b"), "完整 OCR 不受摘录长度限制");
    }

    @Test
    void 真实缺少置信度的可选证明不能使OCR丢失也不能变为验收证据() throws IOException {
        ObjectNode observed = fixture("missing-confidence");
        assertFalse(evidence(observed).has("confidence"));
        assertFalse(observed.path("activeView").has("confidence"));
        assertTrue(validator.validate(DesktopObservationSchema.create(), observed, "/output").isEmpty());

        var result = parse(observed);

        assertNotNull(result);
        assertTrue(result.visibleText().contains("ui-tars-7b-dpo"));
        assertNull(result.activeView());
        assertTrue(result.conditionEvidence().isEmpty());
        assertFalse(evidence(observed).has("confidence"), "宿主不得补造置信度");
    }

    @Test
    void 证据或内容缺少置信度时保留观察且拒绝该证明() throws IOException {
        for (String missingAt : List.of("evidence", "content")) {
            ObjectNode observed = fixture("paraphrased-label");
            ObjectNode candidate = missingAt.equals("evidence") ? evidence(observed) : content(observed);
            candidate.remove("confidence");
            assertTrue(validator.validate(DesktopObservationSchema.create(), observed, "/output").isEmpty());

            var result = parse(observed);

            assertNotNull(result);
            assertTrue(result.conditionEvidence().isEmpty(), missingAt);
            assertFalse(candidate.has("confidence"));
        }
    }

    @Test
    void 摘录修复仍拒绝非法身份区域角色坐标弱置信度和敏感内容() throws IOException {
        for (int malformed = 0; malformed < 11; malformed++) {
            ObjectNode observed = fixture("paraphrased-label");
            ObjectNode candidate = evidence(observed);
            ObjectNode content = content(observed);
            switch (malformed) {
                case 0 -> candidate.put("criterionId", "screen_supplied");
                case 1 -> candidate.put("subject", "本地模型列表 ");
                case 2 -> candidate.put("region", "sidebar");
                case 3 -> content.put("role", "navigation");
                case 4 -> candidate.put("confidence", 0.84);
                case 5 -> content.put("confidence", 0.84);
                case 6 -> content.put("x", FRAME_WIDTH);
                case 7 -> content.put("height", -1);
                case 8 -> content.put("x", "265");
                case 9 -> content.put("x", 265.5);
                case 10 -> content.put("label", content.path("label").asText() + "; password: supersecret");
                default -> throw new AssertionError();
            }
            assertTrue(parse(observed).conditionEvidence().isEmpty(), "case " + malformed);
        }
    }

    @Test
    void 不能从摘要任意OCR行短导航词或单个词补造主区摘录() throws IOException {
        for (String label : List.of("当前显示全部本地模型", "已经完成；My Models；Q8_0",
                "已经完成；text-embedding-qwen3-em...")) {
            ObjectNode observed = fixture("paraphrased-label");
            content(observed).put("label", label);
            assertTrue(parse(observed).conditionEvidence().isEmpty(), label);
        }
    }

    @Test
    void 过长候选和过多分段无法触发无界修复() throws IOException {
        ObjectNode observed = fixture("paraphrased-label");
        String literal = "您有 10 个本地模型，占用了 58.96 GB 的磁盘空间。";
        content(observed).put("label", "伪造文字".repeat(1_100) + ";" + literal);
        assertTrue(parse(observed).conditionEvidence().isEmpty());
        content(observed).put("label", "改写|".repeat(33) + literal);
        assertTrue(parse(observed).conditionEvidence().isEmpty());
    }

    @Test
    void 部分原文相同不能删除模型词序或标点来伪造连续摘录() throws IOException {
        ObjectNode observed = fixture("paraphrased-label");
        content(observed).put("label", "qwen3 0.6B Qwen Q8_0");
        assertTrue(parse(observed).conditionEvidence().isEmpty(), "不能跳过模型名称列把词语拼成连续原文");
        content(observed).put("label", "您有 10 个本地模型 占用了 58.96 GB 的磁盘空间");
        assertTrue(parse(observed).conditionEvidence().isEmpty(), "原文标点不能被任意忽略");
    }

    private DesktopVisualObservation parse(ObjectNode observed) {
        return DesktopObservationParser.parse(observed, FRAME_WIDTH, FRAME_HEIGHT, CONDITIONS);
    }

    private ObjectNode fixture(String name) throws IOException {
        try (var resource = getClass().getResourceAsStream("/vision/lmstudio-" + name + ".json")) {
            assertNotNull(resource);
            return (ObjectNode) json.readTree(resource);
        }
    }

    private static ObjectNode evidence(ObjectNode observed) {
        return (ObjectNode) observed.path("conditionEvidence").get(0);
    }

    private static ObjectNode content(ObjectNode observed) {
        return (ObjectNode) evidence(observed).path("content");
    }
}
