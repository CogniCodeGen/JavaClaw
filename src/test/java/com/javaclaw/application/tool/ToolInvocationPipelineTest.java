package com.javaclaw.application.tool;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolInvocationPipelineTest {

    @Test
    void authorizesAuditsAndExecutesInOrder() {
        List<String> order = new ArrayList<>();
        ToolInvocationPipeline pipeline = new ToolInvocationPipeline(invocation -> {
            order.add("authorize");
            return ToolAuthorization.allow();
        }, new ToolAuditSink() {
            @Override public void started(ToolInvocation invocation) {
                order.add("audit-start");
            }
            @Override public void completed(ToolInvocation invocation, ToolResult result) {
                order.add("audit-" + result.status());
            }
        });

        ToolResult result = pipeline.invoke(
                ToolInvocation.plugin("demo", "lookup", "查询"), () -> {
                    order.add("execute");
                    return "ok";
                });

        assertTrue(result.succeeded());
        assertEquals("ok", result.formatted());
        assertEquals(List.of("authorize", "audit-start", "execute", "audit-SUCCEEDED"), order);
    }

    @Test
    void rejectionNeverExecutesAndUsesStableFormatting() {
        AtomicBoolean executed = new AtomicBoolean();
        ToolInvocationPipeline pipeline = new ToolInvocationPipeline(
                invocation -> ToolAuthorization.reject("缺少授权"), noOpAudit());

        ToolResult result = pipeline.invoke(
                ToolInvocation.plugin("demo", "delete", "删除"), () -> {
                    executed.set(true);
                    return "unexpected";
                });

        assertFalse(executed.get());
        assertEquals(ToolResult.Status.REJECTED, result.status());
        assertEquals("工具调用已拒绝：缺少授权", result.formatted());
    }

    @Test
    void exceptionIsRedactedAndMappedWithoutStackTrace() {
        ToolInvocationPipeline pipeline = new ToolInvocationPipeline(
                invocation -> ToolAuthorization.allow(), noOpAudit());

        ToolResult result = pipeline.invoke(
                ToolInvocation.plugin("demo", "write", "写入"),
                () -> { throw new IllegalArgumentException("token=very-secret-value"); });

        assertEquals(ToolResult.Status.FAILED, result.status());
        assertEquals("工具调用失败[IllegalArgumentException]：<敏感内容已隐藏>",
                result.formatted());
    }

    private static ToolAuditSink noOpAudit() {
        return new ToolAuditSink() {
            @Override public void started(ToolInvocation invocation) {
            }
            @Override public void completed(ToolInvocation invocation, ToolResult result) {
            }
        };
    }
}
