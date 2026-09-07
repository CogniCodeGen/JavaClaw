package com.javaclaw.client.cli;

import java.io.PrintStream;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.TurnId;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.client.RemoteRpcException;
import com.javaclaw.client.sdk.JavaClawClient;
import com.javaclaw.protocol.ProtocolErrorCode;

/**
 * 用户取消与 JVM 退出共用一次有界取消工作；仅服务端终态能证明取消已落盘。
 *
 * <p>SDK 没有 RPC 超时，取消在独立虚拟线程运行，调用者最多等待指定时限。断线或超时不能被显示为已取消。
 */
final class CliTurnCancellation {
    private final JavaClawClient client;
    private final TurnId turnId;
    private final PrintStream output;
    private final Duration timeout;
    private final AtomicReference<CompletableFuture<AgentTurn>> operation = new AtomicReference<>();
    private final AtomicReference<AgentTurn> confirmed = new AtomicReference<>();

    CliTurnCancellation(JavaClawClient client, TurnId turnId, PrintStream output, Duration timeout) {
        this.client = client;
        this.turnId = turnId;
        this.output = output;
        this.timeout = timeout;
    }

    Optional<AgentTurn> cancel(String reason) {
        AgentTurn terminal = confirmed.get();
        if (terminal != null) {
            return Optional.of(terminal);
        }
        CompletableFuture<AgentTurn> candidate = new CompletableFuture<>();
        if (operation.compareAndSet(null, candidate)) {
            Thread.ofVirtual().name("javaclaw-cli-cancel").start(() -> execute(candidate, reason));
        }
        try {
            AgentTurn turn = operation.get().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            output.println("Turn " + turnId + " 已进入终态：" + turn.status());
            return Optional.of(turn);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            output.println("Turn " + turnId + " 取消结果未确认：等待被中断。");
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
            output.println("Turn " + turnId + " 取消结果未确认：服务端未在期限内确认终态。");
        }
        return Optional.empty();
    }

    void confirm(AgentTurn turn) {
        if (!CliTurnRunner.terminal(turn)) {
            throw new IllegalArgumentException("只有权威终态可以结束取消等待");
        }
        confirmed.set(turn);
        CompletableFuture<AgentTurn> pending = operation.get();
        if (pending != null) {
            pending.complete(turn);
        }
    }

    private void execute(CompletableFuture<AgentTurn> result, String reason) {
        try {
            AgentTurn turn = request(reason);
            long deadline = System.nanoTime() + timeout.toNanos();
            while (!CliTurnRunner.terminal(turn)) {
                if (System.nanoTime() >= deadline) {
                    throw new IllegalStateException("取消终态等待超时");
                }
                Thread.sleep(25);
                turn = client.turns().read(turnId);
            }
            confirm(turn);
            result.complete(turn);
        } catch (Exception failure) {
            AgentTurn terminal = confirmed.get();
            if (terminal != null) {
                result.complete(terminal);
            } else {
                result.completeExceptionally(failure);
            }
        }
    }

    private AgentTurn request(String reason) {
        for (int attempt = 0; attempt < 3; attempt++) {
            AgentTurn current = client.turns().read(turnId);
            if (CliTurnRunner.terminal(current)) {
                return current;
            }
            try {
                return client.turns().cancel(turnId, reason, CommandOptions.create(current.revision()));
            } catch (RemoteRpcException failure) {
                if (failure.code() != ProtocolErrorCode.REVISION_CONFLICT) {
                    throw failure;
                }
            }
        }
        throw new IllegalStateException("取消请求 revision 持续变化");
    }
}
