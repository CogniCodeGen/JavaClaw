package com.javaclaw.infrastructure.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.framework.api.AgentDefinitionRef;
import com.javaclaw.framework.api.InputBlock;
import com.javaclaw.framework.api.InvocationSource;
import com.javaclaw.framework.api.RunProfileRef;
import com.javaclaw.framework.api.RunRequest;
import com.javaclaw.framework.api.RunScope;
import com.javaclaw.framework.spi.ModelTaskResult;
import com.javaclaw.memory.MemoryGraphScope;
import com.javaclaw.memory.MemoryService;
import com.javaclaw.memory.embed.TestEmbeddingGatewayFactory;
import com.javaclaw.platform.data.DataRoot;
import com.javaclaw.platform.spring.ApplicationContexts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryPersonaFixedContextSourceTest {
    @Test
    void 版本快照按会话和习惯顺序读取且保持隔离(@TempDir Path temporary) {
        try (var context = ApplicationContexts.createRoot(new DataRoot(temporary.resolve("config")));
             var embedding = TestEmbeddingGatewayFactory.create(4,
                     (text, timeout) -> new double[] {1, 0, 0, 0});
             var memory = new MemoryService(request -> CompletableFuture.completedFuture(
                     new ModelTaskResult(JsonNodeFactory.instance.objectNode(), "test",
                             0, 0, false, Map.of())),
                     embedding.gateway(), embedding.tasks(), context.getBean(AgentConfig.class),
                     new ObjectMapper())) {
            memory.open(temporary.resolve("memory"), "workspace", "user");
            MemoryGraphScope scope = new MemoryGraphScope("workspace", "user", "thread",
                    MemoryGraphScope.Kind.THREAD);
            memory.inScope(scope).setPersona("会话人格", "test");
            memory.inScope(scope.habits()).setPersona("习惯人格", "test");
            var source = new MemoryPersonaFixedContextSource(memory);

            var first = source.read(request("workspace", "user", "thread"));
            assertTrue(first.body().indexOf("会话人格") < first.body().indexOf("习惯人格"));
            assertEquals(first, source.read(request("workspace", "user", "thread")));
            assertFalse(source.read(request("workspace", "user", "other")).body()
                    .contains("会话人格"));
            memory.inScope(scope.habits()).setPersona("更新后习惯人格", "test");
            var updated = source.read(request("workspace", "user", "thread"));
            assertFalse(first.version().equals(updated.version()));
            assertTrue(updated.body().contains("更新后习惯人格"));
            memory.inScope(scope).setPersona("相同人格", "test");
            memory.inScope(scope.habits()).setPersona("相同人格", "test");
            String deduplicated = source.read(request("workspace", "user", "thread")).body();
            assertEquals(deduplicated.indexOf("相同人格"), deduplicated.lastIndexOf("相同人格"));
            assertThrows(IllegalArgumentException.class,
                    () -> source.read(request("workspace", "other-user", "thread")));
            assertThrows(IllegalArgumentException.class,
                    () -> source.read(request("other-workspace", "user", "thread")));

            memory.inScope(scope).setPersona(" ", "test");
            memory.inScope(scope.habits()).setPersona(" ", "test");
            assertEquals("", source.read(request("workspace", "user", "thread")).body());
        }
    }

    private static RunRequest request(String workspaceId, String userId, String threadId) {
        return RunRequest.builder().agent(AgentDefinitionRef.latest("test"))
                .profile(RunProfileRef.latest("chat")).source(InvocationSource.chat())
                .scope(new RunScope(workspaceId, userId, threadId))
                .input(InputBlock.text("继续")).build();
    }
}
