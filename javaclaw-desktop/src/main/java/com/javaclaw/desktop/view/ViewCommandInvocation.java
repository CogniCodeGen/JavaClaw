package com.javaclaw.desktop.view;

import java.util.Map;
import java.util.Objects;

/**
 * ViewSchema v2 已完成绑定和类型校验的 command。
 *
 * @param operation 扩展 operation
 * @param arguments 规范 JSON 可表示的参数
 * @param expectedRevision 目标资源版本
 * @param dangerous 是否需要危险操作确认
 */
public record ViewCommandInvocation(
        String operation, Map<String, Object> arguments, long expectedRevision, boolean dangerous) {
    /** 复制参数并校验调用。 */
    public ViewCommandInvocation {
        operation = Objects.requireNonNull(operation, "operation").strip();
        if (operation.isEmpty()) {
            throw new IllegalArgumentException("operation must not be blank");
        }
        arguments = Map.copyOf(Objects.requireNonNull(arguments, "arguments"));
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision must not be negative");
        }
    }
}
