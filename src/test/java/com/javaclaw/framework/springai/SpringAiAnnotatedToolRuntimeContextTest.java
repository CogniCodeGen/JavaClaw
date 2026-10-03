package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.desktop.agent.DesktopSessionTools;
import com.javaclaw.desktop.api.DesktopSessionOwner;
import com.javaclaw.desktop.api.DesktopSessionService;
import com.javaclaw.framework.api.PermissionSet;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.RunScope;
import com.javaclaw.framework.spi.ToolContext;
import com.javaclaw.framework.spi.ToolContract;
import com.javaclaw.framework.spi.ToolObjectBundle;
import com.javaclaw.framework.spi.ToolRuntimeContextProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.annotation.Tool;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class SpringAiAnnotatedToolRuntimeContextTest {
    @TempDir Path temporary;

    @Test
    void exactHostToolsShareTheSourceProviderAndReadItOnlyWhenRequested() {
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger inventoryReads = new AtomicInteger();
        DesktopSessionService sessions = (DesktopSessionService) Proxy.newProxyInstance(
                DesktopSessionService.class.getClassLoader(),
                new Class<?>[] { DesktopSessionService.class },
                (proxy, method, args) -> {
                    if (method.getName().equals("liveSessionIds")) {
                        inventoryReads.incrementAndGet();
                        assertEquals("workspace", ((DesktopSessionOwner) args[0]).workspaceId());
                        return Optional.empty();
                    }
                    throw new AssertionError("runtime context must not execute tools: " + method.getName());
                });
        DesktopSessionTools source = new DesktopSessionTools(sessions,
                new DesktopSessionOwner("workspace", "thread", "chat", "source"),
                temporary, null, null, () -> List.of(JsonNodeFactory.instance.objectNode()
                        .put("read", reads.incrementAndGet())));
        SpringAiAnnotatedToolRegistry registry = new SpringAiAnnotatedToolRegistry(new ObjectMapper());
        registry.register("workspace", ignored -> ToolObjectBundle.of(List.of(source)));
        var tools = registry.create(context());

        assertEquals(0, reads.get());
        assertEquals(0, inventoryReads.get());
        tools.forEach(tool -> assertSame(source, tool.runtimeContextProvider()));
        assertEquals(1, tools.getFirst().runtimeContextProvider().currentContext()
                .getFirst().path("read").asInt());
        assertEquals(2, tools.getLast().runtimeContextProvider().currentContext()
                .getFirst().path("read").asInt());
        assertEquals(2, inventoryReads.get(), "liveness reads only the service's owner-scoped inventory");
    }

    @Test
    void extensionToolCannotExposeHostRuntimeCapabilityFacts() {
        SpringAiAnnotatedToolRegistry registry = new SpringAiAnnotatedToolRegistry(new ObjectMapper());
        registry.register("workspace", ignored -> ToolObjectBundle.of(List.of(new ExtensionTool())));
        var tools = registry.create(context());
        assertEquals(1, tools.size());
        assertNull(tools.getFirst().runtimeContextProvider());
    }

    private static ToolContext context() {
        return new ToolContext(RunId.random(), new RunScope("workspace", "user", "thread"),
                PermissionSet.NONE, null, Instant.EPOCH, null);
    }

    @ToolContract(group = "extension", permissions = {"tool.read"}, idempotent = true)
    public static final class ExtensionTool implements ToolRuntimeContextProvider {
        @Tool(name = "extension_status")
        public String status() { return "ready"; }

        @Override
        public List<JsonNode> currentContext() {
            throw new AssertionError("extension provider must not be trusted as host state");
        }
    }
}
