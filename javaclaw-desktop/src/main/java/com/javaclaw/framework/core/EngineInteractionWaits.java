package com.javaclaw.framework.core;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.*;
import com.javaclaw.framework.spi.StoredRun;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionException;

/** 管理交互等待的订阅、超时与投递，不持有另一套 Run 状态机。 */
final class EngineInteractionWaits {
    private final AgentEngine engine;
    EngineInteractionWaits(AgentEngine engine) { this.engine = engine; }

    void attachInteractionWait(ActiveEngineRun active, RunState state, JsonNode supplied) {
        disposeInteractionWait(active);
        if (supplied == null || supplied.path("invocationId").asText().isBlank()) {
            engine.fail(active, new IllegalArgumentException("interaction wait has no reserved invocation"));
            return;
        }
        JsonNode wait = supplied.deepCopy();
        active.interactionWait = wait;
        long remaining = Math.max(1, java.time.Duration.between(engine.clock.instant(), active.control.deadline()).toMillis());
        active.waitDeadline = engine.waitTimers.schedule(() -> {
            if (active.interactionWait == wait) engine.cancel(active.id, active.control.timeoutReason());
        }, remaining);
        if (state == RunState.WAITING_EVENT) {
            awaitInteractionEvent(active, wait);
            return;
        }
        RunId childId = new RunId(wait.path("childRunId").asText());
        StoredRun child = engine.requireRun(childId);
        if (!active.id.equals(child.request().linkage().parentRunId())
                || !InteractionExecutionPolicy.isInteraction(child.request())) {
            engine.cancel(active.id, new CancelReason("INVALID_INTERACTION_WAIT", "child ownership mismatch"));
            return;
        }
        RunHandle childHandle = engine.handle(childId);
        engine.interactionObserver.accept(new com.javaclaw.framework.spi.ToolContext(active.id, active.request.scope(),
                active.plan.descriptor().permissions(), active.control, active.control.deadline(), active.request), childHandle);
        var subscription = reactor.core.Disposables.swap();
        active.interactionSubscription = subscription;
        long deliveredCursor = engine.runs.eventsAfter(active.id, 0).stream()
                .filter(event -> event.producer().equals("framework.core")
                        && Set.of("core.interaction.child_waiting_input", "core.interaction.child_waiting_approval")
                            .contains(event.type())
                        && childId.value().equals(event.payload().path("childRunId").asText())
                        && wait.path("revision").asLong() == event.payload().path("revision").asLong())
                .mapToLong(event -> event.payload().path("childEventSequence").asLong()).max()
                .orElse(wait.path("afterSequence").asLong(0));
        subscription.update(childHandle.events(deliveredCursor)
                .filter(childEvent -> childEvent.type().equals("core.run.waiting_input")
                        || childEvent.type().equals("core.run.waiting_approval"))
                // 子事件可能在持有子任务启动锁时发出；先切离该线程，
                // 再获取 ANSWER/AMEND/CANCEL 使用的父任务锁。
                .publishOn(reactor.core.scheduler.Schedulers.fromExecutor(engine.executor))
                .subscribe(childEvent -> {
                    synchronized (active.launchLock) {
                        if (active.interactionWait != wait || active.terminated.get() || active.detached.get()
                                || active.control.cancelled() || engine.get(active.id).state() != RunState.WAITING_CHILD
                                || childEvent.schemaVersion() != 1 || !childEvent.producer().equals("framework.core")
                                || !childEvent.runId().equals(childId.value())) return;
                        StoredRun currentChild = engine.requireRun(childId);
                        JsonNode childTask = currentChild.request().attributes().get(InteractionExecutionPolicy.TASK_ATTRIBUTE);
                        if (!active.id.equals(currentChild.request().linkage().parentRunId()) || childTask == null
                                || !childTask.path("taskId").asText().equals(wait.path("taskId").asText())
                                || childTask.path("revision").asLong() != wait.path("revision").asLong()) return;
                        boolean input = childEvent.type().equals("core.run.waiting_input");
                        RunState childState = currentChild.snapshot().state();
                        if (input && childState != RunState.WAITING_INPUT
                                || !input && childState != RunState.WAITING_APPROVAL) return;
                        long latestChallenge = engine.runs.eventsAfter(childId, 0).stream()
                                .filter(event -> event.schemaVersion() == 1 && event.producer().equals("framework.core")
                                        && event.runId().equals(childId.value()) && event.type().equals(childEvent.type()))
                                .mapToLong(RunEventEnvelope::sequence).max().orElse(0);
                        if (childEvent.sequence() != latestChallenge) return;
                        String forwardedType = input ? "core.interaction.child_waiting_input"
                                : "core.interaction.child_waiting_approval";
                        boolean forwarded = engine.runs.eventsAfter(active.id, 0).stream().anyMatch(event ->
                                event.schemaVersion() == 1 && event.producer().equals("framework.core")
                                        && event.runId().equals(active.id.value()) && event.type().equals(forwardedType)
                                        && childId.value().equals(event.payload().path("childRunId").asText())
                                        && wait.path("revision").asLong() == event.payload().path("revision").asLong()
                                        && childEvent.sequence() == event.payload().path("childEventSequence").asLong());
                        if (forwarded) return;
                        ObjectNode payload = JsonNodeFactory.instance.objectNode()
                                .put("childRunId", childId.value()).put("taskId", wait.path("taskId").asText())
                                .put("revision", wait.path("revision").asLong()).put("childEventSequence", childEvent.sequence());
                        payload.set("output", childEvent.payload().path("output"));
                        payload.set("approval", childEvent.payload().path("approval"));
                        engine.appendTransition(active.id, Set.of(RunState.WAITING_CHILD), RunState.WAITING_CHILD,
                                engine.event(active.request, forwardedType, "framework.core", payload, null), null, null)
                                .ifPresent(active.sink::tryEmitNext);
                    }
                }, failure -> engine.cancel(childId, new CancelReason("CHILD_EVENT_STREAM_FAILED", failure.toString()))));
        childHandle.completion().whenCompleteAsync((outcome, failure) -> {
            if (active.interactionWait != wait || active.control.cancelled()) return;
            try {
                StoredRun terminal = engine.requireRun(childId);
                if (!terminal.snapshot().state().terminal()) return; // 关闭或恢复不代表业务完成
                deliverInteractionWait(active, state, wait,
                        engine.json.valueToTree(InteractionResultProjector.project(engine.runs, engine.json, terminal)));
            } catch (Throwable deliveryFailure) {
                synchronized (active.launchLock) {
                    if (active.interactionWait == wait && !active.terminated.get() && !active.detached.get()
                            && engine.get(active.id).state() == state) engine.fail(active, deliveryFailure);
                }
            }
        }, engine.executor);
    }

    /** 有界等待到期交还模型；持续监听的订阅分片到期仍由宿主续订。 */
    private void awaitInteractionEvent(ActiveEngineRun active, JsonNode wait) {
        synchronized (active.launchLock) {
            if (!interactionEventWaitActive(active, wait)) return;
            if (active.interactionEventAwait != null) return;
            var renewal = active.waitRenewal; active.waitRenewal = null;
            if (renewal != null) renewal.cancel();
            long started = System.nanoTime();
            try {
                InteractionEventWaitMode waitMode = InteractionEventWaitMode.forStoredWait(wait);
                long slice = interactionEventWaitSlice(active, wait, waitMode);
                ObjectNode lease = (ObjectNode) wait.deepCopy();
                lease.put("timeoutMillis", slice); // 不得改变持久化基线或已保留调用。
                if (waitMode == InteractionEventWaitMode.BOUNDED)
                    scheduleBoundedInteractionTimeout(active, wait, slice);
                if (slice == 0) return; // 已过期的有界等待不再订阅期限外的新事件。
                var awaited = engine.interactionEvents.await(active.request, lease, active.control).toCompletableFuture();
                active.interactionEventAwait = awaited;
                awaited.whenCompleteAsync((output, failure) -> {
                    synchronized (active.launchLock) {
                        if (active.interactionEventAwait != awaited || !interactionEventWaitActive(active, wait)) return;
                        active.interactionEventAwait = null;
                        try {
                            if (failure != null) {
                                deliverInteractionWait(active, RunState.WAITING_EVENT, wait,
                                        JsonNodeFactory.instance.objectNode().put("event", "EVENT_SOURCE_FAILED")
                                                .put("errorCode", "EVENT_SOURCE_UNAVAILABLE"));
                            } else if (output != null && "WAIT_TIMEOUT".equals(output.path("event").asText())) {
                                validateInteractionTimeout(wait, output, slice);
                                if (waitMode == InteractionEventWaitMode.UNTIL_CHANGE) {
                                    // 提前返回的源超时不能造成忙循环，续订仍使用原基线与原调用。
                                    long elapsed = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                                    long delay = Math.max(1, Math.max(1_000, slice) - elapsed);
                                    active.waitRenewal = engine.waitTimers.schedule(() -> awaitInteractionEvent(active, wait),
                                            delay);
                                }
                                // BOUNDED 的总时限由宿主计时器负责，源超时不能提前恢复或重置它。
                            } else deliverInteractionWait(active, RunState.WAITING_EVENT, wait, output);
                        } catch (Throwable deliveryFailure) { engine.fail(active, deliveryFailure); }
                    }
                }, engine.executor);
            } catch (Throwable deliveryFailure) { engine.fail(active, deliveryFailure); }
        }
    }

    private long interactionEventWaitSlice(ActiveEngineRun active, JsonNode wait, InteractionEventWaitMode waitMode) {
        long remaining = Math.max(1, java.time.Duration.between(engine.clock.instant(), active.control.deadline()).toMillis());
        if (waitMode == InteractionEventWaitMode.BOUNDED && wait.has("waitDeadline")) {
            // 恢复同一次等待不能重置其总时限；旧上下文仍受原 Run 截止时间约束。
            Instant deadline = Instant.parse(wait.path("waitDeadline").asText());
            remaining = Math.min(remaining, Math.max(0, java.time.Duration.between(engine.clock.instant(), deadline).toMillis()));
        }
        return Math.min(remaining, Math.min(30_000, Math.max(1, wait.path("timeoutMillis").asLong(30_000))));
    }

    private void scheduleBoundedInteractionTimeout(ActiveEngineRun active, JsonNode wait, long slice) {
        ObjectNode timedOut = JsonNodeFactory.instance.objectNode().put("event", "WAIT_TIMEOUT")
                .put("sessionId", wait.path("sessionId").asText()).put("timeoutMillis", slice)
                .put("afterCapturedAtMillis", wait.path("afterCapturedAtMillis").asLong())
                .put("waitMode", InteractionEventWaitMode.BOUNDED.name())
                .put("inputAuthority", false).put("acceptanceEvidence", false)
                .put("message", "No desktop change arrived within the bounded wait. Observe fresh state and "
                        + "continue the task; this timeout is not evidence that its goal was achieved.");
        active.waitRenewal = engine.waitTimers.schedule(() -> completeBoundedInteractionTimeout(active, wait, timedOut),
                slice);
    }

    private void completeBoundedInteractionTimeout(ActiveEngineRun active, JsonNode wait, JsonNode output) {
        synchronized (active.launchLock) {
            if (!interactionEventWaitActive(active, wait)) return;
            try {
                JsonNode result = output;
                var awaited = active.interactionEventAwait;
                if (awaited != null && awaited.isDone()) {
                    // 真实事件可能已到达，只是其异步回调仍在排队；不能把它覆盖为超时。
                    try {
                        JsonNode received = awaited.join(); // isDone 已确认，此处不阻塞计时器。
                        if (received != null && "WAIT_TIMEOUT".equals(received.path("event").asText()))
                            validateInteractionTimeout(wait, received, output.path("timeoutMillis").asLong());
                        else result = received;
                    } catch (java.util.concurrent.CompletionException | java.util.concurrent.CancellationException failure) {
                        result = JsonNodeFactory.instance.objectNode().put("event", "EVENT_SOURCE_FAILED")
                                .put("errorCode", "EVENT_SOURCE_UNAVAILABLE");
                    }
                }
                active.waitRenewal = null;
                deliverInteractionWait(active, RunState.WAITING_EVENT, wait, result);
            } catch (Throwable deliveryFailure) {
                engine.fail(active, deliveryFailure);
            }
        }
    }

    private static void validateInteractionTimeout(JsonNode wait, JsonNode output, long slice) {
        if (!output.path("sessionId").asText().equals(wait.path("sessionId").asText())
                || !output.path("timeoutMillis").isIntegralNumber()
                || output.path("timeoutMillis").longValue() != slice)
            throw new IllegalStateException("event source returned an invalid subscription timeout");
    }

    private boolean interactionEventWaitActive(ActiveEngineRun active, JsonNode wait) {
        if (active.interactionWait != wait || active.terminated.get() || active.detached.get()) return false;
        if (active.control.cancelled() || !engine.clock.instant().isBefore(active.control.deadline())) {
            engine.cancel(active.id, active.control.cancellationReason().orElseGet(active.control::timeoutReason));
            return false;
        }
        if (!engine.runs.readable(active.request.scope())) {
            engine.cancel(active.id, new CancelReason("INTERACTION_WAIT_ACCESS_REVOKED", "event wait scope is no longer readable"));
            return false;
        }
        return engine.runs.find(active.id).map(stored -> stored.snapshot().state() == RunState.WAITING_EVENT).orElse(false);
    }

    private void deliverInteractionWait(ActiveEngineRun active, RunState state, JsonNode wait, JsonNode result) {
        synchronized (active.launchLock) {
            if (active.interactionWait != wait || active.terminated.get() || active.detached.get()) return;
            if (active.control.cancelled()) {
                engine.cancel(active.id, active.control.cancellationReason().orElseGet(active.control::timeoutReason)); return;
            }
            String invocation = wait.path("invocationId").asText();
            String tool = state == RunState.WAITING_CHILD ? "interaction_delegate" : "interaction_wait_event";
            ObjectNode step = JsonNodeFactory.instance.objectNode()
                    .put("stepId", StepId.tool(active.id, invocation).value());
            step.set("output", JsonNodeFactory.instance.objectNode().put("status", "SUCCEEDED")
                    .put("errorCode", "").put("displayMessage", "")
                    .set("rawOutput", result));
            ((ObjectNode) step.path("output")).set("modelOutput", result);
            ObjectNode completed = JsonNodeFactory.instance.objectNode().put("tool", tool)
                    .put("invocationId", invocation).put("status", "SUCCEEDED").put("errorCode", "")
                    .put("displayMessage", "").put("durationMillis", 0);
            completed.set("output", result); completed.set("modelOutput", result);
            ObjectNode receipt = JsonNodeFactory.instance.objectNode().put("tool", tool)
                    .put("invocationId", invocation).put("operation", "execute").put("target", "")
                    .put("status", "OBSERVED").put("observedAt", engine.clock.instant().toString())
                    .put("evidenceRef", "core.interaction.result:" + active.id.value() + ":" + invocation)
                    .put("reason", "host delivered the settled interaction result");
            receipt.putObject("metadata").put("delivery", "NOT_SENT");
            ObjectNode resumed = JsonNodeFactory.instance.objectNode()
                    .put("commandType", state == RunState.WAITING_CHILD ? "interaction.result" : "interaction.event");
            resumed.set("command", JsonNodeFactory.instance.objectNode().put("invocationId", invocation));
            ObjectNode delivered = JsonNodeFactory.instance.objectNode().put("invocationId", invocation)
                    .put("childRunId", wait.path("childRunId").asText())
                    .put("revision", wait.path("revision").asLong(1));
            var appended = engine.appendTransitionBatch(active.id, Set.of(state), RunState.RUNNING, List.of(
                    engine.event(active.request, "core.step.completed", "framework.core", step, null),
                    engine.event(active.request, "core.tool.completed", 2, "framework.core", completed, null),
                    engine.event(active.request, "core.tool.receipt", "framework.core", receipt, null),
                    engine.event(active.request, state == RunState.WAITING_CHILD ? "core.interaction.child_completed" : "core.interaction.event_received",
                            "framework.core", delivered, null),
                    engine.event(active.request, "core.run.resumed", "framework.core", resumed, null)));
            if (appended.isEmpty()) return;
            active.control.restoreEffectReceipt(invocation, com.javaclaw.framework.spi.EffectReceiptV1.Status.OBSERVED, "NOT_SENT");
            disposeInteractionWait(active);
            appended.get().forEach(active.sink::tryEmitNext);
            engine.launch(active, new ResumeCommand(resumed.path("commandType").asText(), resumed.path("command")), null,
                    Set.of(RunState.RUNNING));
        }
    }

    void disposeInteractionWait(ActiveEngineRun active) {
        synchronized (active.launchLock) {
            active.interactionWait = null;
            var subscription = active.interactionSubscription;
            active.interactionSubscription = null;
            if (subscription != null) subscription.dispose();
            var awaited = active.interactionEventAwait; active.interactionEventAwait = null;
            if (awaited != null) awaited.cancel(false);
            var renewal = active.waitRenewal; active.waitRenewal = null;
            if (renewal != null) renewal.cancel();
            var deadline = active.waitDeadline; active.waitDeadline = null;
            if (deadline != null) deadline.cancel();
        }
    }

}
