package com.javaclaw.workflow.node;

import com.javaclaw.workflow.runtime.CancellationToken;
import com.javaclaw.workflow.runtime.GraphCancelledException;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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

    @Test
    void 阻塞式同步工具可在执行期间取消() throws Exception {
        CancellationToken cancellation = new CancellationToken();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Mono<ToolResultBlock> blockingCall = Mono.fromCallable(() -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                interrupted.countDown();
                throw e;
            }
            return null;
        });
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<Throwable> outcome = caller.submit(() -> {
                try {
                    ToolNodeExecutor.awaitToolCall(blockingCall, cancellation, "blocking_tool");
                    return null;
                } catch (Throwable error) {
                    return error;
                }
            });

            assertTrue(entered.await(2, TimeUnit.SECONDS), "同步工具应已开始执行");
            assertTrue(cancellation.cancel());
            assertInstanceOf(GraphCancelledException.class, outcome.get(2, TimeUnit.SECONDS));
            assertTrue(interrupted.await(2, TimeUnit.SECONDS), "取消应中断底层同步工具线程");
        } finally {
            cancellation.cancel();
            release.countDown();
            caller.shutdownNow();
        }
    }
}
