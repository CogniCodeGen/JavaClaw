package com.javaclaw.workflow;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.api.conversation.ConversationCallbacks;
import com.javaclaw.api.conversation.ConversationEvent;
import com.javaclaw.api.conversation.ConversationOutcome;
import com.javaclaw.framework.api.TaskOutcome;
import com.javaclaw.framework.api.TaskResult;
import com.javaclaw.workflow.model.GraphState;
import com.javaclaw.workflow.model.NodeDefinition;
import com.javaclaw.workflow.model.NodeType;
import com.javaclaw.workflow.model.ResumeSafety;
import com.javaclaw.workflow.model.RetryPolicy;
import com.javaclaw.workflow.runtime.CancellationToken;
import com.javaclaw.workflow.runtime.GraphCancelledException;
import com.javaclaw.workflow.runtime.GraphListener;
import com.javaclaw.workflow.runtime.NodeExecutionContext;
import com.javaclaw.workflow.runtime.NodeResult;
import com.javaclaw.workflow.service.SystemPipelineAwaiter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemPipelineAwaiterTest {

    @Test
    void checkpointsNestedTaskResultWithoutEquatingNodeExitToTaskSuccess() throws Exception {
        TaskResult partial = new TaskResult(TaskOutcome.PARTIAL,
                List.of("week view observed"), "week view not visible", List.of("receipt:open"));
        NodeResult result = SystemPipelineAwaiter.await(context(new CancellationToken()),
                callbacks -> {
                    callbacks.onEvent(new ConversationEvent.Reply("日历已打开"));
                    callbacks.onTerminal(ConversationOutcome.completed(partial));
                }, silentCallbacks(), () -> { });

        GraphState state = new GraphState().apply(result.patch());
        assertEquals(partial, com.javaclaw.framework.api.TaskResultJson.decode(
                state.get(SystemPipelineAwaiter.TASK_RESULT_KEY)));
        assertEquals("日历已打开", result.output());
    }

    @Test
    void 启动期间取消会命中建立后的真实订阅() throws Exception {
        CancellationToken cancellation = new CancellationToken();
        CountDownLatch starterEntered = new CountDownLatch(1);
        CountDownLatch releaseStarter = new CountDownLatch(1);
        AtomicBoolean subscriptionActive = new AtomicBoolean();
        AtomicBoolean activeSubscriptionCancelled = new AtomicBoolean();
        Thread canceller = Thread.ofVirtual().start(() -> {
            await(starterEntered);
            cancellation.cancel();
            releaseStarter.countDown();
        });

        assertThrows(GraphCancelledException.class, () -> SystemPipelineAwaiter.await(
                context(cancellation),
                callbacks -> {
                    starterEntered.countDown();
                    await(releaseStarter);
                    subscriptionActive.set(true);
                },
                silentCallbacks(),
                () -> {
                    if (subscriptionActive.get()) activeSubscriptionCancelled.set(true);
                }));

        canceller.join();
        assertTrue(activeSubscriptionCancelled.get(),
                "取消钩子必须在 starter 建立订阅后再次命中真实订阅");
    }

    @Test
    void 已取消令牌不会启动底层管线() {
        CancellationToken cancellation = new CancellationToken();
        cancellation.cancel();
        AtomicBoolean started = new AtomicBoolean();

        assertThrows(GraphCancelledException.class, () -> SystemPipelineAwaiter.await(
                context(cancellation),
                callbacks -> started.set(true),
                silentCallbacks(),
                () -> { }));

        assertFalse(started.get());
    }

    private static NodeExecutionContext context(CancellationToken cancellation) {
        NodeDefinition node = new NodeDefinition(
                "stage", NodeType.SYSTEM, "system.pipeline", "阶段",
                JsonNodeFactory.instance.objectNode().put("stageId", "stage"),
                0, 0, RetryPolicy.NONE, ResumeSafety.CONFIRM_RETRY);
        return new NodeExecutionContext("run", "thread", node, new GraphState(),
                cancellation, GraphListener.NOOP, com.javaclaw.workflow.runtime.WorkflowExecutionServices.EMPTY);
    }

    private static ConversationCallbacks silentCallbacks() {
        return new ConversationCallbacks() {
            @Override public void onEvent(ConversationEvent event) { }
            @Override public void onTerminal(ConversationOutcome outcome) { }
        };
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(3, TimeUnit.SECONDS)) {
                throw new AssertionError("等待测试同步点超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("等待测试同步点被中断", e);
        }
    }
}
