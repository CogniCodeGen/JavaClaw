package com.javaclaw.memory.curation;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.memory.model.Episode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolEvidenceSelectorTest {
    @Test
    void selectsCompletedEvidenceByRelevanceWithoutFullTraceOrIntermediateEvents() {
        ArrayNode trace = JsonNodeFactory.instance.arrayNode();
        trace.add(event("core.tool.started", "secret argument", false));
        for (int index = 0; index < 12; index++) {
            trace.add(event("core.tool.completed", "unrelated-" + index + " ".repeat(900), false));
        }
        trace.add(event("core.tool.completed", "用户偏好蓝色主题", false));
        trace.add(event("core.tool.completed", "waiting-only", true));
        trace.add(event("core.tool.failed", "failed-only", false));
        Episode episode = new Episode("s", "请核实用户偏好蓝色主题", "结果确认用户偏好蓝色主题");
        episode.toolTraceJson = trace.toString();

        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        ToolEvidenceSelector.Selection selected = ToolEvidenceSelector.select(episode, json);
        ToolEvidenceSelector.Selection replay = ToolEvidenceSelector.select(episode, json);

        assertTrue(selected.candidateCount() > selected.selectedCount());
        assertEquals(selected.evidence(), replay.evidence(), "同一持久轨迹应产生稳定证据 ID");
        assertTrue(selected.plainText().contains("用户偏好蓝色主题"));
        assertFalse(selected.plainText().contains("secret argument"));
        assertFalse(selected.plainText().contains("waiting-only"));
        assertFalse(selected.plainText().contains("failed-only"));
        assertTrue(selected.plainText().length() <= 8_000);
        assertTrue(selected.evidence().size() <= 6);
        assertTrue(selected.evidence().get(0).hasNonNull("id"));
    }

    @Test
    void overFiveHundredTwelveFragmentsStillSelectsLateRelevantEvidence() {
        ArrayNode output = JsonNodeFactory.instance.arrayNode();
        for (int index = 0; index < 600; index++) output.add("无关字段 " + index);
        output.add("用户偏好蓝色主题");
        ObjectNode event = event("core.tool.completed", "", false);
        event.withObject("payload").set("output", output);
        Episode episode = new Episode("s", "用户偏好蓝色主题", "已记录");
        episode.toolTraceJson = JsonNodeFactory.instance.arrayNode().add(event).toString();

        ToolEvidenceSelector.Selection selected = ToolEvidenceSelector.select(
                episode, new com.fasterxml.jackson.databind.ObjectMapper());

        assertEquals(601, selected.candidateCount());
        assertTrue(selected.plainText().contains("用户偏好蓝色主题"));
        String identity = "0:600:web_get_text:用户偏好蓝色主题";
        String stableId = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
        assertTrue(selected.evidence().findValuesAsText("id").contains(stableId));
        assertEquals(selected.evidence(), ToolEvidenceSelector.select(
                episode, new com.fasterxml.jackson.databind.ObjectMapper()).evidence());
    }

    private static ObjectNode event(String type, String output, boolean waiting) {
        ObjectNode event = JsonNodeFactory.instance.objectNode();
        event.put("type", type);
        ObjectNode payload = event.putObject("payload");
        payload.put("tool", "web_get_text");
        payload.put("waitingInput", waiting);
        payload.put("output", output);
        payload.put("modelOutput", "duplicate-result-should-not-appear");
        payload.put("arguments", "secret argument");
        return event;
    }
}
