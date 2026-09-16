package com.javaclaw.model;

import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import com.openai.models.responses.ResponseInputItem;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;

import com.javaclaw.api.AttachmentRef;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.runtime.ModelImage;
import com.javaclaw.runtime.ModelMessage;
import com.javaclaw.runtime.ProviderState;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelImagesMappingTest {
    private static final byte[] IMAGE = java.util.Base64.getDecoder()
            .decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jGXYAAAAASUVORK5CYII=");

    @Test
    void responsesPersistsOnlyScopedReferencesAndHydratesOnEveryReplay() throws Exception {
        ModelImage image = image();
        var mapping = new ResponsesImageMapping(ignored -> IMAGE.clone());
        ResponseInputItem reference = mapping.message(tool("call-1", image));
        String persisted = ModelJsonMapper.create().writeValueAsString(reference);
        assertTrue(persisted.contains("javaclaw-image:v1:"));
        assertFalse(persisted.contains("data:image"));
        ResponseInputItem sent = mapping.hydrate(reference, ResponseInputItem.class);
        assertTrue(ModelJsonMapper.create().writeValueAsString(sent).contains("data:image/png;base64,"));
        assertFalse(ModelJsonMapper.create().writeValueAsString(reference).contains("data:image"));
        assertThrows(
                IllegalStateException.class,
                () -> new ResponsesImageMapping(ignored -> new byte[] {1}).hydrate(reference, ResponseInputItem.class));
    }

    @Test
    void springAppendsObservationAfterAllPendingToolResponses() throws Exception {
        var mapper = new SpringAiPromptMapper(ignored -> IMAGE.clone());
        var invocation = ModelAdapterTestFixtures.invocation(
                List.of(
                        ModelMessage.assistant(
                                "",
                                List.of(
                                        ModelAdapterTestFixtures.toolCall("call-1"),
                                        ModelAdapterTestFixtures.toolCall("call-2"))),
                        tool("call-1", image()),
                        ModelMessage.tool("call-2", "browser_act", "完成")),
                List.of());
        var messages = mapper.map(invocation, ChatOptions.builder().build()).getInstructions();
        assertTrue(messages.get(2) instanceof ToolResponseMessage);
        assertTrue(messages.get(3) instanceof ToolResponseMessage);
        UserMessage observation = (UserMessage) messages.get(4);
        assertEquals(1, observation.getMedia().size());
        assertTrue(observation.getText().contains("call-1"));
        assertTrue(observation.getText().contains("frame-1"));
    }

    @Test
    void userImageUsesUserRoleAndCannotBecomeSystemInstructions() throws Exception {
        var user = new ModelMessage(
                MessageRole.USER, "解释图片", List.of(), Optional.empty(), Optional.empty(), List.of(image()));
        var mapper = new SpringAiPromptMapper(ignored -> IMAGE.clone());
        var prompt = mapper.map(
                ModelAdapterTestFixtures.invocation(List.of(user), List.of()),
                ChatOptions.builder().build());
        assertEquals(2, prompt.getInstructions().size());
        assertEquals(
                1, ((UserMessage) prompt.getInstructions().get(1)).getMedia().size());
        assertThrows(
                IllegalArgumentException.class,
                () -> new ModelMessage(
                        MessageRole.SYSTEM, "外部数据", List.of(), Optional.empty(), Optional.empty(), user.images()));
    }

    @Test
    void responsesContinuingHydratesStoredImageWithoutEmbeddingBytesInState() throws Exception {
        ProviderStateCodec states = new ProviderStateCodec();
        var mapper = new OpenAiResponsesRequestMapper(states, ignored -> IMAGE.clone());
        var invocation = ModelAdapterTestFixtures.invocation(List.of(tool("call-1", image())), List.of());
        var config = new OpenAiResponsesEndpointConfig(
                ModelAdapterTestFixtures.ENDPOINT_ID,
                "test",
                Optional.empty(),
                Duration.ofSeconds(10),
                0,
                ReasoningSummaryStyle.AUTO);
        var request = mapper.initial(config, invocation);
        ProviderState empty = new ProviderState(
                ProviderStateCodec.PROVIDER_ID,
                ProviderStateCodec.FORMAT,
                new CanonicalPayload("{\"formatVersion\":4,\"kind\":\"response\",\"responseId\":\"r1\","
                        + "\"instructions\":{\"systemInstruction\":\"系统说明\",\"developerInstructions\":\"\","
                        + "\"responseContract\":\"\"},\"inputTokens\":1,\"outputItems\":[],\"inputItems\":[]}"));
        ProviderState saved =
                states.withInputs(empty, request.input().orElseThrow().asResponse());
        assertFalse(saved.payload().json().contains("data:image"));
        var next = mapper.continuing(
                config, ModelAdapterTestFixtures.invocation(List.of(), List.of()), states.decode(saved));
        var hydrated = mapper.hydrate(next);
        assertTrue(ModelJsonMapper.create()
                .writeValueAsString(hydrated.input().orElseThrow())
                .contains("data:image/png"));
    }

    private static ModelMessage tool(String callId, ModelImage image) {
        return new ModelMessage(
                MessageRole.TOOL,
                "页面观察",
                List.of(),
                Optional.of(callId),
                Optional.of("browser_screenshot"),
                List.of(image));
    }

    private static ModelImage image() throws Exception {
        String digest =
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(IMAGE));
        return new ModelImage(
                new AttachmentRef(digest, "image/png", "observation.png", IMAGE.length),
                WorkspaceId.random(),
                ThreadId.random(),
                "frame-1",
                1,
                1);
    }
}
