package com.javaclaw.workflow.node;

import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolNodeExecutorTest {
    @Test
    void 识别JavaClaw和AgentScope工具失败结果() {
        assertTrue(ToolNodeExecutor.isFailureResult("[file_write][失败] 没有权限"));
        assertTrue(ToolNodeExecutor.isFailureResult("[browser][超时] 操作未完成"));
        assertTrue(ToolNodeExecutor.isFailureResult("Error: tool not found"));
        assertFalse(ToolNodeExecutor.isFailureResult("[file_read][成功] ok"));
        assertFalse(ToolNodeExecutor.isFailureResult("[file_read][成功] 文件正文包含 [失败] 字样"));
    }

    @Test
    void 窄工具组声明会停用其他组并移除Mcp() {
        Toolkit toolkit = new Toolkit();
        WorkflowToolGroupPolicy.KNOWN_TOOL_GROUPS.forEach(
                group -> toolkit.createToolGroup(group, group, true));

        WorkflowToolGroupPolicy.restrict(toolkit, List.of("knowledge"), true);

        assertEquals(Set.of("knowledge"), Set.copyOf(toolkit.getActiveGroups()));
    }

    @Test
    void Tool节点未声明工具组时保留本地组但移除Mcp() {
        Toolkit toolkit = new Toolkit();
        WorkflowToolGroupPolicy.KNOWN_TOOL_GROUPS.forEach(
                group -> toolkit.createToolGroup(group, group, true));

        WorkflowToolGroupPolicy.restrict(toolkit, List.of(), true);

        assertFalse(toolkit.getActiveGroups().contains("mcp"));
        assertTrue(toolkit.getActiveGroups().contains("knowledge"));
        assertTrue(toolkit.getActiveGroups().contains("system"));
    }
}
