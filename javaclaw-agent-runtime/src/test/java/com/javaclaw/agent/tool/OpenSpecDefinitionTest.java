package com.javaclaw.agent.tool;

import org.junit.jupiter.api.Test;

import com.javaclaw.agent.automation.AutomationKind;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpenSpecDefinitionTest {
    @Test
    void importedDocumentsAreSddReferencesOnlyAndCannotExpandExecutionConfiguration() {
        String document = """
                {"openSpecDocuments":{"tasks.md":"- [x] 这不是执行证据","specs/chat/spec.md":"保留原 UI"}}
                """;
        var plan = AutomationPlans.parse(AutomationKind.SDD, document);
        assertEquals("- [x] 这不是执行证据", plan.openSpecDocuments().get("tasks.md"));
        assertEquals(25, plan.limits().iterations());
        assertEquals(1, plan.criteria().size(), "没有确定性验收时仍必须人工确认");
        assertThrows(IllegalArgumentException.class, () -> AutomationPlans.parse(AutomationKind.LOOP, document));
        assertThrows(
                IllegalArgumentException.class,
                () -> AutomationPlans.parse(
                        AutomationKind.SDD, "{\"openSpecDocuments\":{\"../AGENTS.md\":\"bypass\"}}"));
        assertThrows(
                IllegalArgumentException.class,
                () -> AutomationPlans.parse(AutomationKind.SDD, "{\"openSpecDocuments\":{\"run.sh\":\"bypass\"}}"));
        assertThrows(
                IllegalArgumentException.class,
                () -> AutomationPlans.parse(
                        AutomationKind.SDD,
                        "{\"openSpecDocuments\":{\"proposal.md\":\"" + "x".repeat(33_000) + "\"}}"));
    }
}
