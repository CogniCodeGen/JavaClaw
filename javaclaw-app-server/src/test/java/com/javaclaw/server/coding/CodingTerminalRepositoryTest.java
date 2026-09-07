package com.javaclaw.server.coding;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.protocol.CoreItemCodecs;
import com.javaclaw.server.persistence.AttachmentService;
import com.javaclaw.server.persistence.CodingOperationRepository;
import com.javaclaw.server.persistence.CodingTerminalRepository;
import com.javaclaw.server.persistence.CodingTerminalRepository.Snapshot;
import com.javaclaw.server.persistence.H2Transactions;
import com.javaclaw.server.persistence.H2TurnJournal;
import com.javaclaw.server.persistence.PersistenceException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodingTerminalRepositoryTest {
    private static final String FIRST_DIGEST = "a".repeat(64);
    private static final String SECOND_DIGEST = "b".repeat(64);

    @TempDir
    Path temporary;

    @Test
    void 状态只接受运行与真实终态且重复终态必须连退出码也一致() throws Exception {
        try (var fixture = new CodingTestFixture(temporary)) {
            var terminals = repository(fixture);
            var owner = create(fixture, terminals, "starting");
            assertEquals("STARTING", owner.state());
            assertEquals(Optional.empty(), owner.exitCode());
            assertThrows(PersistenceException.class, () -> create(fixture, terminals, "starting"));
            assertThrows(SecurityException.class, () -> terminals.read(fixture.workspace.id(), "absent"));
            assertThrows(SecurityException.class, () -> terminals.state("absent", "RUNNING", Optional.empty()));
            for (String invalid : List.of("", "STARTING", "BROKEN", "running")) {
                assertThrows(
                        IllegalArgumentException.class, () -> terminals.state(owner.id(), invalid, Optional.empty()));
            }
            assertThrows(IllegalArgumentException.class, () -> terminals.state(owner.id(), "RUNNING", Optional.of(0)));
            terminals.state(owner.id(), "RUNNING", Optional.empty());
            terminals.state(owner.id(), "RUNNING", Optional.empty());
            assertEquals(
                    "RUNNING",
                    terminals.read(fixture.workspace.id(), owner.id()).state());
            for (String state : List.of("COMPLETED", "FAILED", "CANCELLED", "TIMED_OUT", "UNKNOWN_OUTCOME")) {
                var terminal = create(fixture, terminals, "final-" + state);
                terminals.state(terminal.id(), state, Optional.of(9));
                terminals.state(terminal.id(), state, Optional.of(9));
                assertThrows(PersistenceException.class, () -> terminals.state(terminal.id(), state, Optional.empty()));
                assertThrows(
                        PersistenceException.class, () -> terminals.state(terminal.id(), "RUNNING", Optional.empty()));
                assertEquals(
                        state,
                        repository(fixture)
                                .read(fixture.workspace.id(), terminal.id())
                                .state());
            }
        }
    }

    @Test
    void 原始输出跨块分页且旧快照不会读到后续追加内容() throws Exception {
        try (var fixture = new CodingTestFixture(temporary)) {
            var terminals = repository(fixture);
            var initial = create(fixture, terminals, "bytes");
            byte[] bytes = "A错B\0end".getBytes(StandardCharsets.UTF_8);
            assertEquals(2, terminals.append(initial, Arrays.copyOfRange(bytes, 0, 2), 100));
            var first = terminals.read(fixture.workspace.id(), initial.id());
            assertEquals(bytes.length, terminals.append(first, Arrays.copyOfRange(bytes, 2, bytes.length), 100));
            var all = repository(fixture).read(fixture.workspace.id(), initial.id());
            assertArrayEquals(new byte[0], terminals.output(initial, 0, 100));
            assertArrayEquals(Arrays.copyOfRange(bytes, 0, 2), terminals.output(first, 0, 100));
            assertArrayEquals(bytes, terminals.output(all, 0, 100));
            assertArrayEquals(Arrays.copyOfRange(bytes, 1, 5), terminals.output(all, 1, 4));
            assertArrayEquals(Arrays.copyOfRange(bytes, 4, bytes.length), terminals.output(all, 4, 100));
            assertArrayEquals(new byte[0], terminals.output(all, bytes.length, 1));
            assertThrows(IllegalArgumentException.class, () -> terminals.output(all, -1, 1));
            assertThrows(IllegalArgumentException.class, () -> terminals.output(all, bytes.length + 1, 1));
            assertThrows(IllegalArgumentException.class, () -> terminals.output(all, 0, 0));
            assertThrows(IllegalArgumentException.class, () -> terminals.output(all, 0, 1_048_577));
        }
    }

    @Test
    void 追加预算和并发游标冲突不能覆盖已经留存的输出() throws Exception {
        try (var fixture = new CodingTestFixture(temporary)) {
            var terminals = repository(fixture);
            var initial = create(fixture, terminals, "budget");
            assertThrows(IllegalArgumentException.class, () -> terminals.append(initial, new byte[0], 100));
            assertThrows(IllegalArgumentException.class, () -> terminals.append(initial, new byte[65_537], 100));
            assertThrows(IllegalArgumentException.class, () -> terminals.append(initial, new byte[] {1}, -1));
            assertEquals(3, terminals.append(initial, new byte[] {1, 2, 3}, 3));
            var retained = terminals.read(fixture.workspace.id(), initial.id());
            assertThrows(PersistenceException.class, () -> terminals.append(initial, new byte[] {9}, 100));
            assertThrows(PersistenceException.class, () -> terminals.append(retained, new byte[] {9}, 3));
            assertThrows(PersistenceException.class, () -> terminals.append(retained, new byte[] {9}, 2));
            assertArrayEquals(new byte[] {1, 2, 3}, terminals.output(retained, 0, 100));
            var concurrent = create(fixture, terminals, "concurrent");
            var ready = new CountDownLatch(2);
            var start = new CountDownLatch(1);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var first = executor.submit(() -> competingAppend(terminals, concurrent, (byte) 4, ready, start));
                var second = executor.submit(() -> competingAppend(terminals, concurrent, (byte) 5, ready, start));
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                start.countDown();
                assertEquals(1, first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS));
            }
            var actual = terminals.read(fixture.workspace.id(), concurrent.id());
            assertEquals(1, actual.outputBytes());
            byte winner = terminals.output(actual, 0, 1)[0];
            assertTrue(winner == 4 || winner == 5);
        }
    }

    @Test
    void 调用者构造的快照不能伪造工作区Turn和输出尾部() throws Exception {
        try (var fixture = new CodingTestFixture(temporary)) {
            var terminals = repository(fixture);
            var initial = create(fixture, terminals, "owner");
            terminals.state(initial.id(), "RUNNING", Optional.empty());
            assertThrows(SecurityException.class, () -> terminals.read(WorkspaceId.random(), initial.id()));
            for (Snapshot forged : List.of(
                    new Snapshot(initial.id(), initial.turnId(), WorkspaceId.random(), "RUNNING", Optional.empty(), 0),
                    new Snapshot(
                            initial.id(), TurnId.random(), initial.workspaceId(), "RUNNING", Optional.empty(), 0))) {
                assertThrows(SecurityException.class, () -> terminals.output(forged, 0, 1));
                assertThrows(SecurityException.class, () -> terminals.append(forged, new byte[] {1}, 10));
                assertThrows(SecurityException.class, () -> terminals.inputIntent(forged, 1, FIRST_DIGEST));
            }
            var ahead =
                    new Snapshot(initial.id(), initial.turnId(), initial.workspaceId(), "RUNNING", Optional.empty(), 4);
            assertThrows(SecurityException.class, () -> terminals.output(ahead, 0, 1));
            assertTrue(terminals.inputIntent(initial, 1, FIRST_DIGEST));
            assertEquals(0, terminals.read(fixture.workspace.id(), initial.id()).outputBytes());
        }
    }

    @Test
    void 输入只持久摘要且未知送达在重建后拒绝重放() throws Exception {
        try (var fixture = new CodingTestFixture(temporary)) {
            var terminals = repository(fixture);
            var owner = create(fixture, terminals, "input");
            assertThrows(SecurityException.class, () -> terminals.inputIntent(owner, 1, FIRST_DIGEST));
            terminals.state(owner.id(), "RUNNING", Optional.empty());
            assertThrows(IllegalArgumentException.class, () -> terminals.inputIntent(owner, 0, FIRST_DIGEST));
            for (String invalid : List.of("raw input secret", "A".repeat(64), "a".repeat(63))) {
                assertThrows(IllegalArgumentException.class, () -> terminals.inputIntent(owner, 1, invalid));
            }
            assertThrows(IllegalArgumentException.class, () -> terminals.inputIntent(owner, 1, null));
            assertThrows(IllegalArgumentException.class, () -> terminals.inputDelivered(owner.id(), 0, FIRST_DIGEST));
            assertThrows(SecurityException.class, () -> terminals.inputIntent(owner, 2, FIRST_DIGEST));
            assertTrue(terminals.inputIntent(owner, 1, FIRST_DIGEST));
            var restarted = repository(fixture);
            assertThrows(SecurityException.class, () -> restarted.inputIntent(owner, 1, FIRST_DIGEST));
            assertThrows(SecurityException.class, () -> restarted.inputIntent(owner, 2, SECOND_DIGEST));
            assertThrows(PersistenceException.class, () -> restarted.inputDelivered(owner.id(), 1, SECOND_DIGEST));
            assertThrows(PersistenceException.class, () -> restarted.inputDelivered(owner.id(), 2, FIRST_DIGEST));
            assertEquals(
                    FIRST_DIGEST, scalar(fixture, "SELECT INPUT_DIGEST FROM CORE.CODING_TERMINAL WHERE ID='input'"));
            assertEquals("INTENT", scalar(fixture, "SELECT INPUT_STATE FROM CORE.CODING_TERMINAL WHERE ID='input'"));
            restarted.inputDelivered(owner.id(), 1, FIRST_DIGEST);
            assertFalse(repository(fixture).inputIntent(owner, 1, FIRST_DIGEST));
            assertThrows(PersistenceException.class, () -> restarted.inputDelivered(owner.id(), 1, FIRST_DIGEST));
            assertThrows(SecurityException.class, () -> restarted.inputIntent(owner, 1, SECOND_DIGEST));
            assertTrue(restarted.inputIntent(owner, 2, SECOND_DIGEST));
            restarted.inputDelivered(owner.id(), 2, SECOND_DIGEST);
            restarted.state(owner.id(), "COMPLETED", Optional.of(0));
            assertFalse(restarted.inputIntent(owner, 2, SECOND_DIGEST));
            assertThrows(SecurityException.class, () -> restarted.inputIntent(owner, 1, FIRST_DIGEST));
            assertThrows(SecurityException.class, () -> restarted.inputIntent(owner, 3, FIRST_DIGEST));
        }
    }

    @Test
    void 重启只把在途会话和已开始操作标记未知且不抹除输入意图() throws Exception {
        try (var fixture = new CodingTestFixture(temporary)) {
            var terminals = repository(fixture);
            var starting = create(fixture, terminals, "restart-starting");
            var running = create(fixture, terminals, "restart-running");
            var completed = create(fixture, terminals, "restart-completed");
            terminals.state(running.id(), "RUNNING", Optional.empty());
            terminals.inputIntent(running, 1, FIRST_DIGEST);
            terminals.state(completed.id(), "COMPLETED", Optional.of(7));
            var operations = new CodingOperationRepository(fixture.database, fixture.json, fixture.clock);
            operations.start(running.id());
            repository(fixture).recoverInterrupted();
            assertEquals(
                    "UNKNOWN_OUTCOME",
                    terminals.read(fixture.workspace.id(), starting.id()).state());
            assertEquals(
                    "UNKNOWN_OUTCOME",
                    terminals.read(fixture.workspace.id(), running.id()).state());
            assertEquals(
                    Optional.empty(),
                    terminals.read(fixture.workspace.id(), running.id()).exitCode());
            assertEquals(
                    Optional.of(7),
                    terminals.read(fixture.workspace.id(), completed.id()).exitCode());
            assertEquals(
                    "PREPARED",
                    operations
                            .find(fixture.workspace.id(), starting.id())
                            .orElseThrow()
                            .state());
            assertEquals(
                    "UNKNOWN_OUTCOME",
                    operations
                            .find(fixture.workspace.id(), running.id())
                            .orElseThrow()
                            .state());
            assertThrows(SecurityException.class, () -> repository(fixture).inputIntent(running, 1, FIRST_DIGEST));
            assertThrows(SecurityException.class, () -> repository(fixture).inputIntent(running, 2, SECOND_DIGEST));
            assertEquals(
                    "INTENT",
                    scalar(fixture, "SELECT INPUT_STATE FROM CORE.CODING_TERMINAL WHERE ID='restart-running'"));
            terminals.recoverInterrupted();
            assertEquals(
                    "COMPLETED",
                    terminals.read(fixture.workspace.id(), completed.id()).state());
        }
    }

    @Test
    void 损坏的Blob长度或索引缺口必须失败而不是返回伪完整分页() throws Exception {
        try (var fixture = new CodingTestFixture(temporary)) {
            var terminals = repository(fixture);
            var initial = create(fixture, terminals, "corrupt");
            terminals.append(initial, new byte[] {1, 2, 3}, 100);
            var owner = terminals.read(fixture.workspace.id(), initial.id());
            update(fixture, "UPDATE CORE.CODING_TERMINAL_OUTPUT SET CONTENT_LENGTH=2 WHERE SESSION_ID='corrupt'");
            assertThrows(PersistenceException.class, () -> terminals.output(owner, 0, 3));
            update(
                    fixture,
                    "UPDATE CORE.CODING_TERMINAL_OUTPUT SET CONTENT_LENGTH=3,OFFSET_BYTES=1 WHERE SESSION_ID='corrupt'");
            assertThrows(PersistenceException.class, () -> terminals.output(owner, 0, 3));
            update(fixture, "DELETE FROM CORE.CODING_TERMINAL_OUTPUT WHERE SESSION_ID='corrupt'");
            assertThrows(PersistenceException.class, () -> terminals.output(owner, 0, 3));
        }
    }

    @Test
    void 终态事实和Turn迁移同事务回滚且每个会话只入账一次() throws Exception {
        try (var fixture = new CodingTestFixture(temporary)) {
            var terminals = repository(fixture);
            var completed = create(fixture, terminals, "fact-completed");
            var running = create(fixture, terminals, "fact-running");
            terminals.state(completed.id(), "COMPLETED", Optional.of(7));
            terminals.state(running.id(), "RUNNING", Optional.empty());
            var journal = new H2TurnJournal(
                    fixture.database, CoreItemCodecs.createRegistry(fixture.json), fixture.json, fixture.clock);
            assertThrows(
                    PersistenceException.class,
                    () -> journal.transition(
                            fixture.turn.id(), TurnStatus.WAITING, TurnStatus.COMPLETED, Optional.empty()));
            assertEquals("0", scalar(fixture, "SELECT COUNT(*) FROM CORE.ITEM WHERE KIND='command'"));
            assertEquals(
                    "FALSE",
                    scalar(fixture, "SELECT FINAL_FACT_RECORDED FROM CORE.CODING_TERMINAL WHERE ID='fact-completed'"));
            journal.transition(fixture.turn.id(), TurnStatus.RUNNING, TurnStatus.WAITING, Optional.empty());
            assertEquals("1", scalar(fixture, "SELECT COUNT(*) FROM CORE.ITEM WHERE KIND='command'"));
            var fact = fixture.json.decode(
                    new CanonicalPayload(scalar(fixture, "SELECT PAYLOAD FROM CORE.ITEM WHERE KIND='command'")),
                    CorePayloads.Command.class);
            assertEquals(completed.id(), fact.commandId());
            assertEquals(Optional.of(7), fact.exitCode());
            assertEquals(
                    "FALSE",
                    scalar(fixture, "SELECT FINAL_FACT_RECORDED FROM CORE.CODING_TERMINAL WHERE ID='fact-running'"));
            journal.transition(fixture.turn.id(), TurnStatus.WAITING, TurnStatus.RUNNING, Optional.empty());
            assertEquals("1", scalar(fixture, "SELECT COUNT(*) FROM CORE.ITEM WHERE KIND='command'"));
            terminals.state(running.id(), "UNKNOWN_OUTCOME", Optional.empty());
            journal.transition(fixture.turn.id(), TurnStatus.RUNNING, TurnStatus.COMPLETED, Optional.empty());
            assertEquals("2", scalar(fixture, "SELECT COUNT(*) FROM CORE.ITEM WHERE KIND='command'"));
        }
    }

    private static int competingAppend(
            CodingTerminalRepository terminals, Snapshot owner, byte value, CountDownLatch ready, CountDownLatch start)
            throws Exception {
        ready.countDown();
        assertTrue(start.await(5, TimeUnit.SECONDS));
        try {
            terminals.append(owner, new byte[] {value}, 10);
            return 1;
        } catch (PersistenceException conflict) {
            return 0;
        }
    }

    private static CodingTerminalRepository repository(CodingTestFixture fixture) {
        return new CodingTerminalRepository(
                fixture.database,
                new AttachmentService(fixture.database, fixture.json, fixture.clock),
                fixture.json,
                fixture.clock);
    }

    private static Snapshot create(CodingTestFixture fixture, CodingTerminalRepository terminals, String id) {
        var intent = new CodingOperationRepository.Intent(
                id,
                fixture.turn.id(),
                fixture.workspace.id(),
                "call-" + id,
                "terminal_open",
                fixture.root,
                fixture.json.encode(Map.of("argv", List.of("java", "Main"))));
        new CodingOperationRepository(fixture.database, fixture.json, fixture.clock).prepare(intent);
        terminals.create(intent, new CorePayloads.Command(id, List.of("java", "Main"), fixture.root, Optional.empty()));
        return terminals.read(fixture.workspace.id(), id);
    }

    private static String scalar(CodingTestFixture fixture, String sql) throws Exception {
        return new H2Transactions(fixture.database).execute(connection -> {
            try (var statement = connection.prepareStatement(sql);
                    var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getString(1);
            }
        });
    }

    private static void update(CodingTestFixture fixture, String sql) throws Exception {
        new H2Transactions(fixture.database).execute(connection -> {
            try (var statement = connection.prepareStatement(sql)) {
                assertEquals(1, statement.executeUpdate());
            }
            return null;
        });
    }
}
