package com.javaclaw.client.cli;

import java.io.PrintStream;
import java.util.Optional;
import java.util.function.LongSupplier;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.TurnId;
import com.javaclaw.client.extension.CodingExecutionPoller;
import com.javaclaw.client.sdk.JavaClawClient;

/** CLI 前台的只读执行输出观察；不持有 stdin 权限，每秒最多一次目录查询。 */
final class CliCodingOutput {
    private final JavaClawClient client;
    private final PrintStream output;
    private final LongSupplier nanos;
    private CodingExecutionPoller poller;
    private TurnId owner;
    private long nextPoll;
    private boolean reportedFailure;

    CliCodingOutput(JavaClawClient client, PrintStream output) {
        this(client, output, System::nanoTime);
    }

    CliCodingOutput(JavaClawClient client, PrintStream output, LongSupplier nanos) {
        this.client = client;
        this.output = output;
        this.nanos = nanos;
    }

    void read(AgentTurn turn, boolean terminal) {
        long now = nanos.getAsLong();
        if (!terminal && now < nextPoll && turn.id().equals(owner)) {
            return;
        }
        nextPoll = now + 1_000_000_000L;
        try {
            if (!turn.id().equals(owner)) {
                var thread = client.threads().read(turn.threadId());
                poller = new CodingExecutionPoller(new CodingExecutionPoller.Scope(
                        thread.workspaceId(), Optional.of(thread.id()), Optional.of(turn.id())));
                owner = turn.id();
                reportedFailure = false;
            }
            CodingExecutionPoller.Poll result = poller.poll(client.builtins().coding());
            print(result);
            if (terminal) {
                drain(result);
            }
        } catch (RuntimeException failure) {
            if (!reportedFailure) {
                output.println("执行输出当前不可读取；会话观察与 Turn 取消仍可用。");
                reportedFailure = true;
            }
        }
    }

    private void drain(CodingExecutionPoller.Poll first) {
        CodingExecutionPoller.Poll result = first;
        // 终态最多再排空三轮，最多约 1 MiB；不无限追页，也不因日志续期 Turn。
        for (int pass = 0; result.pending() && pass < 3; pass++) {
            long previous = consumed(result);
            result = poller.poll(client.builtins().coding());
            print(result);
            if (consumed(result) == previous) {
                break;
            }
        }
        if (result.pending()) {
            output.println("[仍有输出未显示；可通过 Coding SDK 的字节游标继续读取]");
        }
    }

    private void print(CodingExecutionPoller.Poll result) {
        for (var snapshot : result.snapshots()) {
            if (snapshot.changed()) {
                output.println(snapshot.fact().title());
                if (!snapshot.appendedText().isEmpty()) {
                    output.println(snapshot.appendedText());
                }
                snapshot.summary().exitCode().ifPresent(code -> output.println("退出码：" + code));
            }
        }
    }

    private static long consumed(CodingExecutionPoller.Poll result) {
        return result.snapshots().stream()
                .mapToLong(CodingExecutionPoller.Snapshot::nextOffsetBytes)
                .sum();
    }
}
