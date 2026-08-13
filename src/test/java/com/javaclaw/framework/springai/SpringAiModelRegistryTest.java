package com.javaclaw.framework.springai;

import com.javaclaw.framework.spi.ModelTier;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SpringAiModelRegistryTest {

    @Test
    void workspaceRoutesStayIsolatedAndClosingNewGenerationRestoresOldOne() {
        SpringAiModelRegistry registry = new SpringAiModelRegistry();
        ChatModel a1 = model();
        ChatModel a2 = model();
        ChatModel b1 = model();
        var oldA = registry.installWorkspace("a", Map.of("a:old", a1),
                Map.of(ModelTier.HIGH, "a:old"));
        var workspaceB = registry.installWorkspace("b", Map.of("b:current", b1),
                Map.of(ModelTier.HIGH, "b:current"));

        var newA = registry.installWorkspace("a", Map.of("a:new", a2),
                Map.of(ModelTier.HIGH, "a:new"));
        assertSame(a2, registry.require("a", ModelTier.HIGH));
        assertSame(b1, registry.require("b", ModelTier.HIGH));

        newA.close();
        assertSame(a1, registry.require("a", ModelTier.HIGH));
        assertSame(b1, registry.require("b", ModelTier.HIGH));
        oldA.close();
        workspaceB.close();
    }

    @Test
    void failedWorkspacePublicationDoesNotReplaceExistingRoute() {
        SpringAiModelRegistry registry = new SpringAiModelRegistry();
        ChatModel existing = model();
        var installed = registry.installWorkspace("workspace", Map.of("old", existing),
                Map.of(ModelTier.NORMAL, "old"));

        assertThrows(java.util.NoSuchElementException.class, () -> registry.installWorkspace(
                "workspace", Map.of(), Map.of(ModelTier.NORMAL, "missing")));
        assertSame(existing, registry.require("workspace", ModelTier.NORMAL));
        installed.close();
    }

    private static ChatModel model() {
        return new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) { return null; }
        };
    }
}
