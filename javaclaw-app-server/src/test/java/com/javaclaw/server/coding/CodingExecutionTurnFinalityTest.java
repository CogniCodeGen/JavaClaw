package com.javaclaw.server.coding;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts;
import com.javaclaw.builtin.contracts.CodingResults;
import com.javaclaw.server.persistence.CodingTerminalRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Turn 终态不等于已知进程退出；优先保留真实回执，明确未知意图绝不能被覆盖。 */
class CodingExecutionTurnFinalityTest {
    @TempDir
    Path directory;

    @Test
    void Turn终态后迟到取消回执显示真实退出但未观察退出的命令显示未知() throws Exception {
        try (var fixture = new CodingStreamingFixture(directory)) {
            var cancelled = fixture.start("cancelled", "command_run", 100);
            fixture.start("unconfirmed", "command_run", 100);
            cancel(fixture);
            fixture.streams.complete(cancelled, "CANCELLED", -1);
            var rows = list(fixture);
            assertEquals("CANCELLED", find(rows, "cancelled").state());
            assertEquals(Optional.of(-1), find(rows, "cancelled").exitCode());
            assertEquals("UNKNOWN_OUTCOME", find(rows, "unconfirmed").state());
            assertTrue(find(rows, "unconfirmed").exitCode().isEmpty());
        }
    }

    @Test
    void 活跃Turn的依赖后置证据阶段仍运行但Turn终态不隐藏已确认成功退出() throws Exception {
        try (var fixture = new CodingStreamingFixture(directory)) {
            var owner = fixture.start("preparing", "dependencies_prepare", 100);
            fixture.streams.complete(owner, "COMPLETED", 0);
            assertEquals("RUNNING", find(list(fixture), "preparing").state());
            cancel(fixture);
            assertEquals("COMPLETED", find(list(fixture), "preparing").state());
            assertEquals(Optional.of(0), find(list(fixture), "preparing").exitCode());
        }
    }

    @Test
    void PTY启动或运行快照在Turn终态后不继续显示运行且已知失败退出保持真实值() throws Exception {
        try (var fixture = new CodingStreamingFixture(directory)) {
            var records = terminals(fixture);
            for (String id : List.of("starting", "running", "finished")) {
                terminal(fixture, records, id);
            }
            records.state("running", "RUNNING", Optional.empty());
            records.state("finished", "FAILED", Optional.of(17));
            cancel(fixture);
            var rows = list(fixture);
            assertEquals("UNKNOWN_OUTCOME", find(rows, "starting").state());
            assertEquals("UNKNOWN_OUTCOME", find(rows, "running").state());
            assertEquals("FAILED", find(rows, "finished").state());
            assertEquals(Optional.of(17), find(rows, "finished").exitCode());
        }
    }

    @Test
    void 显式未知操作优先于已经观察到的batch或PTY退出码() throws Exception {
        try (var fixture = new CodingStreamingFixture(directory)) {
            var stream = fixture.start("batch", "command_run", 100);
            fixture.streams.complete(stream, "COMPLETED", 0);
            fixture.operations.unknown("batch");
            var records = terminals(fixture);
            terminal(fixture, records, "terminal");
            fixture.operations.start("terminal");
            records.state("terminal", "FAILED", Optional.of(17));
            fixture.operations.unknown("terminal");
            cancel(fixture);
            for (var row : list(fixture)) {
                assertEquals("UNKNOWN_OUTCOME", row.state());
                assertTrue(row.exitCode().isEmpty());
            }
        }
    }

    private static void cancel(CodingStreamingFixture fixture) {
        var journal = new com.javaclaw.server.persistence.H2TurnJournal(
                fixture.base.database,
                com.javaclaw.protocol.CoreItemCodecs.createRegistry(fixture.base.json),
                fixture.base.json,
                fixture.base.clock);
        journal.transition(fixture.base.turn.id(), TurnStatus.RUNNING, TurnStatus.CANCELLED, Optional.empty());
    }

    private static CodingTerminalRepository terminals(CodingStreamingFixture fixture) {
        return new CodingTerminalRepository(
                fixture.base.database, fixture.attachments, fixture.base.json, fixture.base.clock);
    }

    private static void terminal(CodingStreamingFixture fixture, CodingTerminalRepository records, String id) {
        fixture.prepare(id, "terminal_open", fixture.base.turn.id());
        records.create(
                fixture.operations
                        .find(fixture.base.workspace.id(), id)
                        .orElseThrow()
                        .intent(),
                new CorePayloads.Command(id, List.of("java", "Main"), fixture.base.root, Optional.empty()));
    }

    private static List<CodingResults.ExecutionSummary> list(CodingStreamingFixture fixture) throws Exception {
        return fixture.base
                .json
                .decode(
                        fixture.query(
                                "execution/list",
                                new CodingEnvironmentContracts.Empty(),
                                Optional.of(fixture.base.turn.threadId()),
                                Optional.of(fixture.base.turn.id())),
                        CodingResults.ExecutionList.class)
                .executions();
    }

    private static CodingResults.ExecutionSummary find(List<CodingResults.ExecutionSummary> rows, String id) {
        return rows.stream()
                .filter(row -> row.operationId().equals(id))
                .findFirst()
                .orElseThrow();
    }
}
