package com.javaclaw.server.coding;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.ToolExecutionFact;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.server.persistence.CodingOperationRepository;
import com.javaclaw.server.persistence.CodingOperationRepository.Intent;
import com.javaclaw.server.persistence.H2Transactions;
import com.javaclaw.server.persistence.PersistenceException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodingOperationRepositoryTest {
    @TempDir
    Path temporary;

    @Test
    void 幂等身份拒绝异参跨工作区和跨根且请求摘要损坏不能恢复() throws Exception {
        try (var fixture = new CodingTestFixture(temporary)) {
            var repository = repository(fixture);
            var intent = intent(fixture, "identity");
            assertEquals(repository.prepare(intent), repository.prepare(intent));
            assertThrows(
                    SecurityException.class,
                    () -> repository.prepare(new Intent(
                            intent.id(),
                            intent.turnId(),
                            intent.workspaceId(),
                            intent.callId(),
                            intent.operation(),
                            intent.executionRoot(),
                            fixture.json.encode(Map.of("argv", List.of("java", "Other"))))));
            assertThrows(
                    SecurityException.class,
                    () -> repository.prepare(new Intent(
                            intent.id(),
                            intent.turnId(),
                            WorkspaceId.random(),
                            intent.callId(),
                            intent.operation(),
                            intent.executionRoot(),
                            intent.request())));
            assertThrows(
                    SecurityException.class,
                    () -> repository.prepare(new Intent(
                            intent.id(),
                            intent.turnId(),
                            intent.workspaceId(),
                            intent.callId(),
                            intent.operation(),
                            temporary,
                            intent.request())));
            assertThrows(SecurityException.class, () -> repository.find(WorkspaceId.random(), intent.id()));
            assertTrue(repository.find(fixture.workspace.id(), "missing").isEmpty());
            new H2Transactions(fixture.database).execute(connection -> {
                try (var statement =
                        connection.prepareStatement("UPDATE CORE.CODING_OPERATION SET REQUEST_DIGEST=? WHERE ID=?")) {
                    statement.setString(1, "0".repeat(64));
                    statement.setString(2, intent.id());
                    assertEquals(1, statement.executeUpdate());
                }
                return null;
            });
            assertThrows(PersistenceException.class, () -> repository.prepare(intent));
        }
    }

    @Test
    void 未知结果在重建仓库后仍禁止重放且不能补造成功记录() throws Exception {
        try (var fixture = new CodingTestFixture(temporary)) {
            var repository = repository(fixture);
            var intent = intent(fixture, "unknown");
            repository.prepare(intent);
            var preparation = fixture.json.encode(Map.of("before", "original"));
            repository.preparation(intent.id(), preparation);
            repository.start(intent.id());
            assertEquals(
                    "STARTED",
                    repository
                            .find(fixture.workspace.id(), intent.id())
                            .orElseThrow()
                            .state());
            repository.unknown(intent.id());
            var restarted = repository(fixture);
            var recovered = restarted.prepare(intent);
            assertEquals("UNKNOWN_OUTCOME", recovered.state());
            assertEquals(Optional.of(preparation), recovered.preparation());
            assertTrue(recovered.result().isEmpty());
            assertThrows(PersistenceException.class, () -> restarted.start(intent.id()));
            assertThrows(PersistenceException.class, () -> restarted.preparation(intent.id(), preparation));
            assertThrows(
                    PersistenceException.class,
                    () -> restarted.finish(intent.id(), fixture.json.encode(Map.of("success", true)), List.of(), true));
            assertEquals("UNKNOWN_OUTCOME", restarted.prepare(intent).state());
        }
    }

    @Test
    void 非零退出的实际失败及平台事实在重建仓库后完整保留() throws Exception {
        try (var fixture = new CodingTestFixture(temporary)) {
            var repository = repository(fixture);
            var intent = intent(fixture, "exit");
            repository.prepare(intent);
            repository.start(intent.id());
            var result = fixture.json.encode(Map.of("exitCode", 7));
            List<ToolExecutionFact> facts = List.of(
                    new ToolExecutionFact(new CorePayloads.Command(
                            intent.id(), List.of("java", "Main"), fixture.root, Optional.of(7))),
                    new ToolExecutionFact(new CorePayloads.FileChange(
                            Path.of("generated.txt"), "create", Optional.empty(), Optional.of("a".repeat(64)))));
            repository.finish(intent.id(), result, facts, false);
            var recovered = repository(fixture).prepare(intent);
            assertEquals("FINISHED", recovered.state());
            assertFalse(recovered.success());
            assertEquals(Optional.of(result), recovered.result());
            assertEquals(facts, recovered.facts());
            assertThrows(PersistenceException.class, () -> repository.start(intent.id()));
            assertThrows(PersistenceException.class, () -> repository.finish(intent.id(), result, List.of(), true));
        }
    }

    private static CodingOperationRepository repository(CodingTestFixture fixture) {
        return new CodingOperationRepository(fixture.database, fixture.json, fixture.clock);
    }

    private static Intent intent(CodingTestFixture fixture, String id) {
        return new Intent(
                id,
                fixture.turn.id(),
                fixture.workspace.id(),
                "call-" + id,
                "command_run",
                fixture.root,
                fixture.json.encode(Map.of("argv", List.of("java", "Main"))));
    }
}
