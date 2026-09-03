package com.javaclaw.desktop.settings;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.McpAuthType;
import com.javaclaw.api.McpCacheScope;
import com.javaclaw.api.McpEndpoint;
import com.javaclaw.api.McpEndpointSpec;
import com.javaclaw.api.McpPromptDescriptor;
import com.javaclaw.api.McpPromptPage;
import com.javaclaw.api.McpResourceDescriptor;
import com.javaclaw.api.McpResourcePage;
import com.javaclaw.api.McpTransport;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.client.CommandOptions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpExternalDataPresenterTest {
    @Test
    void 显式读取Resource与Prompt只更新外部数据状态() {
        TestMcpSettingsGateway gateway = new TestMcpSettingsGateway();
        McpEndpoint enabled = enabled(gateway);
        McpExternalDataPresenter presenter = new McpExternalDataPresenter(gateway);
        AtomicReference<McpExternalDataState> snapshot = new AtomicReference<>();
        presenter.subscribe(snapshot::set);
        presenter.select(Optional.of(enabled));

        presenter.loadResources(false);
        presenter.readResource(snapshot.get().resources().getFirst());
        assertEquals(
                "hello",
                snapshot.get()
                        .resource()
                        .orElseThrow()
                        .contents()
                        .getFirst()
                        .text()
                        .orElseThrow());
        assertTrue(snapshot.get().prompt().isEmpty());

        presenter.loadPrompts(false);
        presenter.getPrompt(snapshot.get().prompts().getFirst(), Map.of("topic", "v5"));
        assertEquals(1, snapshot.get().prompt().orElseThrow().messages().size());
        assertTrue(snapshot.get().resource().isEmpty());
        assertTrue(snapshot.get().message().contains("系统上下文"));
    }

    @Test
    void 分页追加旧响应丢弃和失败状态都不把外部内容注入上下文() {
        TestMcpSettingsGateway gateway = new TestMcpSettingsGateway();
        McpExternalDataPresenter presenter = new McpExternalDataPresenter(gateway);
        AtomicReference<McpExternalDataState> snapshot = new AtomicReference<>();
        presenter.subscribe(snapshot::set);
        assertThrows(IllegalStateException.class, () -> presenter.loadResources(false));
        assertThrows(IllegalStateException.class, () -> presenter.loadPrompts(false));

        McpEndpoint enabled = enabled(gateway);
        presenter.select(Optional.of(enabled));
        long selectedEpoch = snapshot.get().epoch();
        presenter.select(Optional.of(enabled));
        assertEquals(selectedEpoch, snapshot.get().epoch());

        gateway.enqueueResources(CompletableFuture.completedFuture(resourcePage("one", Optional.of("next"))));
        gateway.enqueueResources(CompletableFuture.completedFuture(resourcePage("two", Optional.empty())));
        presenter.loadResources(false);
        presenter.loadResources(true);
        assertEquals(
                List.of("one", "two"),
                snapshot.get().resources().stream()
                        .map(McpResourceDescriptor::name)
                        .toList());

        gateway.enqueuePrompts(CompletableFuture.completedFuture(promptPage("first", Optional.of("next"))));
        gateway.enqueuePrompts(CompletableFuture.completedFuture(promptPage("second", Optional.empty())));
        presenter.loadPrompts(false);
        presenter.loadPrompts(true);
        assertEquals(
                List.of("first", "second"),
                snapshot.get().prompts().stream().map(McpPromptDescriptor::name).toList());

        CompletableFuture<McpResourcePage> delayed = new CompletableFuture<>();
        gateway.enqueueResources(delayed);
        presenter.loadResources(false);
        gateway.enqueuePrompts(CompletableFuture.completedFuture(promptPage("newer", Optional.empty())));
        presenter.loadPrompts(false);
        delayed.complete(resourcePage("stale", Optional.empty()));
        assertEquals(
                List.of("one", "two"),
                snapshot.get().resources().stream()
                        .map(McpResourceDescriptor::name)
                        .toList());

        gateway.enqueueResources(
                CompletableFuture.failedFuture(new CompletionException(new IllegalStateException("外部目录失败"))));
        presenter.loadResources(false);
        assertEquals(SettingsLoadState.ERROR, snapshot.get().phase());
        assertEquals("外部目录失败", snapshot.get().message());

        gateway.enqueueResourceRead(CompletableFuture.failedFuture(new IllegalStateException()));
        presenter.readResource(resource("one"));
        assertEquals("IllegalStateException", snapshot.get().message());
        gateway.enqueuePromptResult(CompletableFuture.failedFuture(new IllegalArgumentException("参数被拒绝")));
        presenter.getPrompt(prompt("first"), Map.of());
        assertEquals("参数被拒绝", snapshot.get().message());

        presenter.select(Optional.empty());
        assertTrue(snapshot.get().resources().isEmpty());
        assertTrue(snapshot.get().message().contains("请选择"));
    }

    private static McpEndpoint enabled(TestMcpSettingsGateway gateway) {
        McpEndpoint created = gateway.createMcpEndpoint("external", spec(), CommandOptions.create(0))
                .toCompletableFuture()
                .join();
        return gateway.setMcpEndpointEnabled(created, true, CommandOptions.create(created.revision()))
                .toCompletableFuture()
                .join();
    }

    private static McpResourcePage resourcePage(String name, Optional<String> nextCursor) {
        return new McpResourcePage(List.of(resource(name)), nextCursor, McpCacheScope.PRIVATE, 0, List.of());
    }

    private static McpResourceDescriptor resource(String name) {
        return new McpResourceDescriptor(
                name,
                "docs://" + name,
                Optional.of("External " + name),
                Optional.empty(),
                Optional.of("text/plain"),
                Optional.of(10L));
    }

    private static McpPromptPage promptPage(String name, Optional<String> nextCursor) {
        return new McpPromptPage(List.of(prompt(name)), nextCursor, McpCacheScope.PRIVATE, 0, List.of());
    }

    private static McpPromptDescriptor prompt(String name) {
        return new McpPromptDescriptor(name, Optional.of("External " + name), Optional.empty(), List.of());
    }

    private static McpEndpointSpec spec() {
        return new McpEndpointSpec(
                WorkspaceId.random(),
                "External",
                McpTransport.STREAMABLE_HTTPS,
                Optional.of(URI.create("https://mcp.example.test/rpc")),
                Optional.empty(),
                McpAuthType.NONE,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Duration.ofSeconds(10));
    }
}
