package com.javaclaw.server.persistence;

import java.sql.Connection;
import java.time.Instant;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.CoreSchemas;
import com.javaclaw.api.ItemStatus;
import com.javaclaw.api.MessageRole;
import com.javaclaw.protocol.CanonicalJson;

/** Core 创建事务中的持久写入；调用者已经恢复幂等结果并完成事务外准备。 */
final class TurnCreationWrite {
    private TurnCreationWrite() {}

    static AgentTurn insert(
            Connection connection, TurnStartRequest request, Instant now, CanonicalJson json, boolean evidenceEligible)
            throws Exception {
        AgentTurn created = insert(connection, request, now, json);
        if (!evidenceEligible) {
            ConversationCompletionIndex.exclude(connection, created.id());
        }
        return created;
    }

    static AgentTurn insert(Connection connection, TurnStartRequest request, Instant now, CanonicalJson json)
            throws Exception {
        TurnRepository turns = new TurnRepository();
        turns.lockThread(connection, request.threadId());
        ThreadRepository threads = new ThreadRepository();
        var thread = threads.find(connection, request.threadId()).orElseThrow();
        WorkspaceSecurityRepository.requireUnlocked(connection, thread.workspaceId(), request.executionRoot());
        new ChildTurnReservationRepository(json).requireReservedStart(connection, request);
        if (turns.hasActiveTurn(connection, request.threadId())) {
            throw PersistenceException.revisionConflict("Thread 已有活动 Turn");
        }
        AgentTurn turn = turns.insert(connection, request, now);
        new TurnPromptRepository().insert(connection, turn.id(), request.promptSnapshot());
        new ManifestSnapshotRepository().insert(connection, request.promptSnapshot());
        new TurnToolCatalogRepository().insert(connection, turn.id(), request.toolCatalog(), json);
        if (request.unattendedExecutionScope().isPresent()) {
            new UnattendedTurnScopeRepository()
                    .insert(
                            connection,
                            turn.id(),
                            request.unattendedExecutionScope().orElseThrow(),
                            now);
        }
        var item = new ItemRepository.ItemWrite(
                turn.id(),
                "message",
                CoreSchemas.MESSAGE,
                "core",
                ItemStatus.COMPLETED,
                json.encode(request.message()),
                now);
        var appended = new ItemRepository(turns).append(connection, item);
        if (appended.sequence() == 1 && request.message().role() == MessageRole.USER) {
            threads.titleFromFirstMessage(connection, thread, request.message().text(), now);
        }
        return turn;
    }
}
