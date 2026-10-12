package com.javaclaw.framework.core;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.*;
import com.javaclaw.framework.spi.StoredRun;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** 串行校验审批与交互控制请求；RunState 更新只交回唯一引擎执行。 */
final class EngineResumeCoordinator {
    private final AgentEngine engine;
    EngineResumeCoordinator(AgentEngine engine) { this.engine = engine; }

    /** 同一子任务的通用恢复与精确问题 ANSWER 准入共用串行锁。 */
    RunHandle resumeAttached(ActiveEngineRun active, RunId runId, ResumeCommand command) {
        RunState current = engine.get(runId).state();
        if (current.terminal()) return engine.handle(runId);
        if (current != RunState.PAUSED && current != RunState.WAITING_INPUT && current != RunState.WAITING_APPROVAL
                && current != RunState.WAITING_CHILD && current != RunState.WAITING_EVENT)
            throw new IllegalStateException("run is not resumable: " + runId);
        if (PersistedRunDeadline.workflowCoordinator(active.request)
                && (current == RunState.PAUSED || current == RunState.WAITING_INPUT)) {
            active.control.restoreDeadline(PersistedRunDeadline.resolve(engine.requireRun(runId),
                    active.plan.descriptor().budget(), engine.runs.eventsAfter(runId, 0), engine.clock.instant()));
        }
        if (active.control.expired()) {
            engine.cancel(runId, active.control.cancellationReason().orElseGet(active.control::timeoutReason));
            return engine.handle(runId);
        }
        RunHandle interaction = resumeInteractionWait(active, current, command);
        if (interaction != null) return interaction;
        if (!engine.runs.claim(runId)) throw new IllegalStateException("another turn owns this thread");
        if (command.type().equals("input.continue")) {
            // 继续已恢复的审批只重开当前问题，不授予权限。
            if (active.pendingApproval != null) {
                if (current != RunState.PAUSED && current != RunState.WAITING_APPROVAL) {
                    throw new IllegalStateException("pending approval has no resumable approval state");
                }
                ObjectNode review = JsonNodeFactory.instance.objectNode();
                review.set("approval", active.pendingApproval.toJson());
                review.put("reason", "APPROVAL_REVIEW_REQUESTED");
                var reopened = engine.appendTransition(runId, Set.of(current), RunState.WAITING_APPROVAL,
                        engine.event(active.request, "core.run.waiting_approval", "framework.core", review, null),
                        null, null);
                if (reopened.isEmpty()) throw new IllegalStateException("pending approval could not be reopened");
                active.sink.tryEmitNext(reopened.get());
                return engine.activeHandle(active);
            }
            if (current == RunState.WAITING_APPROVAL) {
                throw new IllegalStateException("waiting approval has no pending challenge");
            }
            command = new ResumeCommand("input", command.payload());
        }
        ApprovedToolInvocation approvedInvocation = active.pendingApprovedInvocation;
        if (command.type().equals("tool.approval")) {
            String fingerprint = command.payload().path("fingerprint").asText("");
            ToolApprovalChallenge challenge = active.pendingApproval;
            if (fingerprint.isBlank()) {
                throw new IllegalArgumentException("tool approval resume requires a fingerprint");
            }
            boolean matchesApprovedContinuation = approvedInvocation != null
                    && approvedInvocation.challenge().fingerprint().equals(fingerprint);
            if ((challenge == null || !challenge.fingerprint().equals(fingerprint))
                    && !matchesApprovedContinuation) {
                throw new IllegalArgumentException(
                        "approved tool fingerprint does not match pending challenge");
            }
            if (!command.payload().path("approved").asBoolean(false)) {
                engine.cancel(runId, new CancelReason(
                        "TOOL_APPROVAL_DENIED", "tool approval was explicitly denied"));
                return engine.activeHandle(active);
            }
            if (!matchesApprovedContinuation) {
                ToolApprovalGrant grant = new ToolApprovalGrant(
                        challenge.tool(), fingerprint, true,
                        command.payload().path("humanApproved").asBoolean(false));
                approvedInvocation = new ApprovedToolInvocation(challenge, grant);
            }
        } else if (active.pendingApproval != null) {
            throw new IllegalArgumentException(
                    "a run waiting for tool approval requires a tool.approval command");
        }
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("commandType", command.type());
        payload.set("command", command.payload());
        if (active.ready.isDone()) active.ready = new CompletableFuture<>();
        var event = engine.appendTransition(runId,
                Set.of(RunState.WAITING_INPUT, RunState.WAITING_APPROVAL, RunState.WAITING_CHILD,
                        RunState.WAITING_EVENT, RunState.PAUSED),
                RunState.RUNNING,
                engine.event(active.request, "core.run.resumed", "framework.core", payload, null),
                null, null);
        if (event.isEmpty()) {
            throw new IllegalStateException("run is not resumable: " + runId);
        }
        if (approvedInvocation != null) {
            active.pendingApprovedInvocation = approvedInvocation;
            active.control.approveToolCall(approvedInvocation.grant());
        }
        active.pendingApproval = null;
        active.sink.tryEmitNext(event.get());
        engine.launch(active, command, approvedInvocation, Set.of(RunState.RUNNING));
        return engine.activeHandle(active);
    }

    /** 显式继续只重连宿主等待；未完成的原工具不进入新的推理或再次派发。 */
    private RunHandle resumeInteractionWait(ActiveEngineRun active, RunState current, ResumeCommand command) {
        synchronized (active.launchLock) {
            List<RunEventEnvelope> events = engine.runs.eventsAfter(active.id, 0);
            var pending = new RunStepQuery(engine.runs).steps(active.id).stream()
                    .filter(step -> step.kind() == AgentStep.Kind.TOOL && step.state() == AgentStep.State.RUNNING
                            && step.input() != null
                            && Set.of(InteractionExecutionPolicy.DELEGATE_TOOL, InteractionExecutionPolicy.WAIT_EVENT_TOOL)
                                .contains(step.input().path("tool").asText())).toList();
            if (pending.isEmpty()) {
                if (current == RunState.WAITING_CHILD || current == RunState.WAITING_EVENT)
                    throw new IllegalStateException("interaction wait has no unsettled reserved tool");
                return null;
            }
            RunEventEnvelope lastWait = events.stream().filter(event -> event.schemaVersion() == 1
                            && "framework.core".equals(event.producer())
                            && (Set.of("core.run.waiting_child", "core.run.waiting_event").contains(event.type())
                                || "core.tool.suspended".equals(event.type()))
                            && pending.size() == 1
                            && StepId.tool(active.id, interactionWaitOutput(event).path("invocationId").asText())
                                .equals(pending.getFirst().id())
                            && (!"core.tool.suspended".equals(event.type())
                                || (event.payload().path("tool").asText().equals(pending.getFirst().input().path("tool").asText())
                                    && event.payload().path("invocationId").asText()
                                        .equals(interactionWaitOutput(event).path("invocationId").asText()))))
                    .max(java.util.Comparator.comparingLong(RunEventEnvelope::sequence)).orElse(null);
            JsonNode output = lastWait == null ? null : interactionWaitOutput(lastWait);
            if (output == null && pending.size() == 1
                    && InteractionExecutionPolicy.DELEGATE_TOOL.equals(pending.getFirst().input().path("tool").asText()))
                output = recoverCreatedInteractionChild(active, pending.getFirst());
            if (pending.size() != 1 || output == null || !output.isObject()
                    || !StepId.tool(active.id, output.path("invocationId").asText()).equals(pending.getFirst().id())) {
                throw new IllegalStateException("unsettled interaction tool has no recoverable host wait");
            }
            if (!"input.continue".equals(command.type())) throw new IllegalArgumentException(
                    "a suspended interaction requires explicit input.continue; use interaction controls for answers or changes");
            if (current != RunState.PAUSED && current != RunState.WAITING_CHILD && current != RunState.WAITING_EVENT)
                throw new IllegalStateException("interaction wait is not in a resumable state");
            JsonNode recoveredWait = output;
            RunEventEnvelope unfinishedControl = events.stream().filter(event -> event.schemaVersion() == 1
                    && "framework.core".equals(event.producer())
                    && "core.interaction.control_accepted".equals(event.type())
                    && event.payload().path("revision").asLong() == recoveredWait.path("revision").asLong()
                    && event.payload().path("childRunId").asText().equals(recoveredWait.path("childRunId").asText())
                    && !interactionControlApplied(events, event.payload().path("commandId").asText()))
                    .max(java.util.Comparator.comparingLong(RunEventEnvelope::sequence)).orElse(null);
            if (unfinishedControl != null && "AMEND".equals(unfinishedControl.payload().path("type").asText()))
                return recoverInteractionAmend(active, current, output, unfinishedControl);
            if (unfinishedControl != null && "CANCEL".equals(unfinishedControl.payload().path("type").asText())) {
                engine.cancel(active.id, new CancelReason("USER_REQUEST", "recovered interaction cancellation"));
                return engine.handle(active.id);
            }
            if (!engine.runs.claim(active.id)) throw new IllegalStateException("another turn owns this thread");
            RunState waiting = InteractionExecutionPolicy.DELEGATE_TOOL.equals(pending.getFirst().input().path("tool").asText())
                    ? RunState.WAITING_CHILD : RunState.WAITING_EVENT;
            output = output.deepCopy();
            if (waiting == RunState.WAITING_CHILD && output instanceof ObjectNode details) {
                RunId childId = new RunId(output.path("childRunId").asText());
                var challenge = InteractionAnswerChallenge.current(childId, engine.get(childId).state(), engine.runs.eventsAfter(childId, 0));
                details.put("needsAnswer", challenge.isPresent())
                        .put("childEventSequence", challenge.map(RunEventEnvelope::sequence).orElse(0L));
                details.remove("currentChallenge");
                challenge.ifPresent(event -> details.set("currentChallenge", event.payload().path("output")));
            }
            ObjectNode payload = JsonNodeFactory.instance.objectNode().put("reason", "EXPLICIT_WAIT_REATTACH");
            payload.set("output", output);
            var reopened = engine.appendTransition(active.id, Set.of(current), waiting,
                    engine.event(active.request, waiting == RunState.WAITING_CHILD ? "core.run.waiting_child" : "core.run.waiting_event",
                            "framework.core", payload, null), output, null);
            if (reopened.isEmpty()) throw new IllegalStateException("interaction wait could not be reattached");
            active.sink.tryEmitNext(reopened.get());
            engine.attachInteractionWait(active, waiting, output);
            if (waiting == RunState.WAITING_CHILD) {
                RunId childId = new RunId(output.path("childRunId").asText());
                if (unfinishedControl != null && "ANSWER".equals(unfinishedControl.payload().path("type").asText()))
                    applyInteractionAnswer(active, childId, unfinishedControl.payload());
                if (engine.get(childId).state() == RunState.PAUSED
                        && InteractionAnswerChallenge.current(childId, RunState.PAUSED, engine.runs.eventsAfter(childId, 0)).isEmpty())
                    engine.resume(childId, new ResumeCommand("input.continue", JsonNodeFactory.instance.objectNode()));
            }
            return engine.activeHandle(active);
        }
    }

    private static JsonNode interactionWaitOutput(RunEventEnvelope event) {
        return event.payload().path("core.tool.suspended".equals(event.type()) ? "wait" : "output");
    }

    /** child 创建与 suspended 之间的崩溃窗口只按宿主父步骤关联恢复，绝不重新派发 delegate。 */
    private JsonNode recoverCreatedInteractionChild(ActiveEngineRun parent, AgentStep pending) {
        String invocation = pending.input().path("invocationId").asText();
        if (invocation.isBlank() || !StepId.tool(parent.id, invocation).equals(pending.id()))
            throw new IllegalStateException("reserved delegate invocation identity is unavailable");
        var candidates = engine.runs.childRuns(parent.id).stream().filter(child ->
                parent.id.equals(child.request().linkage().parentRunId())
                        && InteractionExecutionPolicy.isInteraction(child.request())
                        && InteractionDelegateCoordinator.childScope(parent.request.scope()).equals(child.request().scope())
                        && pending.id().value().equals(child.request().attributes()
                            .getOrDefault("framework.parentStepId", JsonNodeFactory.instance.nullNode()).asText()))
                .toList();
        if (candidates.size() != 1)
            throw new IllegalStateException("reserved delegate has no unique recoverable child");
        StoredRun child = candidates.getFirst();
        InteractionRequestGuard.validate(engine.runs, engine.json, child.request());
        JsonNode task = child.request().attributes().get(InteractionExecutionPolicy.TASK_ATTRIBUTE);
        if (task == null || task.path("revision").asLong() != 1
                || !invocation.equals(task.path("taskId").asText())
                || !"interaction-engine.executor".equals(child.request().source().id()))
            throw new SecurityException("created interaction child revision or source mismatch");
        return JsonNodeFactory.instance.objectNode().put("kind", "interaction.waiting_child")
                .put("childRunId", child.snapshot().id().value()).put("invocationId", invocation)
                .put("taskId", task.path("taskId").asText()).put("revision", 1).put("afterSequence", 0);
    }

    private static boolean interactionControlApplied(List<RunEventEnvelope> events, String commandId) {
        return events.stream().anyMatch(event -> event.schemaVersion() == 1
                && "framework.core".equals(event.producer())
                && "core.interaction.control_applied".equals(event.type())
                && commandId.equals(event.payload().path("commandId").asText()));
    }

    /** 子任务的持久化 resumed 是答案投递边界；父 applied 丢失时只补确认，不再次投递。 */
    private boolean applyInteractionAnswer(ActiveEngineRun parent, RunId childId, JsonNode command) {
        parent.control.throwIfCancelled();
        ActiveEngineRun childActive = engine.activeRuns.get(childId);
        // 终态子任务在重启后没有运行锁，但持久化投递标记仍允许精确确认。
        // 未附着的非终态子任务绝不接收输入。
        synchronized (childActive == null ? parent.launchLock : childActive.launchLock) {
            StoredRun child = engine.requireReadableRun(childId);
            try { requireInteractionAnswerIdentity(parent, child, command); }
            catch (IllegalArgumentException | SecurityException invalid) {
                return rejectInteractionAnswer(parent, childId, command, "STALE_INTERACTION_ANSWER");
            }
            long sequence = command.path("childEventSequence").asLong(0);
            if (!command.path("childEventSequence").isIntegralNumber()
                    || !command.path("childEventSequence").canConvertToLong() || sequence < 1)
                return rejectInteractionAnswer(parent, childId, command, "INTERACTION_ANSWER_CHALLENGE_REQUIRED");
            String commandId = command.path("commandId").asText();
            var delivered = engine.runs.eventsAfter(childId, 0).stream().filter(event -> event.schemaVersion() == 1
                    && event.runId().equals(childId.value()) && event.producer().equals("framework.core")
                    && event.type().equals("core.run.resumed")
                    && commandId.equals(event.payload().path("command").path("interactionCommandId").asText())).toList();
            if (!delivered.isEmpty()) {
                if (delivered.size() != 1 || !delivered.getFirst().payload().path("command")
                        .path("interactionChildEventSequence").isIntegralNumber()
                        || delivered.getFirst().payload().path("command").path("interactionChildEventSequence").asLong() != sequence
                        || delivered.getFirst().payload().path("command").path("interactionRevision").asLong()
                            != command.path("revision").asLong()
                        || !delivered.getFirst().payload().path("command").path("interactionTaskId").asText()
                            .equals(command.path("taskId").asText()))
                    return rejectInteractionAnswer(parent, childId, command, "INTERACTION_ANSWER_DELIVERY_MISMATCH");
                recordInteractionControlApplied(parent, command);
                return true;
            }
            if (child.snapshot().state().terminal()) {
                recordInteractionControlApplied(parent, ((ObjectNode) command).deepCopy()
                        .put("delivery", "CHILD_ALREADY_SETTLED"));
                return true;
            }
            if (childActive == null)
                return rejectInteractionAnswer(parent, childId, command, "INTERACTION_ANSWER_CHILD_UNAVAILABLE");
            if (InteractionAnswerChallenge.current(childId, child.snapshot().state(), engine.runs.eventsAfter(childId, 0))
                    .map(RunEventEnvelope::sequence).orElse(0L) != sequence)
                return rejectInteractionAnswer(parent, childId, command, "STALE_INTERACTION_ANSWER_CHALLENGE");
            engine.resume(childId, new ResumeCommand(child.snapshot().state() == RunState.PAUSED ? "input.continue" : "input",
                    JsonNodeFactory.instance.objectNode().put("text", command.path("text").asText())
                            .put("interactionCommandId", commandId).put("interactionChildEventSequence", sequence)
                            .put("interactionRevision", command.path("revision").asLong())
                            .put("interactionTaskId", command.path("taskId").asText())));
            recordInteractionControlApplied(parent, command);
            return true;
        }
    }

    /** 校验及持久化恢复期间，调用方依次持有父、子任务的启动锁。 */
    private void requireInteractionAnswerIdentity(ActiveEngineRun parent, StoredRun child, JsonNode command) {
        JsonNode wait = parent.interactionWait;
        if (!currentInteractionAnswerChild(parent, child)
                || !command.path("childRunId").asText().equals(child.snapshot().id().value())
                || !command.path("revision").isIntegralNumber() || command.path("revision").asLong() < 1
                || command.path("revision").asLong() != wait.path("revision").asLong()
                || command.path("taskId").asText().isBlank()
                || !command.path("taskId").asText().equals(wait.path("taskId").asText()))
            throw new SecurityException("interaction answer child, task or revision mismatch");
    }

    /** 界面恢复独立推导，不依赖旧命令中缺失或过期的字段。 */
    private boolean currentInteractionAnswerChild(ActiveEngineRun parent, StoredRun child) {
        JsonNode wait = parent.interactionWait;
        JsonNode task = child.request().attributes().get(InteractionExecutionPolicy.TASK_ATTRIBUTE);
        return wait != null && parent.id.equals(child.request().linkage().parentRunId())
                && InteractionExecutionPolicy.isInteraction(child.request())
                && InteractionDelegateCoordinator.childScope(parent.request.scope()).equals(child.request().scope())
                && engine.runs.readable(child.request().scope()) && task != null
                && wait.path("childRunId").asText().equals(child.snapshot().id().value())
                && wait.path("revision").asLong() > 0
                && wait.path("revision").asLong() == task.path("revision").asLong()
                && !wait.path("taskId").asText().isBlank()
                && wait.path("taskId").asText().equals(task.path("taskId").asText());
    }

    /** 结算过期的已接受命令，不将其用于当前问题。 */
    private boolean rejectInteractionAnswer(ActiveEngineRun parent, RunId childId, JsonNode command, String errorCode) {
        StoredRun child = engine.requireReadableRun(childId);
        var challenge = currentInteractionAnswerChild(parent, child)
                ? InteractionAnswerChallenge.current(childId, child.snapshot().state(), engine.runs.eventsAfter(childId, 0))
                : java.util.Optional.<RunEventEnvelope>empty();
        ObjectNode rejected = ((ObjectNode) command).deepCopy().put("delivery", "REJECTED").put("errorCode", errorCode)
                .put("needsAnswer", challenge.isPresent())
                .put("currentChallengeSequence", challenge.map(RunEventEnvelope::sequence).orElse(0L));
        challenge.ifPresent(event -> rejected.set("currentChallenge", event.payload().path("output")));
        recordInteractionControlApplied(parent, rejected);
        return false;
    }

    private RunHandle recoverInteractionAmend(ActiveEngineRun parent, RunState current, JsonNode wait,
            RunEventEnvelope accepted) {
        JsonNode data = accepted.payload();
        InteractionControlCommand command = new InteractionControlCommand(data.path("commandId").asText(),
                data.path("revision").asLong(), InteractionControlCommand.Type.AMEND, data.path("text").asText());
        RunId childId = new RunId(wait.path("childRunId").asText());
        StoredRun child = engine.requireReadableRun(childId);
        if (!parent.id.equals(child.request().linkage().parentRunId())
                || !InteractionExecutionPolicy.isInteraction(child.request())
                || !childId.value().equals(data.path("childRunId").asText())
                || wait.path("revision").asLong() != command.expectedRevision())
            throw new SecurityException("amendment does not match the reserved interaction wait");
        parent.control.throwIfCancelled();
        if (!engine.runs.claim(parent.id)) throw new IllegalStateException("another turn owns this thread");
        engine.disposeInteractionWait(parent);
        ObjectNode payload = ((ObjectNode) data).deepCopy().put("reason", "EXPLICIT_AMENDMENT_RECOVERY");
        var resumed = engine.appendTransition(parent.id, Set.of(current), RunState.RUNNING,
                engine.event(parent.request, "core.interaction.amending", "framework.core", payload, null), null, null);
        if (resumed.isEmpty()) throw new IllegalStateException("interaction amendment cannot be resumed");
        parent.sink.tryEmitNext(resumed.get());
        scheduleInteractionAmend(parent, child, wait, command);
        return engine.activeHandle(parent);
    }

    private void scheduleInteractionAmend(ActiveEngineRun parent, StoredRun child, JsonNode wait,
            InteractionControlCommand command) {
        parent.waitDeadline = engine.waitTimers.schedule(() -> {
            if (!engine.get(parent.id).state().terminal()) engine.cancel(parent.id, parent.control.timeoutReason());
        }, Math.max(1, parent.control.remaining().toMillis()));
        ActiveEngineRun old = engine.activeRuns.get(child.snapshot().id());
        engine.cancel(child.snapshot().id(), new CancelReason("TASK_AMENDED", command.commandId()));
        CompletionStage<Void> settled = old == null ? CompletableFuture.completedFuture(null) : old.physicallySettled;
        settled.whenCompleteAsync((ignored, failure) -> {
            try { amendInteraction(parent, child, wait, command); }
            catch (Throwable failed) { engine.fail(parent, failed); }
        }, engine.executor);
    }

    RunHandle controlInteraction(RunId parentId, InteractionControlCommand command) {
        engine.ensureOpen(); engine.requireReadableRun(parentId);
        ActiveEngineRun parent = engine.activeRuns.get(parentId);
        if (parent == null) throw new IllegalStateException("interaction parent is not attached");
        synchronized (parent.launchLock) {
            RunEventEnvelope duplicate = engine.runs.eventsAfter(parentId, 0).stream().filter(event ->
                    event.type().equals("core.interaction.control_accepted")
                            && event.producer().equals("framework.core")
                            && event.payload().path("commandId").asText().equals(command.commandId()))
                    .findFirst().orElse(null);
            if (duplicate != null) {
                if (duplicate.payload().path("revision").asLong() != command.expectedRevision()
                        || !duplicate.payload().path("type").asText().equals(command.type().name())
                        || !duplicate.payload().path("text").asText().equals(command.text())
                        || duplicate.payload().path("childEventSequence").asLong(0) != command.childEventSequence())
                    throw new IllegalArgumentException("interaction command id was already used for another command");
                if (engine.runs.eventsAfter(parentId, 0).stream().anyMatch(event -> event.schemaVersion() == 1
                        && event.producer().equals("framework.core") && event.type().equals("core.interaction.control_applied")
                        && event.payload().path("commandId").asText().equals(command.commandId())
                        && event.payload().path("delivery").asText().equals("REJECTED")))
                    throw new IllegalArgumentException("STALE_INTERACTION_ANSWER_CHALLENGE");
                return engine.handle(parentId);
            }
            JsonNode wait = parent.interactionWait;
            if (wait == null || engine.get(parentId).state() != RunState.WAITING_CHILD)
                throw new IllegalStateException("parent is not waiting for an interaction task");
            if (wait.path("revision").asLong() != command.expectedRevision())
                throw new IllegalArgumentException("STALE_INTERACTION_REVISION");
            RunId childId = new RunId(wait.path("childRunId").asText());
            StoredRun child = engine.requireRun(childId);
            if (!parentId.equals(child.request().linkage().parentRunId()))
                throw new SecurityException("interaction child owner mismatch");
            Object controlLock = parent.launchLock;
            if (command.type() == InteractionControlCommand.Type.ANSWER) {
                ActiveEngineRun childActive = engine.activeRuns.get(childId);
                if (childActive == null) throw new IllegalStateException("interaction answer child is not attached");
                controlLock = childActive.launchLock;
            }
            synchronized (controlLock) {
                if (command.type() == InteractionControlCommand.Type.ANSWER) child = engine.requireReadableRun(childId);
                ObjectNode accepted = JsonNodeFactory.instance.objectNode()
                        .put("commandId", command.commandId()).put("revision", command.expectedRevision())
                        .put("childRunId", childId.value()).put("type", command.type().name())
                        .put("text", command.text()).put("taskId", wait.path("taskId").asText())
                        .put("childEventSequence", command.childEventSequence());
                if (command.type() == InteractionControlCommand.Type.ANSWER) {
                    requireInteractionAnswerIdentity(parent, child, accepted);
                    if (command.childEventSequence() < 1)
                        throw new IllegalArgumentException("INTERACTION_ANSWER_CHALLENGE_REQUIRED");
                    if (InteractionAnswerChallenge.current(childId, child.snapshot().state(), engine.runs.eventsAfter(childId, 0))
                            .map(RunEventEnvelope::sequence).orElse(0L) != command.childEventSequence())
                        throw new IllegalArgumentException("STALE_INTERACTION_ANSWER_CHALLENGE");
                }
                var acceptedEvent = engine.appendTransition(parentId, Set.of(RunState.WAITING_CHILD), RunState.WAITING_CHILD,
                        engine.event(parent.request, "core.interaction.control_accepted", "framework.core", accepted, null), null, null)
                        .orElseThrow(() -> new IllegalStateException("interaction command could not be accepted"));
                parent.sink.tryEmitNext(acceptedEvent);
                parent.control.throwIfCancelled();
                switch (command.type()) {
                    case ANSWER -> {
                        if (!applyInteractionAnswer(parent, childId, accepted))
                            throw new IllegalArgumentException("STALE_INTERACTION_ANSWER_CHALLENGE");
                    }
                    case CANCEL -> {
                        engine.cancel(parentId, new CancelReason("USER_REQUEST", "interaction task cancelled by user"));
                    }
                    case AMEND -> {
                        var amending = engine.appendTransition(parentId, Set.of(RunState.WAITING_CHILD), RunState.RUNNING,
                                engine.event(parent.request, "core.interaction.amending", "framework.core", accepted, null), null, null)
                                .orElseThrow(() -> new IllegalStateException("interaction amendment could not be activated"));
                        engine.disposeInteractionWait(parent); // 旧修订的迟到完成不能覆盖新修订
                        parent.sink.tryEmitNext(amending);
                        scheduleInteractionAmend(parent, child, wait, command);
                    }
                }
                return engine.handle(parentId);
            }
        }
    }

    private void amendInteraction(ActiveEngineRun parent, StoredRun old, JsonNode previousWait,
            InteractionControlCommand command) throws Exception {
        parent.control.throwIfCancelled();
        InteractionTask previous = engine.json.treeToValue(old.request().attributes().get(InteractionExecutionPolicy.TASK_ATTRIBUTE), InteractionTask.class);
        String goal = previous.goal() + "\n用户修订：" + command.text();
        String idempotency = "interaction-amend:" + parent.id.value() + ":" + command.commandId();
        StoredRun existing = engine.runs.findByIdempotencyKey(old.request().scope(), idempotency).orElse(null);
        if (existing != null) {
            InteractionTask task = engine.json.treeToValue(existing.request().attributes()
                    .get(InteractionExecutionPolicy.TASK_ATTRIBUTE), InteractionTask.class);
            if (!parent.id.equals(existing.request().linkage().parentRunId())
                    || !existing.request().scope().equals(old.request().scope())
                    || !existing.request().source().equals(old.request().source())
                    || !task.taskId().equals(previous.taskId()) || task.revision() != previous.revision() + 1
                    || !task.goal().equals(goal)) throw new SecurityException("replacement interaction identity mismatch");
            InteractionRequestGuard.validate(engine.runs, engine.json, existing.request());
            publishAmendedWait(parent, previousWait, command, task, engine.handle(existing.snapshot().id()));
            if (engine.get(existing.snapshot().id()).state() == RunState.PAUSED) engine.resume(existing.snapshot().id(),
                    new ResumeCommand("input.continue", JsonNodeFactory.instance.objectNode()));
            return;
        }
        TaskContractV3 contract = frozenAmendment(parent, goal, previous.revision() + 1);
        if (contract == null) contract = engine.taskHarness.amendInteraction(parent.id, parent.request, goal,
                previous.revision() + 1, parent.control, parent.sink::tryEmitNext);
        InteractionTask task = new InteractionTask(1, previous.taskId(), previous.revision() + 1, previous.mode(),
                goal, previous.necessaryData(), previous.constraints(), contract.criteria().stream().map(TaskCriterionV3::id).toList());
        var attributes = new java.util.LinkedHashMap<>(old.request().attributes());
        attributes.put(InteractionExecutionPolicy.TASK_ATTRIBUTE, engine.json.valueToTree(task));
        attributes.put("framework.taskContract", engine.json.valueToTree(contract));
        var remaining = java.time.Duration.between(engine.clock.instant(), parent.control.deadline());
        RunBudget budget = engine.usage.remainingBudget(parent.id).restrictWith(old.request().budget())
                .restrictWith(new RunBudget(remaining, Long.MAX_VALUE, Long.MAX_VALUE, Integer.MAX_VALUE,
                        RunBudget.UNBOUNDED.maxCost()));
        RunRequest replacement = new RunRequest(old.request().agent(), old.request().profile(), old.request().source(),
                old.request().scope(), List.of(InputBlock.text(engine.json.valueToTree(task).toString())), old.request().linkage(),
                old.request().permissionCeiling(), budget, idempotency, attributes);
        RunHandle child = engine.start(replacement);
        publishAmendedWait(parent, previousWait, command, task, child);
    }

    private TaskContractV3 frozenAmendment(ActiveEngineRun parent, String goal, long revision) {
        var latest = TaskResultEvaluator.latestContractV3(engine.runs.eventsAfter(parent.id, 0), engine.json).orElse(null);
        if (latest == null || !latest.reliable() || !latest.applicable()
                || !latest.originalRequest().endsWith("交互任务修订：" + goal)) return null;
        var criteria = latest.criteria().stream().filter(criterion -> criterion.capabilityId().startsWith("browser.")
                || criterion.capabilityId().startsWith("desktop.")).toList();
        if (criteria.isEmpty() || criteria.stream().anyMatch(criterion -> !criterion.id().startsWith("ir" + revision + ".")))
            throw new IllegalStateException("persisted amendment contract revision mismatch");
        return new TaskContractV3(3, goal, criteria, true, true, "host.interaction", List.of(), List.of(),
                latest.desktopObservationPolicy(), latest.intentStatus());
    }

    private void publishAmendedWait(ActiveEngineRun parent, JsonNode previousWait, InteractionControlCommand command,
            InteractionTask task, RunHandle child) {
        parent.control.throwIfCancelled();
        ObjectNode wait = ((ObjectNode) previousWait).deepCopy().put("childRunId", child.id().value())
                .put("revision", task.revision()).put("afterSequence", 0);
        ObjectNode payload = JsonNodeFactory.instance.objectNode().put("revision", task.revision())
                .put("childRunId", child.id().value()).put("commandId", command.commandId());
        payload.set("output", wait);
        ObjectNode applied = JsonNodeFactory.instance.objectNode()
                .put("commandId", command.commandId()).put("revision", task.revision())
                .put("childRunId", child.id().value()).put("type", command.type().name());
        var updated = engine.appendTransitionBatch(parent.id, Set.of(RunState.RUNNING), RunState.WAITING_CHILD, List.of(
                engine.event(parent.request, "core.run.waiting_child", "framework.core", payload, null),
                engine.event(parent.request, "core.interaction.control_applied", "framework.core", applied, null)));
        if (updated.isEmpty()) { engine.cancel(child.id(), new CancelReason("PARENT_CANCELLED", parent.id.value())); return; }
        updated.get().forEach(parent.sink::tryEmitNext);
        engine.attachInteractionWait(parent, RunState.WAITING_CHILD, wait);
    }

    private void recordInteractionControlApplied(ActiveEngineRun parent, JsonNode command) {
        RunState state = engine.get(parent.id).state();
        engine.appendTransition(parent.id, Set.of(state), state,
                engine.event(parent.request, "core.interaction.control_applied", "framework.core", command, null), null, null)
                .ifPresent(parent.sink::tryEmitNext);
    }

}
