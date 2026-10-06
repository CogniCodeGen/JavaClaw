package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.framework.api.ModelDecisionV1;
import com.javaclaw.framework.api.StepId;
import com.javaclaw.framework.core.ReasoningRequest;
import com.javaclaw.framework.core.ProvisionalOutputGuard;
import com.javaclaw.framework.spi.CancellationToken;
import com.javaclaw.util.SensitiveDataRedactor;
import org.springframework.ai.chat.model.ChatResponse;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

/** A cancellable, provisional display channel for one physical provider request. */
final class DecisionReplyStream implements AutoCloseable {
    private final ReasoningRequest request;
    private final StepId step;
    private final ObjectMapper json;
    private final CancellationToken.CancellationRegistration cancellation;
    private final AtomicReference<Runnable> transport = new AtomicReference<>();
    private final StringBuilder name = new StringBuilder();
    private final StringBuilder arguments = new StringBuilder();
    private final StringBuilder pendingText = new StringBuilder();
    private final StringBuilder rawText = new StringBuilder();
    private String displayedText = "";
    private UserMessageJsonDecoder decoder;
    private Long toolIndex;
    private String toolCallId;
    private boolean started;
    private volatile boolean closed;
    private boolean invalid;
    private boolean sensitive;
    private long lastEmission;

    DecisionReplyStream(ReasoningRequest request, StepId step, ObjectMapper json) {
        this.request = request;
        this.step = step;
        this.json = json;
        cancellation = request.control().onCancel(this::close);
    }

    static boolean eligible(ReasoningRequest request, org.springframework.ai.chat.model.ChatModel model) {
        if (!(model instanceof ReplyStreamingChatModel)) return false;
        if (!request.plan().descriptor().advisors().isEmpty()) return false;
        if (request.plan().outputGuards().stream().anyMatch(guard -> !(guard instanceof ProvisionalOutputGuard))) {
            return false;
        }
        var run = request.runRequest();
        if (run.attributes().getOrDefault("framework.disableTools",
                JsonNodeFactory.instance.booleanNode(false)).asBoolean(false)) return false;
        // SDD also invokes shared chat profiles for its structured phases. Source is authoritative.
        return (run.profile().id().equals("chat") || run.profile().id().equals("plan")
                || run.profile().id().equals("loop"))
                && (run.source().kind().equals("chat") || run.source().kind().equals("plan")
                || run.source().kind().equals("loop"));
    }

    void attachTransport(Runnable close) {
        transport.set(close);
        if (closed && transport.compareAndSet(close, null)) close.run();
    }

    synchronized void toolDelta(long index, String callId, String nameDelta, String argumentDelta) {
        if (closed || invalid || request.control().cancelled()) return;
        if (toolIndex != null && toolIndex != index) { invalidate(); return; }
        toolIndex = index;
        if (callId != null && !callId.isEmpty()) {
            if (toolCallId != null && !toolCallId.equals(callId)) { invalidate(); return; }
            toolCallId = callId;
        }
        if (nameDelta != null && !nameDelta.isEmpty()) {
            name.append(nameDelta);
            if (!HarnessDecisionToolCallback.NAME.startsWith(name.toString())) {
                invalidate();
                return;
            }
        }
        if (argumentDelta != null) arguments.append(argumentDelta);
        if (arguments.length() > 256 * 1024) { invalidate(); return; }
        if (!HarnessDecisionToolCallback.NAME.contentEquals(name)) return;
        try {
            if (decoder == null) {
                decoder = new UserMessageJsonDecoder(json, this::append);
                decoder.accept(arguments.toString());
            } else if (argumentDelta != null) decoder.accept(argumentDelta);
        } catch (IllegalArgumentException malformed) {
            // No raw arguments or exception detail crosses the provisional display boundary.
            invalidate();
        }
    }

    synchronized void complete(ChatResponse response) {
        if (closed || invalid) return;
        try {
            var calls = response.getResult().getOutput().getToolCalls();
            if (calls.size() != 1 || !HarnessDecisionToolCallback.NAME.equals(calls.getFirst().name())) {
                invalidate();
                return;
            }
            if (toolCallId != null && !toolCallId.equals(calls.getFirst().id())) {
                invalidate();
                return;
            }
            ModelDecisionV1.fromJson(json.readTree(calls.getFirst().arguments()));
            prepareText(true);
            flush();
        } catch (IOException | IllegalArgumentException malformed) {
            invalidate();
        }
    }

    private void append(String value) {
        rawText.append(value);
        prepareText(false);
        long now = System.nanoTime();
        if (!started || pendingText.length() >= 128 || now - lastEmission >= 100_000_000L) flush();
    }

    private void prepareText(boolean complete) {
        if (sensitive) return;
        var prefix = JsonNodeFactory.instance.objectNode().put("text", rawText.toString());
        com.fasterxml.jackson.databind.JsonNode safe = prefix;
        for (var guard : request.plan().outputGuards()) {
            safe = ((ProvisionalOutputGuard) guard).validateDraft(safe, request.runRequest(), request.runId());
            if (safe == null) throw new IllegalArgumentException("provisional output rejected");
        }
        String text = safe.path("text").asText("");
        if (!SensitiveDataRedactor.redactText(text).equals(text)) {
            sensitive = true;
            pendingText.setLength(0);
            if (started) emit("core.model.reply.reset", "");
            return;
        }
        // Draft events are durable. Publish closed sentences/lines, so a credential fragmented
        // across SDK chunks is checked as a whole before entering either the trace or the UI.
        // ASCII periods are not boundaries: JWTs and URLs can contain them.
        if (!complete) text = text.substring(0, boundary(text));
        String prior = displayedText + pendingText;
        if (!text.startsWith(prior)) {
            pendingText.setLength(0);
            displayedText = "";
            if (started) emit("core.model.reply.reset", "");
            pendingText.append(text);
        } else pendingText.append(text.substring(prior.length()));
    }

    private static int boundary(String text) {
        for (int index = text.length() - 1; index >= 0; index--) {
            char value = text.charAt(index);
            if (value == '\n' || value == '。' || value == '！' || value == '？') return index + 1;
        }
        return 0;
    }

    private void flush() {
        if (closed || invalid || pendingText.isEmpty()) return;
        request.control().throwIfCancelled();
        if (!started) {
            emit("core.model.reply.started", "");
            started = true;
        }
        emit("core.model.reply.delta", pendingText.toString());
        displayedText += pendingText;
        pendingText.setLength(0);
        lastEmission = System.nanoTime();
    }

    synchronized void invalidate() {
        if (invalid) return;
        invalid = true;
        pendingText.setLength(0);
        if (started && !closed && !request.control().cancelled()) emit("core.model.reply.reset", "");
    }

    private void emit(String type, String text) {
        var payload = JsonNodeFactory.instance.objectNode().put("modelStepId", step.value());
        if (!text.isEmpty()) payload.put("text", text);
        request.events().emit(type, 1, "framework.springai", payload);
    }

    @Override
    public void close() {
        closed = true;
        Runnable close = transport.getAndSet(null);
        try {
            if (close != null) close.run();
        } finally {
            // The callback may run during registration when the Run was already cancelled.
            if (cancellation != null) cancellation.close();
        }
    }
}
