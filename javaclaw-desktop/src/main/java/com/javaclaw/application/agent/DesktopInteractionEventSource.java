package com.javaclaw.application.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.desktop.api.*;
import com.javaclaw.framework.api.RunRequest;
import com.javaclaw.framework.spi.CancellationToken;
import com.javaclaw.framework.spi.InteractionEventSource;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/** Bounded subscriptions to native events; no model or status polling runs while waiting. */
public final class DesktopInteractionEventSource implements InteractionEventSource {
    private final DesktopSessionService sessions;
    public DesktopInteractionEventSource(DesktopSessionService sessions) { this.sessions = sessions; }

    @Override public CompletionStage<JsonNode> await(RunRequest request, JsonNode wait,
            CancellationToken cancellation) {
        var owner = new DesktopSessionOwner(request.scope().workspaceId(), request.scope().sessionId(),
                request.source().kind(), request.source().id());
        String session = wait.path("sessionId").asText();
        long after = wait.path("afterCapturedAtMillis").asLong();
        long generation = wait.path("afterWindowGeneration").asLong(-1);
        long revision = wait.path("afterContentRevision").asLong(-1);
        long timeout = Math.min(30_000, Math.max(1, wait.path("timeoutMillis").asLong(30_000)));
        CompletableFuture<JsonNode> result = new CompletableFuture<>();
        AtomicReference<Flow.Subscription> frames = new AtomicReference<>(), states = new AtomicReference<>();
        var cancellationRegistration = cancellation.onCancel(() -> result.completeExceptionally(
                new com.javaclaw.framework.spi.RunCancelledException()));
        result.whenComplete((value, failure) -> {
            cancel(frames); cancel(states); cancellationRegistration.close();
        });
        try {
            sessions.frames(owner, session).subscribe(subscriber(frames, result, frame ->
                    changed(frame, after, generation, revision) ? JsonNodeFactory.instance.objectNode()
                            .put("event", "FRAME_AVAILABLE").put("sessionId", session)
                            .put("targetId", frame.targetId()).put("capturedAtMillis", frame.capturedAtMillis())
                            .put("windowGeneration", frame.windowGeneration()) : null));
            sessions.states(owner, session).subscribe(subscriber(states, result, state ->
                    state.kind() != DesktopSessionState.Kind.LIVE && state.atMillis() > after
                            ? JsonNodeFactory.instance.objectNode().put("event", "STATE_CHANGED")
                                .put("sessionId", session).put("state", state.kind().name()) : null));
            // A frame may have arrived just before subscription. This is one read, not polling.
            sessions.snapshot(owner, session).whenComplete((snapshot, failure) -> {
                if (failure != null) result.completeExceptionally(failure);
                else snapshot.filter(frame -> changed(frame, after, generation, revision)).ifPresent(frame ->
                        result.complete(JsonNodeFactory.instance.objectNode().put("event", "FRAME_AVAILABLE")
                                .put("sessionId", session).put("capturedAtMillis", frame.capturedAtMillis())));
            });
            CompletableFuture.delayedExecutor(timeout, TimeUnit.MILLISECONDS).execute(() ->
                    result.complete(JsonNodeFactory.instance.objectNode().put("event", "WAIT_TIMEOUT")
                            .put("sessionId", session).put("timeoutMillis", timeout)));
        } catch (Throwable failure) { result.completeExceptionally(failure); }
        return result;
    }

    private static boolean changed(DesktopFrame frame, long after, long generation, long revision) {
        return frame.capturedAtMillis() > after && (generation < 0 || revision < 0
                || frame.windowGeneration() != generation || frame.contentRevision() != revision);
    }

    private static void cancel(AtomicReference<Flow.Subscription> ref) {
        var subscription = ref.getAndSet(null); if (subscription != null) subscription.cancel();
    }
    private static <T> Flow.Subscriber<T> subscriber(AtomicReference<Flow.Subscription> ref,
            CompletableFuture<JsonNode> result, Function<T, JsonNode> map) {
        return new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription subscription) {
                if (!ref.compareAndSet(null, subscription) || result.isDone()) subscription.cancel();
                else subscription.request(Long.MAX_VALUE);
            }
            @Override public void onNext(T value) {
                JsonNode event = map.apply(value); if (event != null) result.complete(event);
            }
            @Override public void onError(Throwable failure) { result.completeExceptionally(failure); }
            @Override public void onComplete() { }
        };
    }
}
