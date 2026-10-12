package com.javaclaw.application.agent;

import com.javaclaw.api.conversation.ConversationMessage;
import com.javaclaw.api.conversation.ConversationOptions;
import com.javaclaw.api.conversation.ConversationRequest;
import com.javaclaw.framework.api.InvocationSource;
import com.javaclaw.framework.api.PermissionSet;
import com.javaclaw.framework.api.RunRequest;
import com.javaclaw.runtime.WorkspaceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunRequestFactoryTest {

    private Path fixtures;
    private RunRequestFactory factory;

    @BeforeEach
    void setUp() throws Exception {
        fixtures = Path.of("target", "run-request-factory-test").toAbsolutePath();
        Files.createDirectories(fixtures);
        Path root = fixtures.resolve("workspace");
        factory = new RunRequestFactory(new WorkspaceContext(
                "workspace-a", "Workspace A", fixtures, root,
                root.resolve("browser"), root.resolve("screenshots"), root.resolve("logs")));
    }

    @AfterEach
    void cleanFixtureFiles() throws Exception {
        if (!Files.exists(fixtures)) return;
        try (var files = Files.walk(fixtures)) {
            for (Path path : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void conversationPreservesOrderedHistoryAttachmentsAndPreviousReply() throws Exception {
        Path image = fixtures.resolve("fixture.png");
        Path unknown = fixtures.resolve("fixture.unknown-extension");
        Files.write(image, new byte[]{1, 2, 3});
        Files.writeString(unknown, "payload");
        ConversationRequest request = new ConversationRequest(
                "current question", List.of(image.toFile(), unknown.toFile()), "session-7",
                ConversationOptions.DEFAULT,
                List.of(
                        new ConversationMessage(ConversationMessage.Role.USER, "first question"),
                        new ConversationMessage(ConversationMessage.Role.ASSISTANT, "first answer"),
                        new ConversationMessage(ConversationMessage.Role.USER, "follow-up"),
                        new ConversationMessage(ConversationMessage.Role.ASSISTANT, "latest answer")));

        RunRequest run = factory.conversation(
                request, "chat", InvocationSource.chat(), PermissionSet.of("tool.read"));

        assertEquals("system.default", run.agent().id());
        assertEquals("chat", run.profile().id());
        assertEquals("workspace-a", run.scope().workspaceId());
        assertEquals("local-user", run.scope().userId());
        assertEquals("session-7", run.scope().sessionId());
        assertEquals(List.of("core.message", "core.message", "core.message", "core.message",
                "core.text", "core.image", "core.file"),
                run.inputs().stream().map(input -> input.type()).toList());
        assertEquals("user", run.inputs().getFirst().data().path("role").asText());
        assertEquals("first question", run.inputs().getFirst().data().path("text").asText());
        assertEquals("latest answer",
                run.attributes().get("previousAssistantReply").asText());
        assertEquals("image/png", run.inputs().get(5).data().path("mediaType").asText());
        assertEquals("application/octet-stream",
                run.inputs().get(6).data().path("mediaType").asText());
        assertTrue(run.permissionCeiling().contains("tool.read"));
    }

    @Test
    void conversationDefaultsSessionAndOmitsPreviousReplyWhenHistoryHasOnlyUsers() {
        ConversationRequest request = new ConversationRequest(
                "question", List.of(), null, ConversationOptions.DEFAULT,
                List.of(new ConversationMessage(ConversationMessage.Role.USER, "earlier")));

        RunRequest run = factory.conversation(
                request, "plan", new InvocationSource("plan", "desktop"), PermissionSet.NONE);

        assertEquals("default", run.scope().sessionId());
        assertEquals(List.of("core.message", "core.text"),
                run.inputs().stream().map(input -> input.type()).toList());
        assertTrue(run.attributes().isEmpty());
    }

    @Test
    void textNormalizesNullAndBlankInputsWithoutChangingExplicitValues() {
        RunRequest nullValues = factory.text(
                null, null, "loop", InvocationSource.loop("loop-a"),
                PermissionSet.NONE, null);
        assertEquals("default", nullValues.scope().sessionId());
        assertEquals("", nullValues.inputs().getFirst().data().path("text").asText());
        assertNull(nullValues.idempotencyKey());

        RunRequest blankSession = factory.text(
                "prompt", "   ", "sdd", InvocationSource.sdd("task-a"),
                PermissionSet.of("tool.execute"), " command-1 ");
        assertEquals("default", blankSession.scope().sessionId());
        assertEquals("prompt", blankSession.inputs().getFirst().data().path("text").asText());
        assertEquals("command-1", blankSession.idempotencyKey());

        RunRequest explicit = factory.text(
                "prompt", "session-explicit", "workflow", InvocationSource.workflow("wf-a"),
                PermissionSet.NONE, "idempotent");
        assertEquals("session-explicit", explicit.scope().sessionId());
        assertEquals("idempotent", explicit.idempotencyKey());
        assertFalse(explicit.inputs().isEmpty());
    }
}
