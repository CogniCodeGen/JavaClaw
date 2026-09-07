package com.javaclaw.server.coding;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AttachmentScope;
import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.CodingContracts;
import com.javaclaw.builtin.contracts.CodingResults;
import com.javaclaw.extension.spi.ContributionKind;
import com.javaclaw.extension.spi.ExtensionRequest;
import com.javaclaw.server.persistence.AttachmentService;
import com.javaclaw.server.persistence.CodingCommandOutputRepository;
import com.javaclaw.server.persistence.CodingCommandOutputRepository.Output;
import com.javaclaw.server.persistence.CodingOperationRepository;
import com.javaclaw.server.persistence.PersistenceException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodingCommandOutputRepositoryTest {
    @TempDir
    Path temporary;

    @Test
    void 输出仅允许原工作区在已开始状态写入一次且附件摘要不授予跨区读取权() throws Exception {
        try (var fixture = new CodingTestFixture(temporary)) {
            var repository = new CodingCommandOutputRepository(fixture.database, fixture.json);
            var attachments = new AttachmentService(fixture.database, fixture.json, fixture.clock);
            var operations = new CodingOperationRepository(fixture.database, fixture.json, fixture.clock);
            var intent = intent(fixture, "owned-output");
            operations.prepare(intent);
            byte[] bytes = "secret output".getBytes(StandardCharsets.UTF_8);
            var digest = store(fixture, attachments, fixture.workspace.id(), "stdout", bytes);
            var empty = store(fixture, attachments, fixture.workspace.id(), "stderr", new byte[0]);
            var output = new Output(digest, bytes.length, empty, 0);
            assertThrows(SecurityException.class, () -> repository.record(fixture.workspace.id(), intent.id(), output));
            operations.start(intent.id());
            assertThrows(SecurityException.class, () -> repository.record(WorkspaceId.random(), intent.id(), output));
            repository.record(fixture.workspace.id(), intent.id(), output);
            assertThrows(SecurityException.class, () -> repository.record(fixture.workspace.id(), intent.id(), output));
            assertEquals(
                    output,
                    new CodingCommandOutputRepository(fixture.database, fixture.json)
                            .read(fixture.workspace.id(), intent.id()));
            var other = fixture.core.createWorkspace(
                    fixture.identity("workspace/create", "other", Map.of()), "Other", temporary.resolve("other"));
            assertThrows(SecurityException.class, () -> repository.read(other.id(), intent.id()));
            assertThrows(
                    PersistenceException.class, () -> attachments.read(AttachmentScope.workspace(other.id()), digest));
            // 同内容只有显式写入另一 Workspace 后才形成它的所有权；全局 CAS 去重不等于共享访问。
            assertEquals(digest, store(fixture, attachments, other.id(), "other-stdout", bytes));
            assertArrayEquals(
                    bytes,
                    attachments
                            .read(AttachmentScope.workspace(other.id()), digest)
                            .content());
        }
    }

    @Test
    void 管理输出以原始字节分页而非替换字符重编码长度计算游标() throws Exception {
        try (var fixture = new CodingTestFixture(temporary)) {
            var attachments = new AttachmentService(fixture.database, fixture.json, fixture.clock);
            var repository = new CodingCommandOutputRepository(fixture.database, fixture.json);
            var operations = new CodingOperationRepository(fixture.database, fixture.json, fixture.clock);
            var intent = intent(fixture, "raw-output");
            operations.prepare(intent);
            operations.start(intent.id());
            byte[] stdout = {(byte) 0xc3, 0x28, 0};
            byte[] stderr = "错".getBytes(StandardCharsets.UTF_8);
            repository.record(
                    fixture.workspace.id(),
                    intent.id(),
                    new Output(
                            store(fixture, attachments, fixture.workspace.id(), "raw-stdout", stdout), stdout.length,
                            store(fixture, attachments, fixture.workspace.id(), "raw-stderr", stderr), stderr.length));
            var command = new CodingResults.CommandResult(
                    new CodingResults.CommandSummary(
                            intent.id(),
                            List.of("mvn", "dependency:go-offline"),
                            ".",
                            Optional.of(0),
                            CodingResults.ProcessState.COMPLETED,
                            1),
                    new CodingResults.Output("display differs from raw bytes", "", 6, false));
            operations.finish(
                    intent.id(),
                    fixture.json.encode(new CodingResults.PreparationResult(
                            CodingContracts.PackageManager.MAVEN, command, List.of())),
                    List.of(),
                    true);
            var first = page(fixture, intent.id(), 0, 1);
            assertEquals("", first.stdout());
            assertEquals(1, first.nextOffsetBytes());
            assertTrue(first.truncated());
            var second = page(fixture, intent.id(), 1, 1);
            assertEquals("\ufffd(", second.stdout());
            assertEquals(2, second.nextOffsetBytes());
            assertTrue(second.truncated());
            var boundary = page(fixture, intent.id(), 2, 2);
            assertEquals("\0", boundary.stdout());
            assertEquals("", boundary.stderr());
            assertEquals(4, boundary.nextOffsetBytes());
            var last = page(fixture, intent.id(), 4, 2);
            assertEquals("错", last.stderr());
            assertEquals(6, last.nextOffsetBytes());
            assertFalse(last.truncated());
            assertEquals("", page(fixture, intent.id(), 6, 10).stdout());
            assertThrows(IllegalArgumentException.class, () -> page(fixture, intent.id(), 7, 1));
        }
    }

    @Test
    void 输出计数溢出不能绕过硬上限() {
        String digest = "a".repeat(64);
        assertThrows(IllegalArgumentException.class, () -> new Output(digest, Long.MAX_VALUE, digest, Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> new Output(digest, 1_048_576, digest, 1));
        assertThrows(IllegalArgumentException.class, () -> new Output(digest, -1, digest, 0));
        assertEquals(1_048_576, new Output(digest, 1_048_576, digest, 0).stdoutBytes());
    }

    private static CodingOperationRepository.Intent intent(CodingTestFixture fixture, String id) {
        return new CodingOperationRepository.Intent(
                id,
                fixture.turn.id(),
                fixture.workspace.id(),
                "call-" + id,
                "dependencies_prepare",
                fixture.root,
                fixture.json.encode(Map.of("manager", "MAVEN")));
    }

    private static String store(
            CodingTestFixture fixture, AttachmentService attachments, WorkspaceId workspace, String key, byte[] bytes) {
        return attachments
                .store(
                        AttachmentScope.workspace(workspace),
                        fixture.identity("attachment/store", key, Map.of("content", bytes)),
                        "application/octet-stream",
                        bytes)
                .digest();
    }

    private static CodingResults.Output page(CodingTestFixture fixture, String id, long offset, int maximum)
            throws Exception {
        var request = new ExtensionRequest(
                fixture.workspace.id(),
                Optional.of(fixture.turn.threadId()),
                Optional.of(fixture.turn.id()),
                "preparation/output",
                fixture.json.encode(new CodingResults.OutputRead(id, offset, maximum)),
                Optional.empty(),
                0,
                Optional.empty());
        try (var binding = fixture.platform.bindManagement(request, ContributionKind.QUERY, new CancellationSource())) {
            return fixture.json.decode(binding.invoke().payload(), CodingResults.Output.class);
        }
    }
}
