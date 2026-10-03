package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.TaskContractV3;
import com.javaclaw.util.SensitiveDataRedactor;

import java.util.List;

/** Presents host planning failures without turning an unsuccessful repair into a user requirement. */
public final class TaskContractDiagnostics {
    private TaskContractDiagnostics() { }

    public static ObjectNode pausedOutput(ObjectMapper json, TaskContractV3 contract,
            boolean beforeExecution) {
        ObjectNode output = json.createObjectNode().put("kind", "task.contract.unreliable");
        output.set("reasonCodes", json.valueToTree(contract.reasonCodes()));
        output.set("unresolvedInputs", json.valueToTree(contract.unresolvedInputs()));
        String failure = planningFailure(contract.reasonCodes());
        StringBuilder text = new StringBuilder("任务尚未验证完成：");
        if (!failure.isBlank()) {
            text.append(failure).append("。系统暂未建立可靠的任务完成条件。");
            output.put("planningFailure", failureCode(contract.reasonCodes()));
        } else {
            text.append("验收条件规划未通过校验。");
            String unresolved = SensitiveDataRedactor.redactText(
                    String.join("；", contract.unresolvedInputs()));
            if (unresolved.isBlank()) text.append("系统暂未建立可靠的任务完成条件。");
            else text.append("待明确：").append(bounded(unresolved, 350)).append('。');
        }
        text.append(beforeExecution ? "已在执行后续操作前暂停。" : "已暂停后续操作，请查看本轮工具记录中的实际进展。");
        output.put("text", text.toString());
        return output;
    }

    private static String planningFailure(List<String> reasons) {
        return switch (failureCode(reasons)) {
            case "PLANNING_REPAIR_TIMEOUT" -> "验收条件的自动修复超时";
            case "PLANNING_REPAIR_FAILED" -> "验收条件的自动修复调用失败";
            case "PLANNING_BUDGET_EXHAUSTED" -> "验收条件规划的剩余时间不足";
            case "PLANNING_TIMEOUT" -> "验收条件规划超时";
            case "PLANNING_FAILED" -> "验收条件规划调用失败";
            case "MISSING_OBSERVABLE_SUBJECT" -> "验收计划缺少要核验的界面内容条件";
            case "INVALID_PLAN" -> "模型返回的验收计划格式不符合要求";
            default -> "";
        };
    }

    private static String failureCode(List<String> reasons) {
        for (String code : List.of("PLANNING_REPAIR_TIMEOUT", "PLANNING_REPAIR_FAILED",
                "PLANNING_BUDGET_EXHAUSTED", "PLANNING_TIMEOUT", "PLANNING_FAILED",
                "MISSING_OBSERVABLE_SUBJECT", "INVALID_PLAN")) {
            if (reasons.contains(code)) return code;
        }
        return "";
    }

    private static String bounded(String text, int limit) {
        return text.length() <= limit ? text : text.substring(0, limit) + "…";
    }
}
