package com.javaclaw.application.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.api.ThreadEvent;
import com.javaclaw.application.chat.ChatHistoryApplicationService.*;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Rebuilds a display from its own immutable journal, including fork cutoff copies. */
public final class ChatThreadTranscript {
    private ChatThreadTranscript() { }

    public static List<MessageSnapshot> project(List<ThreadEvent> events) {
        List<MessageSnapshot> messages = new ArrayList<>();
        Set<String> hidden = new HashSet<>();
        for (ThreadEvent event : events) {
            String turn = event.turnId() == null ? "" : event.turnId().value();
            JsonNode envelope = event.payload();
            if (envelope.has("request")) {
                JsonNode request = envelope.path("request");
                if (request.path("source").path("kind").asText().equals("maintenance")) {
                    hidden.add(turn);
                    continue;
                }
                addInput(messages, event, request.path("inputs"), "");
            }
            if (hidden.contains(turn)) continue;
            JsonNode payload = envelope.path("event").path("payload");
            if (event.type().equals("turn/resumed")
                    && payload.path("commandType").asText().equals("input")) {
                JsonNode command = payload.path("command");
                addInput(messages, event, command.path("inputs"), command.path("text").asText(""));
            } else if (event.type().equals("turn/waiting_input")) {
                JsonNode output = payload.path("output");
                if (output.path("kind").asText().equals("clarify_request")) {
                    JsonNode clarification = output.path("payload");
                    String text = clarification(clarification.path("reason").asText(""),
                            clarification.path("question").asText(""));
                    messages.add(message(MessageRole.ASSISTANT, text, event, List.of(), DeliveryStatus.COMPLETE));
                }
            } else if (Set.of("turn/completed", "turn/failed", "turn/cancelled").contains(event.type())) {
                JsonNode output = payload.path("output");
                String text = output.path("text").asText(output.path("value").asText(""));
                DeliveryStatus status = event.type().equals("turn/completed") ? DeliveryStatus.COMPLETE
                        : event.type().equals("turn/failed") ? DeliveryStatus.FAILED : DeliveryStatus.CANCELLED;
                if (!text.isBlank()) messages.add(message(MessageRole.ASSISTANT, text, event, List.of(), status));
                else if (status == DeliveryStatus.FAILED && !payload.path("message").asText().isBlank())
                    messages.add(message(MessageRole.SYSTEM, payload.path("message").asText(), event, List.of(), status));
            }
        }
        return List.copyOf(messages);
    }

    /** Adds only the journal tail absent from the display; existing adoption and image metadata survive. */
    public static List<MessageSnapshot> recoverTail(List<MessageSnapshot> saved, List<MessageSnapshot> journal) {
        int next = 0;
        int insertion = saved.size();
        for (int stored = 0; stored < saved.size(); stored++) {
            MessageSnapshot visible = saved.get(stored);
            for (int durable = next; durable < journal.size(); durable++) {
                MessageSnapshot candidate = journal.get(durable);
                if (visible.role() == candidate.role() && visible.content().equals(candidate.content())) {
                    next = durable + 1;
                    insertion = stored + 1;
                    break;
                }
            }
        }
        if (next == journal.size()) return List.copyOf(saved);
        List<MessageSnapshot> recovered = new ArrayList<>(saved.subList(0, insertion));
        recovered.addAll(journal.subList(next, journal.size()));
        recovered.addAll(saved.subList(insertion, saved.size()));
        return List.copyOf(recovered);
    }

    private static String clarification(String reason, String question) {
        StringBuilder text = new StringBuilder("> 🤔 **需要您的澄清**\n>\n");
        if (!reason.isBlank()) text.append("> **原因**：").append(reason.replace("\n", "\n> ")).append("\n>\n");
        if (!question.isBlank()) text.append("> **问题**：").append(question.replace("\n", "\n> "));
        return text.toString();
    }

    private static void addInput(List<MessageSnapshot> messages, ThreadEvent event, JsonNode inputs, String fallback) {
        StringBuilder text = new StringBuilder();
        List<String> images = new ArrayList<>();
        for (JsonNode input : inputs) {
            String kind = input.path("type").asText();
            JsonNode data = input.path("data");
            if (kind.equals("core.text")) {
                if (!text.isEmpty()) text.append('\n');
                text.append(data.path("text").asText(""));
            } else if (kind.equals("core.image")) {
                String uri = data.path("uri").asText("");
                try {
                    if (uri.startsWith("file:")) images.add(java.nio.file.Path.of(java.net.URI.create(uri)).toString());
                } catch (IllegalArgumentException ignored) { /* keep invalid attachment references in the journal */ }
            }
        }
        if (text.isEmpty()) text.append(fallback);
        if (!text.isEmpty() || !images.isEmpty())
            messages.add(message(MessageRole.USER, text.toString(), event, images, null));
    }

    private static MessageSnapshot message(MessageRole role, String text, ThreadEvent event,
                                           List<String> images, DeliveryStatus delivery) {
        return new MessageSnapshot(role, text,
                LocalDateTime.ofInstant(event.timestamp(), ZoneId.systemDefault()), images, false, delivery, null);
    }
}
