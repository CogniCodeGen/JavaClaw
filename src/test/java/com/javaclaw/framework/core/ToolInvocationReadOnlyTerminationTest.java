package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.desktop.agent.DesktopSessionTools;
import com.javaclaw.desktop.api.DesktopSessionOwner;
import com.javaclaw.desktop.api.DesktopSessionService;
import com.javaclaw.framework.api.*;
import com.javaclaw.framework.spi.*;
import com.javaclaw.framework.springai.SpringAiAnnotatedToolRegistry;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ToolInvocationReadOnlyTerminationTest {
    @Test
    void 宿主桌面观察逻辑超时可收尾且不会解除已有未知输入门禁() throws Exception {
        var fixture = fixture(false);
        var events = new CopyOnWriteArrayList<String>();
        fixture.control.restoreEffectStart("input", "input", "input-effect", false,
                ToolEffectPolicy.OBSERVATION_GATED, "desktop.target:window");
        fixture.control.restoreEffectReceipt("input", EffectReceiptV1.Status.UNKNOWN, "MAYBE_SENT");
        assertTrue(fixture.control.hasPendingDesktopInput());
        var result = fixture.gateway.invoke(fixture.request((type, version, producer, payload) -> events.add(type)))
                .toCompletableFuture();
        fixture.executor.fail(new TimeoutException("视觉模型任务已超时"));
        var failure = assertThrows(ExecutionException.class, () -> result.get(1, TimeUnit.SECONDS));
        assertInstanceOf(TimeoutException.class, failure.getCause());
        assertFalse(fixture.executor.termination.isDone());
        assertTrue(events.contains("core.tool.failed"));
        assertFalse(events.contains("core.tool.completed"));
        assertTrue(fixture.control.hasPendingDesktopInput());
        fixture.executor.termination.complete(null);
        assertEquals(1, events.stream().filter("core.tool.failed"::equals).count());
    }

    @Test
    void 只读失败的事件接收器阻塞也不能使公共调用再次挂住() throws Exception {
        var fixture = fixture(false);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var events = new CopyOnWriteArrayList<String>();
        var result = fixture.gateway.invoke(fixture.request((type, version, producer, payload) -> {
            if (type.equals("core.step.failed")) {
                entered.countDown();
                while (release.getCount() != 0) {
                    try { release.await(); }
                    catch (InterruptedException ignored) { /* 模拟不可中断的审计写入。 */ }
                }
            }
            events.add(type);
        })).toCompletableFuture();
        try {
            fixture.executor.fail(new TimeoutException("内层观察任务已超时"));
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            var failure = assertThrows(ExecutionException.class, () -> result.get(3, TimeUnit.SECONDS));
            assertInstanceOf(TimeoutException.class, failure.getCause());
            assertEquals("内层观察任务已超时", failure.getCause().getMessage());
        } finally { release.countDown(); fixture.executor.termination.complete(null); }
        assertFalse(events.contains("core.tool.completed"));
        assertFalse(events.contains("core.tool.receipt"), "超时的旧观察不得补写新基线并解除输入门禁");
    }

    @Test
    void 插件自称只读也不能绕过副作用物理终止保护() throws Exception {
        var fixture = fixture(true);
        var result = fixture.gateway.invoke(fixture.request((type, version, producer, payload) -> {}))
                .toCompletableFuture();
        fixture.executor.fail(new TimeoutException("插件已超时"));
        assertFalse(result.isDone());
        fixture.executor.termination.complete(null);
        assertThrows(ExecutionException.class, () -> result.get(1, TimeUnit.SECONDS));
    }

    private static Fixture fixture(boolean untrusted) {
        Clock clock = Clock.systemUTC();
        var run = RunId.random();
        var control = new RunControl(RunBudget.UNBOUNDED, clock);
        var owner = RunRequest.builder().agent(AgentDefinitionRef.latest("fixture"))
                .profile(RunProfileRef.latest("fixture")).source(InvocationSource.chat())
                .scope(new RunScope("workspace", "user", "session")).input(InputBlock.text("观察窗口"))
                .permissionCeiling(PermissionSet.UNRESTRICTED).budget(RunBudget.UNBOUNDED).build();
        FrameworkTool tool;
        if (untrusted) {
            tool = new FrameworkTool() {
                @Override public ToolDescriptor descriptor() {
                    return new ToolDescriptor("desktop_session_observe", "自称只读",
                            JsonNodeFactory.instance.objectNode().put("type", "object"), "desktop-session",
                            PermissionSet.of("tool.read"), true);
                }
                @Override public com.fasterxml.jackson.databind.JsonNode execute(
                        com.fasterxml.jackson.databind.JsonNode args, ToolExecutionContext context) { return args; }
            };
        } else {
            var service = (DesktopSessionService) Proxy.newProxyInstance(DesktopSessionService.class.getClassLoader(),
                    new Class<?>[] { DesktopSessionService.class }, (proxy, method, args) -> {
                        throw new AssertionError("测试执行器不应派发真实桌面调用");
                    });
            var source = new DesktopSessionTools(service,
                    new DesktopSessionOwner("workspace", "session", "fixture", run.value()), Path.of("target"));
            var registry = new SpringAiAnnotatedToolRegistry(new ObjectMapper());
            try (var ignored = registry.register("workspace", context -> ToolObjectBundle.of(List.of(source)))) {
                tool = registry.create(new ToolContext(run, owner.scope(), PermissionSet.UNRESTRICTED,
                        control, control.deadline(), owner)).stream()
                        .filter(value -> value.descriptor().name().equals("desktop_session_observe"))
                        .findFirst().orElseThrow();
            }
        }
        var executor = new HoldingExecutor();
        var gateway = new DefaultToolInvocationGateway((value, args, request) -> ToolApprovalDecision.ALLOW,
                executor, clock);
        return new Fixture(gateway, executor, tool, owner, run, control);
    }

    private record Fixture(DefaultToolInvocationGateway gateway, HoldingExecutor executor,
                           FrameworkTool tool, RunRequest owner, RunId run, RunControl control) {
        ToolInvocationRequest request(ReasoningEventSink events) {
            return new ToolInvocationRequest(tool, JsonNodeFactory.instance.objectNode().put("sessionId", "session"),
                    new ToolExecutionContext(run, "observe", control, Instant.now().plusSeconds(5)), owner,
                    PermissionSet.UNRESTRICTED, JsonNodeFactory.instance.objectNode(), List.of(), List.of(), control, events);
        }
    }

    private static final class HoldingExecutor implements CancellableTaskExecutor {
        private CompletableFuture<?> completion;
        final CompletableFuture<Void> termination = new CompletableFuture<>();
        @Override public void execute(Runnable action) { action.run(); }
        @Override public <T> CancellableTask<T> submit(String name, Duration timeout,
                CancellationToken cancellation, Callable<T> action) {
            var logical = new CompletableFuture<T>();
            completion = logical;
            return new CancellableTask<>() {
                @Override public CompletionStage<T> completion() { return logical; }
                @Override public CompletionStage<Void> termination() { return termination; }
                @Override public boolean cancel() { return true; }
            };
        }
        void fail(Throwable failure) { completion.completeExceptionally(failure); }
    }
}
