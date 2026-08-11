package com.javaclaw.application.tool;

import com.javaclaw.util.SensitiveDataRedactor;

import java.time.Duration;

/**
 * 工具调用的统一执行管道：授权 → 开始审计 → 执行 → 异常映射 → 完成审计。
 *
 * <p>预期拒绝和执行异常都映射为 {@link ToolResult}，不会让不同调用入口各自发明错误文本。
 * {@link Error} 不会被吞掉。审计端口异常按尽力语义隔离，不能改变工具结果。</p>
 */
public final class ToolInvocationPipeline {

    private final ToolAuthorizer authorizer;
    private final ToolAuditSink audit;

    public ToolInvocationPipeline(ToolAuthorizer authorizer, ToolAuditSink audit) {
        this.authorizer = java.util.Objects.requireNonNull(authorizer, "authorizer");
        this.audit = java.util.Objects.requireNonNull(audit, "audit");
    }

    public ToolResult invoke(ToolInvocation invocation, ToolOperation operation) {
        java.util.Objects.requireNonNull(invocation, "invocation");
        java.util.Objects.requireNonNull(operation, "operation");
        long startedAt = System.nanoTime();
        ToolAuthorization authorization;
        try {
            authorization = java.util.Objects.requireNonNull(
                    authorizer.authorize(invocation), "authorizer returned null");
        } catch (RuntimeException failure) {
            return complete(invocation, failed(failure, startedAt));
        }
        if (!authorization.allowed()) {
            return complete(invocation, new ToolResult(ToolResult.Status.REJECTED, "", "Rejected",
                    safe(authorization.reason()), elapsed(startedAt)));
        }

        auditSafely(() -> audit.started(invocation));
        ToolResult result;
        try {
            result = new ToolResult(ToolResult.Status.SUCCEEDED,
                    operation.execute(), "", "", elapsed(startedAt));
        } catch (Exception failure) {
            result = failed(failure, startedAt);
        }
        return complete(invocation, result);
    }

    private ToolResult complete(ToolInvocation invocation, ToolResult result) {
        auditSafely(() -> audit.completed(invocation, result));
        return result;
    }

    private static ToolResult failed(Throwable failure, long startedAt) {
        return new ToolResult(ToolResult.Status.FAILED, "",
                failure.getClass().getSimpleName(), safe(failure.getMessage()), elapsed(startedAt));
    }

    private static String safe(String value) {
        String text = value == null || value.isBlank() ? "未提供原因" : value;
        return SensitiveDataRedactor.redactText(text);
    }

    private static Duration elapsed(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt);
    }

    private static void auditSafely(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ignored) {
            // 审计适配器必须自我容错；这里提供最后一道隔离，避免改变业务执行语义。
        }
    }
}
