package com.javaclaw.desktop.settings;

import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ToolCatalogQueryResult;
import com.javaclaw.api.ToolDescriptor;
import com.javaclaw.api.ToolIdentity;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.api.WorkspaceId;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolCatalogSelectionPresenterTest {
    private static final WorkspaceId WORKSPACE = WorkspaceId.parse("9f4e0135-0bfb-46ce-a84f-854e602ce275");
    private static final PermissionProfileRef PERMISSION = new PermissionProfileRef("workspace-review", 3);

    @Test
    void 后发查询完成后丢弃先前作用域的旧响应() {
        Queue<CompletableFuture<ToolCatalogQueryResult>> responses = new ArrayDeque<>();
        CompletableFuture<ToolCatalogQueryResult> first = new CompletableFuture<>();
        CompletableFuture<ToolCatalogQueryResult> second = new CompletableFuture<>();
        responses.add(first);
        responses.add(second);
        CoreSettingsGateway gateway = proxy(responses);
        ToolCatalogSelectionPresenter presenter = new ToolCatalogSelectionPresenter(gateway);
        AtomicReference<ToolCatalogSelectionState> latest = new AtomicReference<>();
        presenter.subscribe(latest::set);

        presenter.bind(Optional.of(WORKSPACE), Optional.of(PERMISSION), Optional.empty());
        presenter.search("current");
        second.complete(new ToolCatalogQueryResult(8, List.of(descriptor("current_tool"))));
        first.complete(new ToolCatalogQueryResult(7, List.of(descriptor("stale_tool"))));

        assertEquals(8, latest.get().result().orElseThrow().catalogRevision());
        assertEquals(
                List.of("current_tool"),
                latest.get().result().orElseThrow().tools().stream()
                        .map(value -> value.identity().name())
                        .toList());
        assertEquals("current", latest.get().query());
    }

    private static CoreSettingsGateway proxy(Queue<CompletableFuture<ToolCatalogQueryResult>> responses) {
        return (CoreSettingsGateway) Proxy.newProxyInstance(
                CoreSettingsGateway.class.getClassLoader(),
                new Class<?>[] {CoreSettingsGateway.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "toolCatalog" -> {
                        assertEquals(WORKSPACE, arguments[0]);
                        assertEquals(PERMISSION, arguments[1]);
                        yield responses.remove();
                    }
                    case "toString" -> "DeferredToolCatalogGateway";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static ToolDescriptor descriptor(String name) {
        return new ToolDescriptor(
                new ToolIdentity("test", name, 1),
                "测试工具",
                new CanonicalPayload("{}"),
                new CanonicalPayload("{}"),
                ToolRisk.READ_ONLY,
                Set.of("test"));
    }
}
