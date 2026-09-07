package com.javaclaw.client.cli;

import java.io.PrintStream;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.client.sdk.JavaClawClient;
import com.javaclaw.protocol.CoreRpcContracts;

/** 前台拥有 Turn 的观察期；保持 stdio 连接到终态，标准输出只交付最终权威结果。 */
final class CliTurnRunner {
    private final JavaClawClient client;
    private final PrintStream output;
    private final Clock clock;
    private final Duration pollInterval;
    private final Duration cancellationTimeout;

    CliTurnRunner(JavaClawClient client, PrintStream output) {
        this(client, output, Clock.systemUTC(), Duration.ofMillis(200), Duration.ofSeconds(2));
    }

    CliTurnRunner(
            JavaClawClient client,
            PrintStream output,
            Clock clock,
            Duration pollInterval,
            Duration cancellationTimeout) {
        this.client = client;
        this.output = output;
        this.clock = clock;
        this.pollInterval = pollInterval;
        this.cancellationTimeout = cancellationTimeout;
    }

    Result run(CliTurnRequest request, CliTerminal terminal) {
        CoreRpcContracts.TurnStartResult started = client.turns().start(request.payload(), request.options());
        AgentTurn turn = started.turn();
        output.println("Turn " + turn.id() + " 已接受，等待执行结果。");
        CliTerminal selected = new CliTerminal(terminal.interactive() && !request.nonInteractive(), terminal.reader());
        CliTurnCancellation cancellation = new CliTurnCancellation(client, turn.id(), output, cancellationTimeout);
        AtomicBoolean finished = new AtomicBoolean();
        Thread hook = new Thread(
                () -> {
                    if (!finished.get()) {
                        cancellation.cancel("用户中断 CLI 进程");
                    }
                },
                "javaclaw-cli-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);
        try (CliInteractions interactions = new CliInteractions(client, turn.id(), output, clock, selected)) {
            return observe(turn, interactions, cancellation);
        } finally {
            finished.set(true);
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException shuttingDown) {
                // JVM 已开始退出，现有 hook 按同一取消工作完成有界收尾。
            }
        }
    }

    private Result observe(AgentTurn initial, CliInteractions interactions, CliTurnCancellation cancellation) {
        AgentTurn turn = initial;
        TurnStatus displayed = null;
        CliTurnMessages messages = new CliTurnMessages(client, output);
        try {
            while (true) {
                turn = client.turns().read(turn.id());
                if (turn.status() != displayed) {
                    output.println("Turn 状态：" + turn.status());
                    displayed = turn.status();
                }
                messages.read(turn);
                if (terminal(turn)) {
                    cancellation.confirm(turn);
                    turn.errorCode().ifPresent(code -> output.println("Turn 失败代码：" + code));
                    return new Result(Optional.of(turn), exitCode(turn));
                }
                Optional<CliInteractions.Stop> stop = interactions.observe();
                if (stop.isPresent() && stop.orElseThrow().code() != 0) {
                    var request = stop.orElseThrow();
                    return new Result(cancellation.cancel(request.reason()), request.code());
                }
                Thread.sleep(pollInterval.toMillis());
            }
        } catch (InterruptedException interrupted) {
            // 先清除线程中断以完成有界取消，再将中断语义交还调用者。
            Result result = new Result(cancellation.cancel("用户中断 CLI 等待"), 130);
            Thread.currentThread().interrupt();
            return result;
        } catch (RuntimeException failure) {
            return observationFailure(turn, cancellation, failure);
        }
    }

    private Result observationFailure(AgentTurn turn, CliTurnCancellation cancellation, RuntimeException failure) {
        output.println("Turn " + turn.id() + " 观察失败：" + failure.getMessage());
        if (terminal(turn)) {
            cancellation.confirm(turn);
            output.println("Turn 已确认终态，但最终消息读取未完成。");
            return new Result(Optional.of(turn), 1);
        }
        output.println("Turn " + turn.id() + " 最终状态尚未确认，尝试有界取消。");
        boolean interrupted = Thread.interrupted();
        Result result = new Result(cancellation.cancel("CLI 观察失败，停止前台执行"), interrupted ? 130 : 1);
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        return result;
    }

    static boolean terminal(AgentTurn turn) {
        return turn.status() == TurnStatus.COMPLETED
                || turn.status() == TurnStatus.FAILED
                || turn.status() == TurnStatus.CANCELLED;
    }

    private static int exitCode(AgentTurn turn) {
        return switch (turn.status()) {
            case COMPLETED -> 0;
            case CANCELLED -> 130;
            default -> 1;
        };
    }

    /**
     * 前台观察的交付结果；未确认终态时不构造最终 Turn JSON。
     *
     * @param turn 已确认的权威终态；容器不可空，取消结果未知时为空
     * @param exitCode 无单位的进程退出码：成功 0、失败 1、缺少必需输入 2、用户取消 130
     */
    record Result(Optional<AgentTurn> turn, int exitCode) {}
}
