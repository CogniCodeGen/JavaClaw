package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.framework.api.TaskContractV3;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskContractDiagnosticsTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void repairTimeoutIsShownInsteadOfAnUnconfirmedModelPrerequisite() {
        TaskContractV3 contract = contract(List.of("MODEL_UNRELIABLE", "UNRESOLVED_INPUTS",
                "PLANNING_REPAIR_TIMEOUT"), List.of("QQ application login status and account context"));
        var output = TaskContractDiagnostics.pausedOutput(json, contract, true);
        assertEquals("PLANNING_REPAIR_TIMEOUT", output.path("planningFailure").asText());
        assertTrue(output.path("text").asText().contains("自动修复超时"));
        assertFalse(output.path("text").asText().contains("待明确"));
        assertFalse(output.path("text").asText().contains("login status"));
        assertTrue(output.path("text").asText().contains("执行后续操作前暂停"));
        assertEquals(contract.unresolvedInputs(), json.convertValue(output.path("unresolvedInputs"), List.class));
    }

    @Test
    void missingHumanInformationRemainsVisibleWhenPlanningReturnedNormally() {
        var output = TaskContractDiagnostics.pausedOutput(json,
                contract(List.of("ACCOUNT_SELECTION_REQUIRED"), List.of("需要查看哪个账号")), true);
        assertTrue(output.path("text").asText().contains("待明确：需要查看哪个账号"));
        assertFalse(output.has("planningFailure"));
    }

    @Test
    void latestRepairFailureTakesPriorityOverTheInitialTimeout() {
        var output = TaskContractDiagnostics.pausedOutput(json,
                contract(List.of("PLANNING_TIMEOUT", "PLANNING_REPAIR_FAILED"), List.of()), true);
        assertEquals("PLANNING_REPAIR_FAILED", output.path("planningFailure").asText());
        assertTrue(output.path("text").asText().contains("自动修复调用失败"));
    }

    @Test
    void postExecutionPauseRefersToExistingToolProgress() {
        var output = TaskContractDiagnostics.pausedOutput(json,
                contract(List.of("PLANNING_BUDGET_EXHAUSTED"), List.of()), false);
        assertTrue(output.path("text").asText().contains("剩余时间不足"));
        assertTrue(output.path("text").asText().contains("本轮工具记录"));
        assertFalse(output.path("text").asText().contains("执行后续操作前暂停"));
    }

    @Test
    void missingContentConditionReportsTheHostValidationFailure() {
        var output = TaskContractDiagnostics.pausedOutput(json,
                contract(List.of("MISSING_OBSERVABLE_SUBJECT", "PLAN_REPAIR_EXHAUSTED"), List.of()), true);
        assertEquals("MISSING_OBSERVABLE_SUBJECT", output.path("planningFailure").asText());
        assertTrue(output.path("text").asText().contains("缺少要核验的界面内容条件"));
        assertFalse(output.path("text").asText().contains("待明确"));
    }

    @Test
    void longUnresolvedInputCannotHideTheExecutionStatusInTheUiLimit() {
        var output = TaskContractDiagnostics.pausedOutput(json,
                contract(List.of("AMBIGUOUS_GOAL"), List.of("内容".repeat(400))), true);
        assertTrue(output.path("text").asText().length() < 600);
        assertTrue(output.path("text").asText().endsWith("执行后续操作前暂停。"));
    }

    private static TaskContractV3 contract(List<String> reasons, List<String> inputs) {
        return new TaskContractV3(3, "查看联系人", List.of(), true, false,
                "model", reasons, inputs);
    }
}
