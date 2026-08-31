package com.javaclaw.desktop;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.javaclaw.sdk.model.AutomationDefinitionInfo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WorkflowGraphEditorTest {
    @Test
    void validGraphRequiresOneStartReachableNodesAndPositiveVisitBudgets() {
        assertNull(WorkflowGraphEditor.validate(List.of(
                node("start", "START", "agent", "", 1),
                node("agent", "AGENT", "end", "", 2),
                node("end", "END", "", "", 1))));
        assertEquals(
                "必须且只能有一个 START 节点",
                WorkflowGraphEditor.validate(List.of(
                        node("one", "START", "end", "", 1),
                        node("two", "START", "end", "", 1),
                        node("end", "END", "", "", 1))));
        assertEquals(
                "agent 指向不存在的节点 missing",
                WorkflowGraphEditor.validate(List.of(
                        node("start", "START", "agent", "", 1),
                        node("agent", "AGENT", "missing", "", 1),
                        node("end", "END", "", "", 1))));
        assertEquals(
                "存在不可达节点：orphan",
                WorkflowGraphEditor.validate(List.of(
                        node("start", "START", "end", "", 1),
                        node("end", "END", "", "", 1),
                        node("orphan", "TOOL", "end", "", 1))));
        assertEquals(
                "agent 的访问上限必须为正数",
                WorkflowGraphEditor.validate(List.of(
                        node("start", "START", "agent", "", 1),
                        node("agent", "AGENT", "end", "", 0),
                        node("end", "END", "", "", 1))));
    }

    private static AutomationDefinitionInfo.Node node(
            String id, String kind, String next, String otherwise, int maxVisits) {
        return new AutomationDefinitionInfo.Node(id, kind, next, otherwise, maxVisits, Map.of());
    }
}
