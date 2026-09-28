package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.api.InputBlock;
import com.javaclaw.framework.api.ResumeCommand;
import com.javaclaw.framework.core.ReasoningRequest;
import com.javaclaw.framework.spi.ExtensionStateStore;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeTypeUtils;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 从冻结的执行计划和本轮输入构造 Spring AI Prompt。 */
final class SpringAiPromptFactory {
    static final String ORIGINAL_TASK_METADATA = "javaclaw.originalTask";
    static final String RESUME_COMMAND_METADATA = "javaclaw.resumeCommand";

    private final ExtensionStateStore extensionState;

    SpringAiPromptFactory(ExtensionStateStore extensionState) {
        this.extensionState = extensionState;
    }

    String systemPrompt(ReasoningRequest request) {
        StringBuilder prompt = new StringBuilder();
        appendAttribute(prompt, request, "framework.projectInstructions", "Project instructions");
        request.plan().descriptor().promptSections().entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .forEach(entry -> prompt.append("## ").append(entry.getKey()).append('\n')
                        .append(entry.getValue()).append("\n\n"));
        appendAttribute(prompt, request, "framework.systemPrompt", "Invocation profile");
        if (request.plan().descriptor().onDemandContextPolicy() == null) {
            appendContributedContext(prompt, request);
        } else {
            var state = extensionState.view(request.runId());
            for (var contributor : request.plan().fixedPromptContributors()) {
                String contribution = contributor.contribute(request.runRequest(), state);
                if (contribution != null && !contribution.isBlank()) {
                    prompt.append(contribution).append("\n\n");
                }
            }
        }
        return prompt.toString();
    }

    List<Message> messages(ReasoningRequest request) {
        List<Message> messages = new ArrayList<>();
        for (InputBlock block : request.runRequest().inputs()) {
            if (!block.type().equals("core.message")) continue;
            String text = block.data().path("text").asText("");
            if (text.isBlank()) continue;
            if (block.data().path("role").asText("").equals("assistant")) {
                messages.add(new AssistantMessage(text));
            } else {
                messages.add(new UserMessage(text));
            }
        }
        if (request.plan().descriptor().stepContextPolicy() == null) {
            messages.add(unprojectedUserMessage(request));
        } else {
            messages.add(originalTaskMessage(request));
            UserMessage resume = resumeCommandMessage(request);
            if (resume != null) messages.add(resume);
        }
        return List.copyOf(messages);
    }

    /** Keep the current request in one user message when projection is disabled. */
    private static UserMessage unprojectedUserMessage(ReasoningRequest request) {
        UserMessage original = originalTaskMessage(request);
        StringBuilder text = new StringBuilder(original.getText());
        if (request.resumeCommand() != null
                && !request.resumeCommand().type().equals("tool.approval")) {
            text.append("\n\nResume command (").append(request.resumeCommand().type())
                    .append("): ").append(request.resumeCommand().payload());
        }
        UserMessage.Builder builder = UserMessage.builder().text(text.toString());
        if (!original.getMedia().isEmpty()) builder.media(original.getMedia());
        return builder.build();
    }

    static boolean isOriginalTask(UserMessage message) {
        return Boolean.TRUE.equals(message.getMetadata().get(ORIGINAL_TASK_METADATA));
    }

    static boolean isResumeCommand(UserMessage message) {
        return Boolean.TRUE.equals(message.getMetadata().get(RESUME_COMMAND_METADATA));
    }

    static boolean sameUserContent(UserMessage left, UserMessage right) {
        return java.util.Objects.equals(left.getText(), right.getText())
                && sameUserMedia(left, right);
    }

    private static boolean sameUserMedia(UserMessage left, UserMessage right) {
        if (left.getMedia().size() != right.getMedia().size()) return false;
        for (int index = 0; index < left.getMedia().size(); index++) {
            Media a = left.getMedia().get(index);
            Media b = right.getMedia().get(index);
            if (!java.util.Objects.equals(a.getName(), b.getName())
                    || !java.util.Objects.equals(a.getMimeType(), b.getMimeType())
                    || !java.util.Objects.equals(a.getId(), b.getId())) return false;
            if (a.getData() instanceof byte[] aBytes && b.getData() instanceof byte[] bBytes) {
                if (!java.util.Arrays.equals(aBytes, bBytes)) return false;
            } else if (!java.util.Objects.equals(a.getData(), b.getData())) return false;
        }
        return true;
    }

    private static void appendAttribute(
            StringBuilder prompt, ReasoningRequest request, String name, String heading) {
        JsonNode value = request.runRequest().attributes().get(name);
        if (value != null && value.isTextual() && !value.asText().isBlank()) {
            prompt.append("## ").append(heading).append('\n')
                    .append(value.asText()).append("\n\n");
        }
    }

    private void appendContributedContext(StringBuilder prompt, ReasoningRequest request) {
        var state = extensionState.view(request.runId());
        for (var contributor : request.plan().promptContributors()) {
            String contribution = contributor.contribute(request.runRequest(), state);
            if (contribution != null && !contribution.isBlank()) {
                prompt.append(contribution).append("\n\n");
            }
        }
        String query = inputText(request);
        for (var retriever : request.plan().retrievers()) {
            List<JsonNode> documents = retriever.retrieve(query, request.runRequest());
            if (!documents.isEmpty()) {
                prompt.append("## Retrieved context\n").append(documents).append("\n\n");
            }
        }
        for (var provider : request.plan().contextProviders()) {
            List<JsonNode> values = provider.provide(request.runRequest(), state);
            if (!values.isEmpty()) {
                prompt.append("## Runtime context\n").append(values).append("\n\n");
            }
        }
    }

    static UserMessage originalTaskMessage(ReasoningRequest request) {
        StringBuilder text = new StringBuilder(inputText(request));
        List<Media> media = new ArrayList<>();
        for (InputBlock block : request.runRequest().inputs()) {
            if (!block.type().equals("core.file") && !block.type().equals("core.image")) continue;
            appendAttachment(text, media, block.data());
        }
        UserMessage.Builder builder = UserMessage.builder().text(text.toString())
                .metadata(Map.of(ORIGINAL_TASK_METADATA, true));
        if (!media.isEmpty()) builder.media(media);
        return builder.build();
    }

    static UserMessage resumeCommandMessage(ReasoningRequest request) {
        return resumeCommandMessage(request.resumeCommand());
    }

    static UserMessage resumeCommandMessage(ResumeCommand command) {
        if (command == null || command.type().equals("tool.approval")) {
            return null;
        }
        if (java.util.Set.of("delegation.continue", "schedule.continue", "managed.continue")
                .contains(command.type()) && command.payload().isEmpty()) {
            return null;
        }
        return UserMessage.builder()
                .text("Resume command (" + command.type() + "): "
                        + command.payload())
                .metadata(Map.of(RESUME_COMMAND_METADATA, true))
                .build();
    }

    private static void appendAttachment(
            StringBuilder text, List<Media> media, JsonNode data) {
        String name = data.path("name").asText("attachment");
        String uri = data.path("uri").asText("");
        String mediaType = data.path("mediaType").asText("application/octet-stream");
        text.append("\n[Attachment: ").append(name).append("; ").append(mediaType).append(']');
        if (uri.isBlank() || !(mediaType.startsWith("image/") || mediaType.startsWith("audio/"))) {
            return;
        }
        try {
            media.add(Media.builder().name(name)
                    .mimeType(MimeTypeUtils.parseMimeType(mediaType))
                    .data(URI.create(uri)).build());
        } catch (RuntimeException ignored) {
            // 文本附件描述仍会提供给模型。
        }
    }

    private static String inputText(ReasoningRequest request) {
        return request.runRequest().inputs().stream()
                .filter(block -> block.type().equals("core.text"))
                .map(block -> block.data().path("text").asText())
                .collect(java.util.stream.Collectors.joining("\n"));
    }
}
