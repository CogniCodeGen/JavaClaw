package com.javaclaw.server.coding;

import java.nio.file.Path;
import java.util.Optional;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.CodingResults;
import com.javaclaw.extension.spi.ContributionKind;
import com.javaclaw.extension.spi.ExtensionRequest;
import com.javaclaw.server.persistence.AttachmentService;
import com.javaclaw.server.persistence.CodingCommandStreamRepository;
import com.javaclaw.server.persistence.CodingOperationRepository;

/** 流式输出测试共享真实 H2 与当前权限；不创建外部进程。 */
final class CodingStreamingFixture implements AutoCloseable {
    final CodingTestFixture base;
    final CodingOperationRepository operations;
    final AttachmentService attachments;
    final CodingCommandStreamRepository streams;

    CodingStreamingFixture(Path directory) throws Exception {
        base = new CodingTestFixture(directory);
        operations = new CodingOperationRepository(base.database, base.json, base.clock);
        attachments = new AttachmentService(base.database, base.json, base.clock);
        streams = new CodingCommandStreamRepository(base.database, attachments, base.json);
    }

    CodingCommandStreamRepository.Snapshot start(String id, String kind, long maximum) {
        prepare(id, kind, base.turn.id());
        var snapshot = streams.create(base.workspace.id(), base.turn.id(), id, maximum);
        operations.start(id);
        return snapshot;
    }

    void prepare(String id, String kind, TurnId turnId) {
        operations.prepare(new CodingOperationRepository.Intent(
                id, turnId, base.workspace.id(), id, kind, base.root, base.json.parse("{}")));
    }

    CanonicalPayload query(String operation, Object payload, Optional<ThreadId> thread, Optional<TurnId> turn)
            throws Exception {
        return query(operation, payload, base.workspace.id(), thread, turn);
    }

    CanonicalPayload query(
            String operation, Object payload, WorkspaceId workspace, Optional<ThreadId> thread, Optional<TurnId> turn)
            throws Exception {
        var request = new ExtensionRequest(
                workspace, thread, turn, operation, base.json.encode(payload), Optional.empty(), 0, Optional.empty());
        try (var binding = base.platform.bindManagement(request, ContributionKind.QUERY, new CancellationSource())) {
            return binding.invoke().payload();
        }
    }

    CodingResults.Output output(String operation, String id, long offset, int maximum) throws Exception {
        return base.json.decode(
                query(
                        operation,
                        new CodingResults.OutputRead(id, offset, maximum),
                        Optional.of(base.turn.threadId()),
                        Optional.of(base.turn.id())),
                CodingResults.Output.class);
    }

    @Override
    public void close() throws Exception {
        base.close();
    }
}
