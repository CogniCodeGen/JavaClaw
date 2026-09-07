package com.javaclaw.server.coding;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AttachmentScope;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.server.persistence.CodingCommandStreamRepository;
import com.javaclaw.server.persistence.CodingTerminalRepository;
import com.javaclaw.server.persistence.PersistenceException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 真实 H2/CAS 的执行中分页、封闭窗口和恢复不变量。 */
class CodingCommandStreamTest {
    @TempDir
    Path directory;

    @Test
    void 两通道提交顺序游标在后续stdout增加时仍稳定且旧快照不读取新尾部() throws Exception {
        try (var fixture = new CodingStreamingFixture(directory)) {
            var empty = fixture.start("stream", "dependencies_prepare", 100);
            assertEquals(
                    0, fixture.output("preparation/output", "stream", 0, 16).nextOffsetBytes());
            var first = fixture.streams.append(empty, "stderr", "é".getBytes(StandardCharsets.UTF_8));
            var second = fixture.streams.append(first, "stdout", "abc".getBytes(StandardCharsets.UTF_8));
            var page = fixture.streams.page(second, 1, 3);
            assertArrayEquals(new byte[] {(byte) 0xa9}, page.stderr());
            assertArrayEquals(new byte[] {'a', 'b'}, page.stdout());
            assertEquals(4, page.nextOffsetBytes());
            assertTrue(page.truncated());
            var older = fixture.streams.page(first, 0, 100);
            assertEquals(2, older.nextOffsetBytes());
            assertArrayEquals(new byte[0], older.stdout());
            var last = fixture.output("preparation/output", "stream", 4, 100);
            assertEquals("c", last.stdout());
            assertEquals("", last.stderr());
            assertEquals(5, last.nextOffsetBytes());
            assertFalse(last.truncated());
            assertEquals(
                    5, fixture.output("preparation/output", "stream", 5, 100).nextOffsetBytes());
            assertThrows(IllegalArgumentException.class, () -> fixture.streams.page(second, 6, 1));
        }
    }

    @Test
    void 过期游标不能覆盖已记录内容且跨Workspace或Turn无法读取CAS() throws Exception {
        try (var fixture = new CodingStreamingFixture(directory)) {
            var empty = fixture.start("identity", "command_run", 100);
            var current = fixture.streams.append(empty, "stdout", new byte[] {1, 2, 3});
            assertThrows(PersistenceException.class, () -> fixture.streams.append(empty, "stderr", new byte[] {9}));
            assertThrows(
                    SecurityException.class,
                    () -> fixture.streams.find(WorkspaceId.random(), empty.turnId(), "identity"));
            assertThrows(
                    SecurityException.class,
                    () -> fixture.streams.find(empty.workspaceId(), TurnId.random(), "identity"));
            var forged = new CodingCommandStreamRepository.Snapshot(
                    current.operationId(),
                    TurnId.random(),
                    current.workspaceId(),
                    current.outputBytes(),
                    current.maximumBytes(),
                    current.state(),
                    current.exitCode());
            assertThrows(SecurityException.class, () -> fixture.streams.page(forged, 0, 10));
            assertThrows(SecurityException.class, () -> fixture.streams.append(forged, "stdout", new byte[] {9}));
            assertArrayEquals(
                    new byte[] {1, 2, 3}, fixture.streams.page(current, 0, 100).stdout());
            String digest = new com.javaclaw.server.persistence.H2Transactions(fixture.base.database)
                    .execute(connection -> {
                        try (var query = connection.createStatement();
                                var rows = query.executeQuery("SELECT CONTENT_DIGEST FROM CORE.CODING_COMMAND_CHUNK")) {
                            assertTrue(rows.next());
                            return rows.getString(1);
                        }
                    });
            assertThrows(
                    PersistenceException.class,
                    () -> fixture.attachments.read(AttachmentScope.workspace(WorkspaceId.random()), digest));
        }
    }

    @Test
    void Turn终态拒绝迟到输出但允许可信取消退出收尾且不能覆写() throws Exception {
        try (var fixture = new CodingStreamingFixture(directory)) {
            var owner = fixture.start("cancel", "command_run", 100);
            owner = fixture.streams.append(owner, "stdout", new byte[] {1});
            var finalOwner = owner;
            fixture.base.journal.transition(
                    fixture.base.turn.id(), TurnStatus.RUNNING, TurnStatus.CANCELLED, Optional.empty());
            assertThrows(SecurityException.class, () -> fixture.streams.append(finalOwner, "stdout", new byte[] {2}));
            fixture.streams.complete(owner, "CANCELLED", -1);
            var sealed = fixture.streams
                    .find(owner.workspaceId(), owner.turnId(), owner.operationId())
                    .orElseThrow();
            assertEquals("CANCELLED", sealed.state());
            assertEquals(Optional.of(-1), sealed.exitCode());
            assertThrows(PersistenceException.class, () -> fixture.streams.complete(finalOwner, "COMPLETED", 0));
            assertArrayEquals(
                    new byte[] {1}, fixture.streams.page(sealed, 0, 10).stdout());
        }
    }

    @Test
    void 重启只标记未知并保留片段且未知状态不能被迟到成功回执改写() throws Exception {
        try (var fixture = new CodingStreamingFixture(directory)) {
            var owner = fixture.start("interrupted", "command_run", 100);
            owner = fixture.streams.append(owner, "stderr", new byte[] {4, 5});
            new CodingTerminalRepository(
                            fixture.base.database, fixture.attachments, fixture.base.json, fixture.base.clock)
                    .recoverInterrupted();
            var recovered =
                    new CodingCommandStreamRepository(fixture.base.database, fixture.attachments, fixture.base.json);
            var snapshot = recovered
                    .find(owner.workspaceId(), owner.turnId(), owner.operationId())
                    .orElseThrow();
            assertArrayEquals(
                    new byte[] {4, 5}, recovered.page(snapshot, 0, 100).stderr());
            assertEquals(
                    "UNKNOWN_OUTCOME",
                    fixture.operations
                            .find(owner.workspaceId(), owner.operationId())
                            .orElseThrow()
                            .state());
            assertThrows(SecurityException.class, () -> recovered.append(snapshot, "stdout", new byte[] {6}));
            assertThrows(SecurityException.class, () -> recovered.complete(snapshot, "COMPLETED", 0));
            assertThrows(
                    SecurityException.class,
                    () -> recovered.create(snapshot.workspaceId(), snapshot.turnId(), "interrupted", 100));
        }
    }

    @Test
    void 输出洪泛被双重冻结上限约束且无额外CAS索引写入() throws Exception {
        try (var fixture = new CodingStreamingFixture(directory)) {
            var owner = fixture.start("flood", "command_run", 1_048_576);
            byte[] bytes = new byte[65_536];
            Arrays.fill(bytes, (byte) 'a');
            for (int index = 0; index < 16; index++) {
                owner = fixture.streams.append(owner, index % 2 == 0 ? "stdout" : "stderr", bytes);
            }
            var full = owner;
            assertEquals(1_048_576, full.outputBytes());
            assertThrows(PersistenceException.class, () -> fixture.streams.append(full, "stdout", new byte[] {1}));
            assertThrows(
                    IllegalArgumentException.class, () -> fixture.streams.append(full, "terminal", new byte[] {1}));
            var page = fixture.streams.page(full, 1_048_575, 100);
            assertEquals(1_048_576, page.nextOffsetBytes());
            assertTrue(page.truncated());
            new com.javaclaw.server.persistence.H2Transactions(fixture.base.database).execute(connection -> {
                try (var query = connection.createStatement();
                        var rows = query.executeQuery("SELECT COUNT(*) FROM CORE.CODING_COMMAND_CHUNK")) {
                    assertTrue(rows.next());
                    assertEquals(16, rows.getInt(1));
                }
                return null;
            });
        }
    }

    @Test
    void 持久索引缺失或正文长度不符不能伪造完整输出() throws Exception {
        try (var fixture = new CodingStreamingFixture(directory)) {
            var first = fixture.start("missing", "command_run", 100);
            first = fixture.streams.append(first, "stdout", new byte[] {1, 2});
            var missing = first;
            sql(fixture, "DELETE FROM CORE.CODING_COMMAND_CHUNK WHERE OPERATION_ID='missing'");
            assertThrows(PersistenceException.class, () -> fixture.streams.page(missing, 0, 10));
            var second = fixture.start("corrupt", "command_run", 100);
            second = fixture.streams.append(second, "stderr", new byte[] {3, 4});
            var corrupt = second;
            sql(fixture, "UPDATE CORE.CODING_COMMAND_CHUNK SET CONTENT_LENGTH=1 WHERE OPERATION_ID='corrupt'");
            assertThrows(PersistenceException.class, () -> fixture.streams.page(corrupt, 0, 10));
        }
    }

    @Test
    void UTF8跨通道和单字节页在字符完成时只显示一次且游标始终前进() throws Exception {
        try (var fixture = new CodingStreamingFixture(directory)) {
            var owner = fixture.start("unicode", "command_run", 100);
            byte[] stdout = "你🙂".getBytes(StandardCharsets.UTF_8);
            byte[] stderr = "错🧪".getBytes(StandardCharsets.UTF_8);
            for (int index = 0; index < stdout.length; index++) {
                owner = fixture.streams.append(owner, "stdout", new byte[] {stdout[index]});
                owner = fixture.streams.append(owner, "stderr", new byte[] {stderr[index]});
            }
            var out = new StringBuilder();
            var err = new StringBuilder();
            for (long offset = 0; offset < owner.outputBytes(); offset++) {
                var page = fixture.output("command/output", "unicode", offset, 1);
                assertEquals(offset + 1, page.nextOffsetBytes());
                out.append(page.stdout());
                err.append(page.stderr());
            }
            assertEquals("你🙂", out.toString());
            assertEquals("错🧪", err.toString());
        }
    }

    private static void sql(CodingStreamingFixture fixture, String sql) throws Exception {
        new com.javaclaw.server.persistence.H2Transactions(fixture.base.database).execute(connection -> {
            try (var statement = connection.createStatement()) {
                statement.execute(sql);
            }
            return null;
        });
    }
}
