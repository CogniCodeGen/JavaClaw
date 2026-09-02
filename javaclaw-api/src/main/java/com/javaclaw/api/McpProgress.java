package com.javaclaw.api;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

/**
 * 已严格关联到单次 MCP 请求的进度通知。
 *
 * @param token 请求生成的 opaque token
 * @param progress 非负且单调递增的当前位置
 * @param total 可选总量；存在时不得小于当前位置
 * @param message 可选用户可见说明
 */
public record McpProgress(String token, BigDecimal progress, Optional<BigDecimal> total, Optional<String> message) {
    /** 复制并校验进度。 */
    public McpProgress {
        token = Preconditions.text(token, "token");
        if (token.length() > 256 || token.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("progress token is invalid");
        }
        BigDecimal checkedProgress = nonNegative(progress, "progress");
        Optional<BigDecimal> checkedTotal =
                Objects.requireNonNull(total, "total").map(value -> nonNegative(value, "total"));
        if (checkedTotal.filter(value -> value.compareTo(checkedProgress) < 0).isPresent()) {
            throw new IllegalArgumentException("progress total must not be less than progress");
        }
        progress = checkedProgress;
        total = checkedTotal;
        message = Objects.requireNonNull(message, "message").map(value -> boundedText(value, "message", 500));
    }

    private static BigDecimal nonNegative(BigDecimal value, String name) {
        BigDecimal checked = Objects.requireNonNull(value, name);
        if (checked.signum() < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return checked;
    }

    private static String boundedText(String value, String name, int maximum) {
        String checked = Preconditions.text(value, name);
        if (checked.length() > maximum) {
            throw new IllegalArgumentException(name + " is too long");
        }
        return checked;
    }
}
