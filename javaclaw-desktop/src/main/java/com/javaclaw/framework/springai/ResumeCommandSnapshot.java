package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.framework.api.ResumeCommand;
import com.javaclaw.framework.core.ReasoningRequest;
import com.javaclaw.framework.core.ToolInvocationFingerprint;
import com.javaclaw.framework.spi.RunStore;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

/** 恢复输入按持久化命令序号冻结附件，同一命令跨模型步复用首次正文与媒体。 */
final class ResumeCommandSnapshot {
    static final String IDENTITY_METADATA = "javaclaw.resumeInputIdentity";
    static final String CONTENT_METADATA = "javaclaw.resumeInputContent";
    static final String SEQUENCE_METADATA = "javaclaw.resumeInputSequence";

    private ResumeCommandSnapshot() { }

    static UserMessage resolve(ReasoningRequest request, List<Message> incoming, RunStore runs) {
        ResumeCommand command = request.resumeCommand();
        if (!visible(command)) return null;
        long sequence = runs == null ? 0 : runs.eventsAfter(request.runId(), 0).stream()
                .filter(event -> event.type().equals("core.run.resumed") && event.schemaVersion() == 1
                        && event.producer().equals("framework.core")
                        && event.payload().path("commandType").asText().equals(command.type())
                        && event.payload().path("command").equals(command.payload()))
                .mapToLong(event -> event.sequence()).max().orElse(0);
        String identity = identity(command, sequence);
        for (Message value : incoming) {
            if (value instanceof UserMessage message && SpringAiPromptFactory.isResumeCommand(message)
                    && identity.equals(message.getMetadata().get(IDENTITY_METADATA))) {
                validateContent(message);
                return message;
            }
        }
        String text = commandText(command);
        int used = incoming.stream().filter(UserMessage.class::isInstance).map(UserMessage.class::cast)
                .filter(SpringAiPromptFactory::isOriginalTask).mapToInt(message -> message.getText().length()).sum();
        int budget = request.plan() == null || request.plan().descriptor().stepContextPolicy() == null
                ? SpringAiAttachmentReader.MAX_TOTAL_TEXT_CHARACTERS
                : Math.max(0, request.plan().descriptor().stepContextPolicy().maxMessageCharacters() / 2
                        - used - text.length());
        return create(command, sequence, text, budget);
    }

    static UserMessage create(ResumeCommand command) {
        return !visible(command) ? null : create(command, 0, commandText(command),
                SpringAiAttachmentReader.MAX_TOTAL_TEXT_CHARACTERS);
    }

    static UserMessage requireCurrent(ReasoningRequest request, List<Message> messages) {
        ResumeCommand command = request.resumeCommand();
        if (!visible(command)) return null;
        List<UserMessage> candidates = messages.stream().filter(UserMessage.class::isInstance)
                .map(UserMessage.class::cast).filter(SpringAiPromptFactory::isResumeCommand)
                .filter(message -> identity(command, sequence(message))
                        .equals(message.getMetadata().get(IDENTITY_METADATA)))
                .sorted(Comparator.comparingLong(ResumeCommandSnapshot::sequence).reversed()).toList();
        if (!candidates.isEmpty()) {
            UserMessage current = candidates.getFirst();
            long duplicates = candidates.stream().filter(message -> sequence(message) == sequence(current)).count();
            if (duplicates != 1) throw new IllegalStateException("resume input snapshot is duplicated");
            validateContent(current);
            return current;
        }
        if (!hasAttachments(command)) {
            UserMessage expected = create(command);
            for (Message value : messages) {
                if (value instanceof UserMessage message && SpringAiPromptFactory.isResumeCommand(message)
                        && !message.getMetadata().containsKey(IDENTITY_METADATA)
                        && !message.getMetadata().containsKey(CONTENT_METADATA)
                        && SpringAiPromptFactory.sameUserContent(message, expected)) return message;
            }
        }
        throw new IllegalStateException("latest resume input snapshot is missing or differs from command");
    }

    private static UserMessage create(ResumeCommand command, long sequence, String initialText, int budget) {
        StringBuilder text = new StringBuilder(initialText);
        var media = new ArrayList<org.springframework.ai.content.Media>();
        SpringAiAttachmentReader attachments = new SpringAiAttachmentReader(budget);
        for (JsonNode block : command.payload().path("inputs")) {
            if (Set.of("core.file", "core.image", "core.audio").contains(block.path("type").asText())) {
                attachments.append(text, media, block.path("data"));
            }
        }
        UserMessage message = UserMessage.builder().text(text.toString()).media(media).build();
        var metadata = new HashMap<String, Object>();
        metadata.put(SpringAiPromptFactory.RESUME_COMMAND_METADATA, true);
        metadata.put(IDENTITY_METADATA, identity(command, sequence));
        metadata.put(CONTENT_METADATA, OriginalTaskSnapshot.contentFingerprint(message));
        metadata.put(SEQUENCE_METADATA, sequence);
        return UserMessage.builder().text(message.getText()).media(media).metadata(metadata).build();
    }

    private static String commandText(ResumeCommand command) {
        JsonNode payload = command.payload();
        if (payload.isObject() && payload.path("inputs").isArray() && hasAttachments(command)) {
            var visible = (com.fasterxml.jackson.databind.node.ObjectNode) payload.deepCopy();
            visible.remove("inputs");
            List<String> inputs = new ArrayList<>();
            payload.path("inputs").forEach(block -> {
                if (block.path("type").asText().equals("core.text")) inputs.add(block.path("data").path("text").asText());
            });
            if (!inputs.isEmpty()) visible.put("text", String.join("\n", inputs));
            payload = visible;
        }
        return "Resume command (" + command.type() + "): " + payload;
    }

    private static String identity(ResumeCommand command, long sequence) {
        var value = JsonNodeFactory.instance.objectNode().put("type", command.type()).put("sequence", sequence);
        value.set("payload", command.payload());
        return ToolInvocationFingerprint.create("resume-input-v1", value);
    }

    private static void validateContent(UserMessage message) {
        if (!OriginalTaskSnapshot.contentFingerprint(message).equals(message.getMetadata().get(CONTENT_METADATA))) {
            throw new IllegalStateException("resume input snapshot content was changed");
        }
    }

    private static long sequence(UserMessage message) {
        Object sequence = message.getMetadata().get(SEQUENCE_METADATA);
        return sequence instanceof Number value ? value.longValue() : 0;
    }

    private static boolean visible(ResumeCommand command) {
        return command != null && !command.type().equals("tool.approval")
                && !(Set.of("delegation.continue", "schedule.continue", "managed.continue")
                        .contains(command.type()) && command.payload().isEmpty());
    }

    private static boolean hasAttachments(ResumeCommand command) {
        for (JsonNode block : command.payload().path("inputs")) {
            if (Set.of("core.file", "core.image", "core.audio").contains(block.path("type").asText())) return true;
        }
        return false;
    }
}
