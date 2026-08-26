package com.javaclaw.application.agent;

import com.javaclaw.api.conversation.ConversationMessage;
import com.javaclaw.api.conversation.ConversationOptions;
import com.javaclaw.api.conversation.ConversationRequest;
import com.javaclaw.framework.api.InvocationSource;
import com.javaclaw.framework.api.PermissionSet;
import com.javaclaw.framework.api.RunRequest;
import com.javaclaw.framework.api.ToolGroupAccess;
import com.javaclaw.framework.api.ToolNameAccess;
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
    private WorkspaceContext workspace;

    @BeforeEach
    void setUp() throws Exception {
        fixtures = Path.of("target", "run-request-factory-test").toAbsolutePath();
        Files.createDirectories(fixtures);
        Path root = fixtures.resolve("workspace");
        workspace = new WorkspaceContext(
                "workspace-a", "Workspace A", fixtures, root,
                root.resolve("browser"), root.resolve("screenshots"), root.resolve("logs"));
        factory = new RunRequestFactory(workspace);
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
        assertEquals(request.priorMessages().getFirst().messageId(),
                run.inputs().getFirst().data()
                        .path(com.javaclaw.framework.api.InputBlock.CONVERSATION_MESSAGE_ID_FIELD)
                        .asText());
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

    @Test
    void routedConversationPersistsExactAllowlistAndKnowledgeGate() {
        RunRequestFactory routed = new RunRequestFactory(workspace, new ToolIntentRouter(true));
        RunRequest ordinary = routed.conversation(
                ConversationRequest.ofText("解释一下 CAP"), "chat",
                InvocationSource.chat(), PermissionSet.UNRESTRICTED);
        assertEquals(List.of("ask_user_clarification", "skill_read"),
                java.util.stream.StreamSupport.stream(
                        ordinary.attributes().get(ToolNameAccess.ATTRIBUTE).spliterator(), false)
                        .map(com.fasterxml.jackson.databind.JsonNode::asText).sorted().toList());
        assertFalse(ordinary.attributes().get("framework.enableKnowledgeContext").asBoolean());

        RunRequest knowledge = routed.conversation(
                ConversationRequest.ofText("查询知识库中的项目文档"), "plan",
                new InvocationSource("plan", "desktop"), PermissionSet.UNRESTRICTED);
        assertTrue(knowledge.attributes().get("framework.enableKnowledgeContext").asBoolean());
        assertTrue(knowledge.attributes().get(ToolGroupAccess.ATTRIBUTE).toString()
                .contains("knowledge"));
        assertTrue(knowledge.attributes().get(ToolNameAccess.ATTRIBUTE).toString()
                .contains("knowledge_search"));

        RunRequest knowledgeWrite = routed.conversation(
                ConversationRequest.ofText("导入知识库"), "chat",
                InvocationSource.chat(), PermissionSet.UNRESTRICTED);
        assertFalse(knowledgeWrite.attributes().get("framework.enableKnowledgeContext").asBoolean());

        RunRequest legacy = new RunRequestFactory(workspace, new ToolIntentRouter(false))
                .conversation(ConversationRequest.ofText("解释一下 CAP"), "chat",
                        InvocationSource.chat(), PermissionSet.UNRESTRICTED);
        assertFalse(legacy.attributes().containsKey(ToolGroupAccess.ATTRIBUTE));
        assertFalse(legacy.attributes().containsKey(ToolNameAccess.ATTRIBUTE));
        assertFalse(legacy.attributes().containsKey("framework.enableKnowledgeContext"));
    }
}
