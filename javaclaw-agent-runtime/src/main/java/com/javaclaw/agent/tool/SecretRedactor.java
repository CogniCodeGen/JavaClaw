package com.javaclaw.agent.tool;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.javaclaw.core.api.ThreadItem;

/** 有界的尽力脱敏；仅减少已治理资料中的意外泄漏，不代替 SecretStore 隔离或权限边界。 */
public final class SecretRedactor {
    private static final int MAX_TEXT = 1_000_000;
    private static final Pattern ASSIGNMENT =
            Pattern.compile("(?i)(api[_-]?key|access[_-]?token|token|password|secret)\\s*[:=]\\s*[^\\s,;]+");
    private final List<String> literalSecrets;

    /** 对辅助模型的参考资料进行字段模式脱敏；不读取宿主环境或凭据库，也不自动将资料提升为指令。 */
    public static String redactReference(String value) {
        return new SecretRedactor(Map.of()).text(value);
    }

    SecretRedactor(Map<String, String> environment) {
        List<String> values = new ArrayList<>();
        environment.forEach((name, value) -> {
            String upper = name.toUpperCase(java.util.Locale.ROOT);
            if ((upper.contains("TOKEN")
                            || upper.contains("KEY")
                            || upper.contains("SECRET")
                            || upper.contains("PASSWORD"))
                    && value != null
                    && value.length() >= 6) {
                values.add(value);
            }
        });
        values.sort(Comparator.comparingInt(String::length).reversed());
        literalSecrets = List.copyOf(values);
    }

    String text(String value) {
        if (value == null) {
            return "";
        }
        String result = value.length() > MAX_TEXT ? value.substring(0, MAX_TEXT) + "\n[output truncated]" : value;
        for (String secret : literalSecrets) {
            result = result.replace(secret, "[REDACTED]");
        }
        return ASSIGNMENT.matcher(result).replaceAll("$1=[REDACTED]");
    }

    boolean endsWithSecretPrefix(String value) {
        for (String secret : literalSecrets) {
            for (int length = Math.min(secret.length() - 1, value.length()); length > 0; length--) {
                if (value.endsWith(secret.substring(0, length))) {
                    return true;
                }
            }
        }
        return false;
    }

    ThreadItem item(ThreadItem item) {
        if (item instanceof ThreadItem.CommandExecution command) {
            return new ThreadItem.CommandExecution(
                    command.argv().stream().map(this::text).toList(),
                    command.exitCode(),
                    text(command.stdout()),
                    text(command.stderr()),
                    command.timedOut(),
                    command.truncated());
        }
        if (item instanceof ThreadItem.DynamicToolCall call) {
            return new ThreadItem.DynamicToolCall(call.tool(), redact(call.result()));
        }
        if (item instanceof ThreadItem.FileChange change) {
            return new ThreadItem.FileChange(text(change.path()), change.change(), text(change.diff()));
        }
        if (item instanceof ThreadItem.McpToolCall call) {
            return new ThreadItem.McpToolCall(call.server(), call.tool(), redact(call.result()));
        }
        if (item instanceof ThreadItem.ErrorItem error) {
            return new ThreadItem.ErrorItem(error.code(), text(error.message()), error.retryable());
        }
        if (item instanceof ThreadItem.UserInputRequest request) {
            return new ThreadItem.UserInputRequest(
                    request.requestId(),
                    text(request.prompt()),
                    request.choices().stream().map(this::text).toList());
        }
        if (item instanceof ThreadItem.UserInputResponse response) {
            return new ThreadItem.UserInputResponse(response.requestId(), text(response.value()), response.cancelled());
        }
        return item;
    }

    private Map<String, String> redact(Map<String, String> values) {
        return values.entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey, entry -> text(entry.getValue())));
    }
}
