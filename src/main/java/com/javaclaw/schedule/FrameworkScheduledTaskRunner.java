package com.javaclaw.schedule;

import com.javaclaw.agent.ToolCallOrigin;
import com.javaclaw.api.conversation.CancellationReason;
import com.javaclaw.api.conversation.ConversationCallbacks;
import com.javaclaw.api.conversation.ConversationHandle;
import com.javaclaw.api.conversation.ConversationOutcome;
import com.javaclaw.api.conversation.TerminalCallbackGuard;
import com.javaclaw.application.agent.AgentConversationRunner;
import com.javaclaw.application.agent.RunRequestFactory;
import com.javaclaw.framework.api.AgentClient;
import com.javaclaw.framework.api.InvocationSource;
import com.javaclaw.framework.api.PermissionSet;
import com.javaclaw.runtime.WorkspaceContext;

import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Schedule application adapter. Every trigger submits an ordinary {@code RunRequest} with the
 * {@code schedule} profile; there is no schedule-specific Agent runtime or shared active run.
 */
public final class FrameworkScheduledTaskRunner implements ScheduledTaskRunner {
    private static final long RUN_TIMEOUT_MINUTES = 12;

    private final AgentConversationRunner runs;
    private final RunRequestFactory requests;
    private volatile boolean closed;

    public FrameworkScheduledTaskRunner(
            AgentClient agents,
            WorkspaceContext workspace,
            Executor executor) {
        this.runs = new AgentConversationRunner(agents, executor);
        this.requests = new RunRequestFactory(workspace);
    }

    @Override
    public void run(
            ScheduledRunControl control,
            ToolCallOrigin origin,
            String prompt,
            ConversationCallbacks callbacks) {
        Objects.requireNonNull(control, "control");
        TerminalCallbackGuard terminal = new TerminalCallbackGuard(callbacks);
        CountDownLatch done = new CountDownLatch(1);
        control.attachWorker(Thread.currentThread());
        ConversationHandle handle = null;
        try {
            if (closed || control.isCancelled()) {
                terminal.onTerminal(ConversationOutcome.cancelled(
                        closed ? CancellationReason.RUNTIME_REBUILD : control.cancellationReason()));
                return;
            }
            ToolCallOrigin effectiveOrigin = origin == null ? ToolCallOrigin.SCHEDULED : origin;
            handle = runs.start(requests.text(prompt, control.runId(), "schedule",
                            InvocationSource.schedule(control.taskId()), PermissionSet.UNRESTRICTED,
                            null), effectiveOrigin, new ConversationCallbacks() {
                @Override
                public void onEvent(com.javaclaw.api.conversation.ConversationEvent event) {
                    if (!control.isCancelled()) terminal.onEvent(event);
                }

                @Override
                public void onTerminal(ConversationOutcome outcome) {
                    terminal.onTerminal(outcome);
                    done.countDown();
                }
            });
            if (!done.await(RUN_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                handle.cancel(CancellationReason.RUNTIME_REBUILD);
                terminal.onTerminal(ConversationOutcome.failed(new TimeoutException(
                        "定时任务执行超过 " + RUN_TIMEOUT_MINUTES + " 分钟，已中断")));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (handle != null) handle.cancel(control.cancellationReason());
            terminal.onTerminal(ConversationOutcome.cancelled(control.cancellationReason()));
        } catch (RuntimeException failure) {
            terminal.onTerminal(ConversationOutcome.failed(failure));
        } finally {
            control.detachWorker();
        }
    }

    @Override
    public void shutdown() {
        closed = true;
        runs.close();
    }
}
