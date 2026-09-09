package com.javaclaw.desktop;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.javaclaw.api.AgentRole;
import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.ExecutionConfiguration;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.ExecutionPreview;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ExecutionRpcContracts;
import com.javaclaw.protocol.JsonRpcRequest;
import com.javaclaw.protocol.WriteCommand;

/** 通用 Presenter 夹具的有状态配置边界，让真实壳等待 SDK 保存与服务端预览完成。 */
final class PresenterExecutionRpcFixture {
    private final CanonicalJson json = new CanonicalJson();
    private final AgentRole role;
    private final Map<ThreadId, ExecutionConfiguration> threads = new ConcurrentHashMap<>();
    private Optional<ExecutionConfiguration> recent = Optional.empty();

    PresenterExecutionRpcFixture(AgentRole role) {
        this.role = role;
    }

    Optional<Object> respond(JsonRpcRequest request) {
        return switch (request.method()) {
            case "execution/preview" -> Optional.of(preview(request));
            case "execution/recent/read" -> Optional.of(new ExecutionRpcContracts.ReadResult(recent));
            case "execution/recent/update" -> Optional.of(updateRecent(request));
            case "thread/execution/read" -> Optional.of(readThread(request));
            case "thread/execution/update" -> Optional.of(updateThread(request));
            default -> Optional.empty();
        };
    }

    private ExecutionPreview preview(JsonRpcRequest request) {
        var payload = json.decode(request.params(), ExecutionRpcContracts.PreviewPayload.class);
        var inherited = payload.threadId()
                .map(threads::get)
                .map(ExecutionConfiguration::overrides)
                .orElseGet(ExecutionOverrides::empty);
        var reasoning =
                role.spec().reasoning().or(payload.execution()::reasoning).or(inherited::reasoning);
        return new ExecutionPreview(
                Optional.of(new AgentRoleRef(role.id(), role.revision())),
                role.spec().model().map(model -> model.provider()),
                reasoning,
                role.spec().model().isPresent(),
                role.spec().reasoning().isPresent(),
                List.of(),
                List.of());
    }

    private Object readThread(JsonRpcRequest request) {
        var payload = json.decode(request.params(), ExecutionRpcContracts.ThreadReadPayload.class);
        return new ExecutionRpcContracts.ReadResult(Optional.ofNullable(threads.get(payload.threadId())));
    }

    private Object updateThread(JsonRpcRequest request) {
        WriteCommand command = json.decode(request.params(), WriteCommand.class);
        var payload = json.decode(command.payload(), ExecutionRpcContracts.ThreadUpdatePayload.class);
        long revision = Optional.ofNullable(threads.get(payload.threadId()))
                .map(ExecutionConfiguration::revision)
                .orElse(0L);
        requireRevision(command, revision);
        var saved = configuration(
                Optional.of(payload.workspaceId()), Optional.of(payload.threadId()), payload.execution(), revision + 1);
        threads.put(payload.threadId(), saved);
        return saved;
    }

    private Object updateRecent(JsonRpcRequest request) {
        WriteCommand command = json.decode(request.params(), WriteCommand.class);
        var payload = json.decode(command.payload(), ExecutionRpcContracts.RecentUpdatePayload.class);
        long revision = recent.map(ExecutionConfiguration::revision).orElse(0L);
        requireRevision(command, revision);
        recent = Optional.of(configuration(Optional.empty(), Optional.empty(), payload.execution(), revision + 1));
        return recent.orElseThrow();
    }

    private static ExecutionConfiguration configuration(
            Optional<WorkspaceId> workspace, Optional<ThreadId> thread, ExecutionOverrides execution, long revision) {
        return new ExecutionConfiguration(workspace, thread, execution, revision, DesktopTestFixtures.NOW);
    }

    private static void requireRevision(WriteCommand command, long revision) {
        if (command.expectedRevision() != revision) {
            throw new IllegalStateException("配置版本冲突");
        }
    }
}
