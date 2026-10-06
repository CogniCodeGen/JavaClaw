package com.javaclaw.application.chat;

import com.javaclaw.framework.api.TaskOutcome;
import com.javaclaw.framework.api.TaskResult;
import com.javaclaw.util.SensitiveDataRedactor;

import java.util.List;
import java.util.Locale;

/** User-facing projection of task acceptance, separate from message delivery and run termination. */
public final class TaskResultDisplay {
    private TaskResultDisplay() {}

    public static String append(String reply, TaskResult result) {
        return append(reply, result, "");
    }

    public static String append(String reply, TaskResult result, String userRequest) {
        String text = reply == null ? "" : reply.stripTrailing();
        String status = format(result, userRequest);
        if (status.isEmpty()) return text;
        return text.isEmpty() ? status : text + "\n\n" + status;
    }

    public static String format(TaskResult result) {
        return format(result, "");
    }

    public static String format(TaskResult result, String userRequest) {
        if (result == null || result.outcome() == TaskOutcome.NOT_APPLICABLE) return "";
        boolean english = englishDisplay(userRequest);
        StringBuilder output = new StringBuilder();
        switch (result.outcome()) {
            case VERIFIED_COMPLETE -> output.append(english
                    ? "> ✅ Task complete (criteria verified)" : "> ✅ 任务已完成（完成条件已核验）");
            case DELIVERED -> output.append(english ? "> ✓ Response delivered" : "> ✓ 回答已交付");
            case PARTIAL -> output.append(english ? "> ⚠ Task partially complete" : "> ⚠ 任务部分完成");
            case BLOCKED -> output.append(english ? "> ⛔ Task blocked" : "> ⛔ 任务受阻");
            case UNVERIFIED -> output.append(english ? "> ◇ Task result unverified" : "> ◇ 任务结果未验证");
            case NOT_APPLICABLE -> { return ""; }
        }
        if (!result.satisfiedCriteria().isEmpty()) {
            output.append(english ? "\n> Done: " : "\n> 已做：")
                    .append(join(result.satisfiedCriteria(), english));
        }
        if (result.outcome() != TaskOutcome.VERIFIED_COMPLETE
                && result.outcome() != TaskOutcome.DELIVERED) {
            if (!result.unmetCriteria().isEmpty()) {
                output.append(english ? "\n> Not done or unconfirmed: " : "\n> 未做或未确认：")
                        .append(join(result.unmetCriteria(), english));
            } else if ("TASK_CONTRACT_UNRELIABLE".equals(result.stopReason())) {
                output.append(english ? "\n> Incomplete: acceptance criteria unavailable"
                        : "\n> 未完成：验收条件未建立");
            } else {
                output.append(english ? "\n> Incomplete: missing verifiable evidence"
                        : "\n> 未完成：缺少可核验的完成证据");
            }
            if (result.stopReason() != null && !result.stopReason().isBlank()) {
                String stopCode = stopCode(result.stopReason());
                boolean terminalCause = "RUN_TIMEOUT".equals(stopCode)
                        || "TASK_SUPERSEDED".equals(stopCode);
                output.append(english ? "\n> Reason: " : terminalCause ? "\n> 原因：" : "\n> 待确认：")
                        .append(singleLine(reason(result.stopReason(), english)));
            }
        }
        if (!result.evidenceRefs().isEmpty()) {
            output.append(english ? "\n> Evidence: " : "\n> 证据：")
                    .append(join(result.evidenceRefs(), english));
        }
        return output.toString();
    }

    private static String join(List<String> values, boolean english) {
        return values.stream().filter(value -> value != null && !value.isBlank())
                .map(TaskResultDisplay::singleLine).distinct().limit(8)
                .reduce((left, right) -> left + (english ? "; " : "；") + right).orElse("—");
    }

    private static String singleLine(String text) {
        String safe = SensitiveDataRedactor.redactText(text);
        safe = safe.replaceAll("[\\r\\n\\t]+", " ").replace('>', '›').strip();
        return safe.length() > 300 ? safe.substring(0, 300) + "…" : safe;
    }

    private static String reason(String raw, boolean english) {
        String code = stopCode(raw);
        if (english) return switch (code) {
            case "TASK_CONTRACT_UNRELIABLE" -> "Reliable acceptance criteria were unavailable";
            case "TASK_CONTRACT_MISSING" -> "Task contract is missing";
            case "MISSING_TRUSTED_RECEIPT" -> "Trusted execution or observation evidence is missing";
            case "NO_PROGRESS" -> "No progress after repair";
            case "BUDGET_EXHAUSTED", "TOKEN_BUDGET", "MAX_ITERATIONS" -> "Run budget exhausted";
            case "APPROVAL_DENIED", "TOOL_APPROVAL_DENIED" -> "Required action was not approved";
            case "TOOL_USED_AFTER_NOT_APPLICABLE_CLASSIFICATION" -> "Tool use requires another verification";
            case "CANCELLED", "USER_CANCELLED" -> "Run cancelled";
            case "RUN_TIMEOUT" -> "Run timed out";
            case "TASK_SUPERSEDED" -> "Task replaced by a newer request";
            default -> raw;
        };
        return switch (code) {
            case "TASK_CONTRACT_UNRELIABLE" -> "系统未能生成可靠的任务完成条件";
            case "TASK_CONTRACT_MISSING" -> "缺少任务契约";
            case "MISSING_TRUSTED_RECEIPT" -> "缺少可信执行或观察证据";
            case "NO_PROGRESS" -> "补做后没有新进展";
            case "BUDGET_EXHAUSTED", "TOKEN_BUDGET", "MAX_ITERATIONS" -> "运行预算已用尽";
            case "APPROVAL_DENIED", "TOOL_APPROVAL_DENIED" -> "所需操作未获授权";
            case "TOOL_USED_AFTER_NOT_APPLICABLE_CLASSIFICATION" -> "执行过工具，需要重新核验任务结果";
            case "CANCELLED", "USER_CANCELLED" -> "运行已取消";
            case "RUN_TIMEOUT" -> "运行超时";
            case "TASK_SUPERSEDED" -> "任务已被新请求替换";
            default -> raw;
        };
    }

    /** The host's cancellation envelope is exact; surrounding prose is never a control code. */
    private static String stopCode(String raw) {
        String code = raw.strip().toUpperCase(Locale.ROOT);
        return switch (code) {
            case "RUN_CANCELLED: RUN_TIMEOUT" -> "RUN_TIMEOUT";
            case "RUN_CANCELLED: TASK_SUPERSEDED" -> "TASK_SUPERSEDED";
            default -> code;
        };
    }

    /** Language selection affects presentation only; it never changes task acceptance. */
    private static boolean englishDisplay(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) return false;
        int han = 0;
        int latin = 0;
        for (int index = 0; index < userRequest.length(); index++) {
            char value = userRequest.charAt(index);
            if (Character.UnicodeScript.of(value) == Character.UnicodeScript.HAN) han++;
            else if (Character.UnicodeScript.of(value) == Character.UnicodeScript.LATIN
                    && Character.isLetter(value)) latin++;
        }
        return latin > han * 2 && latin >= 4;
    }
}
