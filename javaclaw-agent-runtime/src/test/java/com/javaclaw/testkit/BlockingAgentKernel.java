package com.javaclaw.testkit;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.javaclaw.agent.kernel.AgentKernel;
import com.javaclaw.agent.runtime.ItemSink;
import com.javaclaw.agent.runtime.TurnExecutionContext;
import com.javaclaw.core.api.ThreadItem;

/** Controllable kernel used to verify single-turn, steer and interrupt semantics. */
public final class BlockingAgentKernel implements AgentKernel {
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    @Override
    public void execute(TurnExecutionContext context, ItemSink sink) throws Exception {
        entered.countDown();
        while (!release.await(25, TimeUnit.MILLISECONDS)) {
            context.throwIfInterrupted();
        }
        context.throwIfInterrupted();
        sink.append(new ThreadItem.AgentMessage("released"));
    }

    public boolean awaitEntered(long timeout, TimeUnit unit) throws InterruptedException {
        return entered.await(timeout, unit);
    }

    public void release() {
        release.countDown();
    }
}
