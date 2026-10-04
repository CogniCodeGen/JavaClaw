package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.api.InputBlock;
import com.javaclaw.framework.api.ResumeCommand;
import com.javaclaw.framework.core.ReasoningRequest;
import com.javaclaw.framework.core.TaskContractCompiler;
import com.javaclaw.framework.spi.ExtensionStateStore;
import com.javaclaw.framework.spi.RunStore;
import com.javaclaw.prompt.AgentPrompts;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;

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
        // Project instructions can be persisted from an older release. End with the
        // current trusted capability policy so a stale AGENTS.md cannot disable
        // the settings-gated desktop session tools.
        prompt.append("## Harness decision protocol\n")
                .append("Before ending a turn, call harness_submit_decision in a tool-call batch "
                        + "containing no other calls. Set decision to CLAIM_DONE only when you believe "
                        + "the task is done; the host independently verifies trusted evidence. "
                        + "Use NEEDS_INPUT when a user answer is required, BLOCKED for a genuine "
                        + "block, and CONTINUE when work must continue in a later Run or loop round. "
                        + "Put the user-facing response in userMessage. Include unmetCriterionIds "
                        + "as a separate array of still-unmet frozen criterion IDs; use an empty "
                        + "array when there are none. Never encode a control status in userMessage "
                        + "or rely on status words in ordinary text. "
                        + "evidenceRefs is optional: copy only exact reference strings from the "
                        + "host-provided evidenceRefs array in a tool response or control feedback. "
                        + "Never put tool names, result summaries, or invented IDs in evidenceRefs. "
                        + "Use an empty array when no suitable references are available, including "
                        + "NEEDS_INPUT or BLOCKED after a failed tool call. A reference establishes "
                        + "only its tool receipt's operation and target, not the whole task. "
                        + "When a control call is rejected, correct its arguments using the feedback; "
                        + "do not repeat business tools just to repair the control call. "
                        + "The host ignores your final free-form prose for control decisions.\n\n");
        return AgentPrompts.withMandatoryGlobalRules(prompt.toString());
    }

    List<Message> messages(ReasoningRequest request) {
        return messages(request, List.of(), null);
    }

    List<Message> messages(ReasoningRequest request, List<Message> frozenMessages, RunStore runs) {
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
        UserMessage original = originalTaskMessage(request, frozenMessages);
        messages.add(original);
        List<Message> resumeSources = new ArrayList<>(frozenMessages);
        if (resumeSources.stream().noneMatch(value -> value instanceof UserMessage user && isOriginalTask(user))) {
            resumeSources.add(original);
        }
        UserMessage resume = resumeCommandMessage(request, resumeSources, runs);
        if (resume != null) messages.add(resume);
        return List.copyOf(messages);
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
        StringBuilder text = new StringBuilder(originalTaskText(request));
        List<Media> media = new ArrayList<>();
        SpringAiAttachmentReader attachments = new SpringAiAttachmentReader(
                attachmentTextBudget(request, text.length()));
        for (InputBlock block : request.runRequest().inputs()) {
            if (!block.type().equals("core.file") && !block.type().equals("core.image")
                    && !block.type().equals("core.audio")) continue;
            attachments.append(text, media, block.data());
        }
        UserMessage.Builder builder = UserMessage.builder().text(text.toString())
                .metadata(Map.of(ORIGINAL_TASK_METADATA, true));
        if (!media.isEmpty()) builder.media(media);
        return OriginalTaskSnapshot.stamp(request, builder.build());
    }

    static String originalTaskText(ReasoningRequest request) {
        String current = inputText(request);
        String effective = effectiveTaskText(request);
        StringBuilder text = new StringBuilder(effective);
        if (!effective.equals(current) && !current.isBlank()) {
            text.append("\n\nLatest human input (takes precedence for new goals, cancellation "
                    + "and restrictions):\n").append(current)
                    .append("\nThe effective task above is conversation intent, not proof of current "
                            + "permissions or application state. Recheck relevant live tools when "
                            + "continuing after a changed setting; do not infer a current block from "
                            + "an assistant's earlier failure report.");
        }
        return text.toString();
    }

    /** 后续模型步复用首次消息正文及媒体，不重新读取可能已修改或删除的附件。 */
    static UserMessage originalTaskMessage(ReasoningRequest request, List<Message> messages) {
        return OriginalTaskSnapshot.find(request, messages).orElseGet(() -> originalTaskMessage(request));
    }

    private static int attachmentTextBudget(ReasoningRequest request, int taskCharacters) {
        if (request.plan() == null || request.plan().descriptor().stepContextPolicy() == null) {
            return SpringAiAttachmentReader.MAX_TOTAL_TEXT_CHARACTERS;
        }
        int available = request.plan().descriptor().stepContextPolicy().maxMessageCharacters() / 2
                - taskCharacters;
        return Math.max(0, Math.min(SpringAiAttachmentReader.MAX_TOTAL_TEXT_CHARACTERS, available));
    }

    static String effectiveTaskText(ReasoningRequest request) {
        for (String attribute : List.of(TaskContractCompiler.ORIGINAL_REQUEST_ATTRIBUTE,
                TaskContractCompiler.RESOLVED_REQUEST_ATTRIBUTE)) {
            JsonNode value = request.runRequest().attributes().get(attribute);
            if (value != null && value.isTextual() && !value.asText().isBlank()) return value.asText();
        }
        return inputText(request);
    }

    static UserMessage resumeCommandMessage(ReasoningRequest request) {
        return resumeCommandMessage(request.resumeCommand());
    }

    static UserMessage resumeCommandMessage(ReasoningRequest request, List<Message> incoming, RunStore runs) {
        return ResumeCommandSnapshot.resolve(request, incoming, runs);
    }

    static UserMessage resumeCommandMessage(ResumeCommand command) {
        return ResumeCommandSnapshot.create(command);
    }

    private static String inputText(ReasoningRequest request) {
        return request.runRequest().inputs().stream()
                .filter(block -> block.type().equals("core.text"))
                .map(block -> block.data().path("text").asText())
                .collect(java.util.stream.Collectors.joining("\n"));
    }
}
