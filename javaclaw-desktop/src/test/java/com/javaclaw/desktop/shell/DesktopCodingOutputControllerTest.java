package com.javaclaw.desktop.shell;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.ThreadId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.client.extension.CodingExecutionPoller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopCodingOutputControllerTest {
    @Test
    void 单在途并在切换会话和重连后丢弃迟到结果() {
        List<CompletableFuture<CodingExecutionPoller.Poll>> requests = new ArrayList<>();
        List<DesktopCodingOutputController.State> rendered = new ArrayList<>();
        var controller = new DesktopCodingOutputController(
                poller -> {
                    var response = new CompletableFuture<CodingExecutionPoller.Poll>();
                    requests.add(response);
                    return response;
                },
                rendered::add);
        var scope =
                new CodingExecutionPoller.Scope(WorkspaceId.random(), Optional.of(ThreadId.random()), Optional.empty());
        controller.bind(Optional.of(new DesktopCodingOutputController.Binding(scope, Instant.EPOCH)));
        controller.poll();
        controller.poll();
        assertEquals(1, requests.size());
        controller.bind(Optional.of(new DesktopCodingOutputController.Binding(scope, Instant.EPOCH.plusSeconds(1))));
        controller.poll();
        assertEquals(1, requests.size(), "旧请求结束前不向相同作用域叠加查询");
        requests.getFirst().completeExceptionally(new IllegalStateException("旧连接敏感信息"));
        assertTrue(rendered.getLast().failure().isEmpty());
        controller.poll();
        assertEquals(2, requests.size());
        controller.bind(Optional.empty());
        requests.getLast().complete(new CodingExecutionPoller.Poll(List.of(), false));
        controller.poll();
        assertEquals(2, requests.size());
        assertTrue(rendered.getLast().snapshots().isEmpty());
    }

    @Test
    void 同步与异步读取失败均可重试且不暴露异常细节() {
        List<DesktopCodingOutputController.State> rendered = new ArrayList<>();
        var controller = new DesktopCodingOutputController(
                poller -> {
                    throw new IllegalStateException("秘密令牌");
                },
                rendered::add);
        var scope = new CodingExecutionPoller.Scope(WorkspaceId.random(), Optional.empty(), Optional.empty());
        controller.bind(Optional.of(new DesktopCodingOutputController.Binding(scope, Instant.EPOCH)));
        controller.poll();
        controller.poll();
        assertEquals(3, rendered.size());
        assertTrue(rendered.getLast().failure().orElseThrow().contains("仍可"));
        assertTrue(rendered.stream().noneMatch(state -> state.toString().contains("秘密令牌")));
    }
}
