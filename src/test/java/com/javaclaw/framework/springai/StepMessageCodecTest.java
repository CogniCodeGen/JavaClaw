package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.framework.api.AgentDefinitionRef;
import com.javaclaw.framework.api.InputBlock;
import com.javaclaw.framework.api.InvocationSource;
import com.javaclaw.framework.api.ResumeCommand;
import com.javaclaw.framework.api.RunProfileRef;
import com.javaclaw.framework.api.RunRequest;
import com.javaclaw.framework.api.RunScope;
import com.javaclaw.framework.core.ReasoningRequest;
import com.javaclaw.framework.core.StepContextPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StepMessageCodecTest {
    @Test
    void fixedPersonaAndDeferredContentUseSurviveCheckpoint() {
        UserMessage persona = UserMessage.builder().text("persona snapshot")
                .metadata(Map.of(FixedContextSession.SOURCE_METADATA, "memory.persona",
                        FixedContextSession.VERSION_METADATA, "v1")).build();
        UserMessage workflow = UserMessage.builder().text("workspace workflow")
                .metadata(Map.of(OnDemandContextSession.CONTEXT_METADATA, true,
                        OnDemandContextSession.CONTEXT_USE_METADATA, "USER_WORKFLOW")).build();

        List<Message> recovered = StepMessageCodec.messages(
                StepMessageCodec.messages(List.of(persona, workflow)));

        assertEquals(StepMessageCodec.messages(List.of(persona, workflow)),
                StepMessageCodec.messages(recovered));
        assertTrue(FixedContextSession.isFixed((UserMessage) recovered.getFirst()));
        assertEquals("USER_WORKFLOW", ((UserMessage) recovered.getLast()).getMetadata()
                .get(OnDemandContextSession.CONTEXT_USE_METADATA));
    }

    @Test
    void taskAndResumeMarkersSurviveAProviderStepCheckpoint() {
        ReasoningRequest request = request(new ResumeCommand("human.continue",
                JsonNodeFactory.instance.objectNode().put("answer", "yes")));
        List<Message> messages = List.of(
                SpringAiPromptFactory.originalTaskMessage(request),
                SpringAiPromptFactory.resumeCommandMessage(request));

        List<Message> recovered = StepMessageCodec.messages(StepMessageCodec.messages(messages));

        assertEquals(2, recovered.size());
        assertTrue(SpringAiPromptFactory.isOriginalTask((UserMessage) recovered.getFirst()));
        assertTrue(SpringAiPromptFactory.isResumeCommand((UserMessage) recovered.getLast()));
        assertEquals("original task", recovered.getFirst().getText());
        assertTrue(recovered.getLast().getText().contains("human.continue"));
        new StepContextProjector(StepContextPolicy.DEFAULT,
                new com.fasterxml.jackson.databind.ObjectMapper()).validate(recovered, request);
    }

    @Test
    void originalTaskValidationAcceptsRebuiltMediaAfterCheckpoint() {
        RunRequest run = runRequest(List.of(
                InputBlock.text("describe image"),
                InputBlock.file("photo", "https://example.test/photo.png", "image/png")));
        ReasoningRequest request = new ReasoningRequest(null, null, run, null, null, null);
        UserMessage original = SpringAiPromptFactory.originalTaskMessage(request);
        UserMessage restored = (UserMessage) StepMessageCodec.message(StepMessageCodec.message(original));

        assertTrue(SpringAiPromptFactory.sameUserContent(original, restored));
        new StepContextProjector(StepContextPolicy.DEFAULT,
                new com.fasterxml.jackson.databind.ObjectMapper()).validate(List.of(restored), request);
    }

    private static ReasoningRequest request(ResumeCommand resume) {
        return new ReasoningRequest(null, null,
                runRequest(List.of(InputBlock.text("original task"))), resume, null, null);
    }

    private static RunRequest runRequest(List<InputBlock> inputs) {
        return RunRequest.builder()
                .agent(AgentDefinitionRef.latest("test.agent"))
                .profile(RunProfileRef.latest("test.profile"))
                .source(new InvocationSource("test", "source"))
                .scope(new RunScope("workspace", "user", "session"))
                .inputs(inputs)
                .build();
    }
}
