package com.javaclaw.framework.core;


import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.api.*;
import reactor.core.publisher.Sinks;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** 每次运行独立持有执行、审批与等待状态；不推进持久化 RunState。 */
final class ActiveEngineRun {
    final RunId id;
    final RunRequest request;
    final ExecutionPlan plan;
    final RunControl control;
    final Sinks.Many<RunEventEnvelope> sink = Sinks.many().replay().all();
    final CompletableFuture<RunOutcome> completion = new CompletableFuture<>();
    final CompletableFuture<Void> physicallySettled = new CompletableFuture<>();
    volatile CompletableFuture<Void> ready = new CompletableFuture<>();
    final Object launchLock = new Object();
    final AtomicBoolean executing = new AtomicBoolean();
    final AtomicBoolean terminated = new AtomicBoolean();
    final AtomicBoolean cancelling = new AtomicBoolean();
    final AtomicBoolean detached = new AtomicBoolean();
    final AtomicBoolean cleaned = new AtomicBoolean();
    final AtomicBoolean managedAttached = new AtomicBoolean();
    volatile ToolApprovalChallenge pendingApproval;
    volatile ApprovedToolInvocation pendingApprovedInvocation;
    AgentEngine.PendingLaunch pendingLaunch;
    volatile JsonNode interactionWait;
    volatile reactor.core.Disposable interactionSubscription;
    volatile CompletableFuture<JsonNode> interactionEventAwait;
    volatile com.javaclaw.platform.execution.TriggerHandle waitRenewal;
    volatile com.javaclaw.platform.execution.TriggerHandle waitDeadline;

    ActiveEngineRun(RunId id, RunRequest request, ExecutionPlan plan, RunControl control) {
        this.id = id;
        this.request = request;
        this.plan = plan;
        this.control = control;
    }
}

