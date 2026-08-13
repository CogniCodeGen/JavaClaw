package com.javaclaw.workflow.node;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolNodeExecutorTest {
    @Test
    void 识别JavaClaw和模型工具失败结果() {
        assertTrue(ToolNodeExecutor.isFailureResult("[file_write][失败] 没有权限"));
        assertTrue(ToolNodeExecutor.isFailureResult("[browser][超时] 操作未完成"));
        assertTrue(ToolNodeExecutor.isFailureResult("Error: tool not found"));
        assertFalse(ToolNodeExecutor.isFailureResult("[file_read][成功] ok"));
        assertFalse(ToolNodeExecutor.isFailureResult("[file_read][成功] 文件正文包含 [失败] 字样"));
    }

}
