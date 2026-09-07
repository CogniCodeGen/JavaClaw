package com.javaclaw.client.extension;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.ThreadId;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.CodingResults;

/** 单个不可变作用域的有界输出游标；CLI 与 Desktop 共用，禁止在 JavaFX 线程进行网络调用。 每次至多查询一次目录和 16 页输出；同实例串行调用，作用域切换须新建实例并丢弃旧响应。 */
public final class CodingExecutionPoller {
    private static final int PAGE_BYTES = 16 * 1024;
    private static final int MAX_PAGES = 16;
    private static final int TAIL_CHARACTERS = 16 * 1024;
    private final Scope scope;
    private final Map<String, Reading> readings = new LinkedHashMap<>();

    /**
     * 固定查询过滤，不把过滤标识当作服务端权限。
     *
     * @param scope 当前 Workspace 及可选 Thread、Turn
     */
    public CodingExecutionPoller(Scope scope) {
        this.scope = Objects.requireNonNull(scope, "scope");
    }

    /**
     * 拉取一次有界增量；末页空读不推进游标，异常不会伪造成功或扩展权限。
     *
     * @param client 已初始化的 Coding SDK
     * @return 当前最近执行的文本尾部和本次追加文本
     */
    public synchronized Poll poll(CodingExtensionClient client) {
        var executions = client.executions(scope.workspaceId(), scope.threadId(), scope.turnId())
                .executions();
        List<Snapshot> result = new ArrayList<>();
        int pages = 0;
        for (var summary : executions) {
            requireScope(summary);
            var reading = readings.computeIfAbsent(summary.operationId(), ignored -> new Reading(summary));
            reading.requireIdentity(summary);
            String append = "";
            boolean stateChanged = !reading.summary.equals(summary);
            if (pages < MAX_PAGES && (summary.outputBytes() > reading.cursor || !reading.observed)) {
                CodingResults.Output output = output(client, summary, reading.cursor);
                if (output != null) {
                    append = reading.append(output);
                    pages++;
                }
            }
            boolean changed = !reading.observed || stateChanged || !append.isEmpty();
            reading.observed = true;
            reading.summary = summary;
            result.add(reading.snapshot(append, changed));
        }
        var retained = executions.stream()
                .map(CodingResults.ExecutionSummary::operationId)
                .toList();
        readings.keySet().retainAll(retained);
        return new Poll(result, result.stream().anyMatch(Snapshot::pending));
    }

    private void requireScope(CodingResults.ExecutionSummary summary) {
        if (scope.turnId().isPresent() && !scope.turnId().orElseThrow().equals(summary.turnId())) {
            throw new IllegalStateException("执行目录返回了其他 Turn 的资源");
        }
    }

    private CodingResults.Output output(
            CodingExtensionClient client, CodingResults.ExecutionSummary summary, long offset) {
        var request = new CodingResults.OutputRead(summary.operationId(), offset, PAGE_BYTES);
        return switch (summary.operation()) {
            case "dependencies_prepare" -> client.preparationOutput(scope.workspaceId(), request);
            case "command_run" -> client.commandOutput(scope.workspaceId(), request);
            case "terminal_open" ->
                client.terminalOutput(scope.workspaceId(), request).output();
            default -> null;
        };
    }

    /**
     * 不可变读取作用域；所有过滤均由服务端重新验证。
     *
     * @param workspaceId 必需的 Workspace
     * @param threadId 可选 Thread 过滤
     * @param turnId 可选 Turn 过滤
     */
    public record Scope(WorkspaceId workspaceId, Optional<ThreadId> threadId, Optional<TurnId> turnId) {
        /** 拒绝空容器，保留明确的空过滤。 */
        public Scope {
            Objects.requireNonNull(workspaceId, "workspaceId");
            threadId = Objects.requireNonNull(threadId, "threadId");
            turnId = Objects.requireNonNull(turnId, "turnId");
        }
    }

    /**
     * 本次目录与输出观察。
     *
     * @param snapshots 最多 100 个执行的有界文本快照
     * @param pending 是否仍有运行中的执行或未读已保留字节
     */
    public record Poll(List<Snapshot> snapshots, boolean pending) {
        /** 固定跨线程传递的结果。 */
        public Poll {
            snapshots = List.copyOf(snapshots);
        }
    }

    /**
     * 一个执行的显示数据；它不是 Core Item 或可提交事实。
     *
     * @param summary 权威执行摘要
     * @param fact 最近最多 16384 字符的安全文本尾部
     * @param appendedText 此次新读取的安全文本，用于 CLI 顺序追加
     * @param changed 是否出现新输出、状态或首次观察
     * @param nextOffsetBytes 已确认消费的原始字节游标
     * @param pending 是否运行中或尚有未读输出
     */
    public record Snapshot(
            CodingResults.ExecutionSummary summary,
            CodingTranscriptFormatter.Fact fact,
            String appendedText,
            boolean changed,
            long nextOffsetBytes,
            boolean pending) {}

    private static final class Reading {
        private CodingResults.ExecutionSummary summary;
        private long cursor;
        private String tail = "";
        private boolean limited;
        private boolean sourceTruncated;
        private boolean observed;

        private Reading(CodingResults.ExecutionSummary summary) {
            this.summary = summary;
        }

        private void requireIdentity(CodingResults.ExecutionSummary next) {
            if (!summary.turnId().equals(next.turnId())
                    || !summary.operation().equals(next.operation())
                    || next.outputBytes() < cursor) {
                throw new IllegalStateException("执行资源身份或输出游标不一致");
            }
        }

        private String append(CodingResults.Output output) {
            long next = output.nextOffsetBytes();
            if (next < cursor
                    || next > cursor + PAGE_BYTES
                    || output.stdout().length() + output.stderr().length() > PAGE_BYTES * 2
                    || next == cursor
                            && (!output.stdout().isEmpty() || !output.stderr().isEmpty())) {
                throw new IllegalStateException("执行输出页超过边界或游标未推进");
            }
            String raw = output.stdout() + (output.stderr().isEmpty() ? "" : "\nstderr:\n" + output.stderr());
            String safe = new CodingTranscriptFormatter.Fact("", raw).body();
            if (output.truncated() && !sourceTruncated) {
                safe += "\n[输出已达到本页或保留边界]\n";
            }
            tail += safe;
            if (tail.length() > TAIL_CHARACTERS) {
                int start = tail.length() - TAIL_CHARACTERS;
                if (Character.isLowSurrogate(tail.charAt(start))) {
                    start++;
                }
                tail = tail.substring(start);
                limited = true;
            }
            cursor = next;
            sourceTruncated |= output.truncated();
            return safe;
        }

        private Snapshot snapshot(String append, boolean changed) {
            String title =
                    switch (summary.operation()) {
                        case "dependencies_prepare" -> "依赖准备";
                        case "terminal_open" -> "终端";
                        case "command_run" -> "命令";
                        default -> "执行 · " + summary.operation();
                    };
            var fact = new CodingTranscriptFormatter.Fact(
                    title + " · " + summary.state() + " · " + summary.operationId(),
                    (limited ? "[仅显示最近输出；完整保留内容可通过 SDK 分页读取]\n" : "")
                            + tail
                            + summary.exitCode().map(code -> "\n退出码：" + code).orElse(""));
            return new Snapshot(
                    summary,
                    fact,
                    append,
                    changed,
                    cursor,
                    summary.state().equals("RUNNING") || summary.outputBytes() > cursor);
        }
    }
}
