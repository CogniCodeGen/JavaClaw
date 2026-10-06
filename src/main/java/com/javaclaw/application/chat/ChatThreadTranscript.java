package com.javaclaw.application.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.api.TaskResultJson;
import com.javaclaw.framework.api.ThreadEvent;
import com.javaclaw.application.chat.ChatHistoryApplicationService.DeliveryStatus;
import com.javaclaw.application.chat.ChatHistoryApplicationService.MessageRole;
import com.javaclaw.application.chat.ChatHistoryApplicationService.MessageSnapshot;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Rebuilds a display from its own immutable journal, including fork cutoff copies. */
public final class ChatThreadTranscript {
    private ChatThreadTranscript() { }

    public static List<MessageSnapshot> project(List<ThreadEvent> events) {
        List<MessageSnapshot> messages = new ArrayList<>();
        Set<String> hidden = new HashSet<>();
        Map<String, String> turnInputs = new HashMap<>();
        for (ThreadEvent event : events) {
            String turn = event.turnId() == null ? "" : event.turnId().value();
            JsonNode envelope = event.payload();
            if (envelope.has("request")) {
                JsonNode request = envelope.path("request");
                if (request.path("source").path("kind").asText().equals("maintenance")) {
                    hidden.add(turn);
                    continue;
                }
                turnInputs.put(turn, addInput(messages, event, request.path("inputs"), ""));
            }
            if (hidden.contains(turn)) continue;
            JsonNode payload = envelope.path("event").path("payload");
            if (event.type().equals("turn/resumed")
                    && payload.path("commandType").asText().equals("input")) {
                JsonNode command = payload.path("command");
                turnInputs.put(turn, addInput(messages, event, command.path("inputs"),
                        command.path("text").asText("")));
            } else if (event.type().equals("turn/waiting_input")) {
                JsonNode output = payload.path("output");
                if (output.path("kind").asText().equals("clarify_request")) {
                    JsonNode clarification = output.path("payload");
                    String text = clarification(clarification.path("reason").asText(""),
                            clarification.path("question").asText(""));
                    messages.add(message(MessageRole.ASSISTANT, text, event, List.of(), DeliveryStatus.COMPLETE));
                } else {
                    String text = output.path("text").asText("");
                    if (text.isBlank()) text = output.path("value").asText("");
                    if (!text.isBlank()) {
                        messages.add(message(MessageRole.ASSISTANT, text, event,
                                List.of(), DeliveryStatus.COMPLETE));
                    }
                }
            } else if (Set.of("turn/completed", "turn/failed", "turn/cancelled").contains(event.type())) {
                JsonNode output = payload.path("output");
                String text = output.path("text").asText(output.path("value").asText(""));
                DeliveryStatus status = event.type().equals("turn/completed") ? DeliveryStatus.COMPLETE
                        : event.type().equals("turn/failed") ? DeliveryStatus.FAILED : DeliveryStatus.CANCELLED;
                if (status == DeliveryStatus.COMPLETE && !text.isBlank()) {
                    // The saved live reply includes task acceptance; raw model text is not a new message.
                    text = TaskResultDisplay.append(text, TaskResultJson.decode(payload.path("taskResult")),
                            turnInputs.getOrDefault(turn, ""));
                }
                if (!text.isBlank()) messages.add(message(MessageRole.ASSISTANT, text, event, List.of(), status));
                else if (status == DeliveryStatus.FAILED && !payload.path("message").asText().isBlank())
                    messages.add(message(MessageRole.SYSTEM, payload.path("message").asText(), event, List.of(), status));
            }
        }
        return List.copyOf(messages);
    }

    /** Keeps saved display metadata and restores only exact, unambiguous user attachment references. */
    public static List<MessageSnapshot> recoverTail(List<MessageSnapshot> saved, List<MessageSnapshot> journal) {
        int next = 0;
        int insertion = saved.size();
        List<MessageSnapshot> enriched = new ArrayList<>(saved);
        Set<String> savedUsers = uniqueUserContents(saved);
        Set<String> journalUsers = uniqueUserContents(journal);
        for (int stored = 0; stored < saved.size(); stored++) {
            MessageSnapshot visible = saved.get(stored);
            for (int durable = next; durable < journal.size(); durable++) {
                MessageSnapshot candidate = journal.get(durable);
                if (visible.role() == candidate.role() && visible.content().equals(candidate.content())) {
                    if (visible.role() == MessageRole.USER && savedUsers.contains(visible.content())
                            && journalUsers.contains(visible.content())
                            && sameMinute(visible, candidate))
                        enriched.set(stored, recoverUserAttachments(visible, candidate));
                    next = durable + 1;
                    insertion = stored + 1;
                    break;
                }
            }
        }
        if (next == journal.size()) return List.copyOf(enriched);
        List<MessageSnapshot> recovered = new ArrayList<>(enriched.subList(0, insertion));
        recovered.addAll(journal.subList(next, journal.size()));
        recovered.addAll(enriched.subList(insertion, saved.size()));
        return List.copyOf(recovered);
    }

    // Legacy display snapshots have no turn ID; do not borrow references across
    // a different displayed minute when matching their text to the owned journal.
    private static boolean sameMinute(MessageSnapshot saved, MessageSnapshot journal) {
        return saved.timestamp().truncatedTo(ChronoUnit.MINUTES)
                .equals(journal.timestamp().truncatedTo(ChronoUnit.MINUTES));
    }

    private static Set<String> uniqueUserContents(List<MessageSnapshot> messages) {
        Map<String, Integer> counts = new HashMap<>();
        messages.stream().filter(message -> message.role() == MessageRole.USER
                        && !message.content().isBlank())
                .forEach(message -> counts.merge(message.content(), 1, Integer::sum));
        Set<String> unique = new HashSet<>();
        counts.forEach((content, count) -> { if (count == 1) unique.add(content); });
        return unique;
    }

    private static MessageSnapshot recoverUserAttachments(MessageSnapshot saved, MessageSnapshot journal) {
        List<String> attachments = new ArrayList<>(saved.attachmentPaths());
        for (String path : journal.attachmentPaths())
            if (!attachments.contains(path)) attachments.add(path);
        List<String> images = new ArrayList<>(saved.imagePaths());
        for (String path : journal.imagePaths())
            if (!attachments.contains(path) && !images.contains(path)) images.add(path);
        if (images.equals(saved.imagePaths()) && attachments.equals(saved.attachmentPaths())) return saved;
        return new MessageSnapshot(saved.role(), saved.content(), saved.timestamp(), images,
                saved.adopted(), saved.deliveryStatus(), saved.usage(), attachments);
    }

    private static String clarification(String reason, String question) {
        StringBuilder text = new StringBuilder("> 🤔 **需要您的澄清**\n>\n");
        if (!reason.isBlank()) text.append("> **原因**：").append(reason.replace("\n", "\n> ")).append("\n>\n");
        if (!question.isBlank()) text.append("> **问题**：").append(question.replace("\n", "\n> "));
        return text.toString();
    }

    private static String addInput(List<MessageSnapshot> messages, ThreadEvent event, JsonNode inputs, String fallback) {
        StringBuilder text = new StringBuilder();
        List<String> images = new ArrayList<>();
        List<String> attachments = new ArrayList<>();
        for (JsonNode input : inputs) {
            String kind = input.path("type").asText();
            JsonNode data = input.path("data");
            if (kind.equals("core.text")) {
                if (!text.isEmpty()) text.append('\n');
                text.append(data.path("text").asText(""));
            } else if (kind.equals("core.image") || kind.equals("core.file")) {
                String uri = data.path("uri").asText("");
                try {
                    if (uri.startsWith("file:")) {
                        String path = java.nio.file.Path.of(java.net.URI.create(uri))
                                .toAbsolutePath().normalize().toString();
                        if (kind.equals("core.image")) images.add(path);
                        else attachments.add(path);
                    }
                } catch (IllegalArgumentException ignored) { /* keep invalid attachment references in the journal */ }
            }
        }
        if (text.isEmpty()) text.append(fallback);
        if (!text.isEmpty() || !images.isEmpty() || !attachments.isEmpty())
            messages.add(message(MessageRole.USER, text.toString(), event, images, null, attachments));
        return text.toString();
    }

    private static MessageSnapshot message(MessageRole role, String text, ThreadEvent event,
                                           List<String> images, DeliveryStatus delivery) {
        return message(role, text, event, images, delivery, List.of());
    }

    private static MessageSnapshot message(MessageRole role, String text, ThreadEvent event,
                                           List<String> images, DeliveryStatus delivery,
                                           List<String> attachments) {
        return new MessageSnapshot(role, text,
                LocalDateTime.ofInstant(event.timestamp(), ZoneId.systemDefault()), images, false,
                delivery, null, attachments);
    }
}
