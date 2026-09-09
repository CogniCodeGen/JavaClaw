package com.javaclaw.desktop;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.ExecutionBlocker;
import com.javaclaw.api.ExecutionConfiguration;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.ExecutionPreview;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.ThreadStatus;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.client.sdk.JavaClawClient;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.protocol.ExecutionRpcContracts;
import com.javaclaw.protocol.JsonRpcRequest;
import com.javaclaw.protocol.WriteCommand;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 仅通过真实 SDK 编解码执行选择的内存服务，故障可发生在提交前或回执返回前。 */
final class ModelSelectionRpcFixture implements AutoCloseable {
    final CanonicalJson json = new CanonicalJson();
    final PresenterRpcServer server = new PresenterRpcServer();
    final Map<ThreadId, ConversationThread> threads = new HashMap<>();
    final Map<ThreadId, ExecutionConfiguration> configurations = new HashMap<>();
    final List<String> mutations = new ArrayList<>();
    final List<WriteCommand> creations = new ArrayList<>();
    final List<WriteCommand> threadUpdates = new ArrayList<>();
    final Map<WorkspaceId, ExecutionOverrides> workspaceDefaults = new HashMap<>();
    private final Map<String, ConversationThread> created = new HashMap<>();
    private final Map<String, ExecutionConfiguration> updated = new HashMap<>();
    Optional<ExecutionConfiguration> defaults = Optional.empty();
    Optional<ExecutionConfiguration> recent = Optional.empty();
    Optional<ProviderRef> lockedModel = Optional.empty();
    boolean failThreadSave;
    boolean failDefaultSave;
    boolean failRecentSave;
    boolean loseCreateResponse;
    boolean loseThreadSaveResponse;
    boolean mismatchAfterSave;

    ModelSelectionRpcFixture() {
        server.requestOverride = this::respond;
        threads.put(server.thread().id(), server.thread());
    }

    JavaClawClient connect() throws IOException {
        return server.client(ignored -> {});
    }

    private Optional<Object> respond(JsonRpcRequest request) {
        return switch (request.method()) {
            case "execution/recent/read" -> Optional.of(new ExecutionRpcContracts.ReadResult(recent));
            case "execution/recent/update" -> Optional.of(updateRecent(request));
            case "execution/default/read" -> Optional.of(new ExecutionRpcContracts.ReadResult(defaults));
            case "thread/execution/read" -> Optional.of(readThread(request));
            case "execution/default/update" -> Optional.of(updateDefaults(request));
            case "thread/execution/update" -> Optional.of(updateThread(request));
            case "execution/preview" -> Optional.of(preview(request));
            case "thread/create" -> Optional.of(createThread(request));
            default -> Optional.empty();
        };
    }

    private Object readThread(JsonRpcRequest request) {
        var payload = json.decode(request.params(), ExecutionRpcContracts.ThreadReadPayload.class);
        requireWorkspace(payload.workspaceId(), payload.threadId());
        return new ExecutionRpcContracts.ReadResult(Optional.ofNullable(configurations.get(payload.threadId())));
    }

    private Object createThread(JsonRpcRequest request) {
        WriteCommand command = json.decode(request.params(), WriteCommand.class);
        creations.add(command);
        var payload = json.decode(command.payload(), CoreRpcContracts.ThreadCreatePayload.class);
        ConversationThread result = created.computeIfAbsent(command.idempotencyKey(), ignored -> {
            var thread = new ConversationThread(
                    ThreadId.random(),
                    payload.workspaceId(),
                    payload.parentThreadId(),
                    payload.executionIntent(),
                    payload.title(),
                    ThreadStatus.ACTIVE,
                    1,
                    DesktopTestFixtures.NOW,
                    DesktopTestFixtures.NOW);
            threads.put(thread.id(), thread);
            mutations.add("thread/create");
            return thread;
        });
        if (loseCreateResponse) {
            loseCreateResponse = false;
            throw new IllegalStateException("模拟 Thread 已创建但回执丢失");
        }
        return result;
    }

    private Object updateDefaults(JsonRpcRequest request) {
        WriteCommand command = json.decode(request.params(), WriteCommand.class);
        var payload = json.decode(command.payload(), ExecutionRpcContracts.DefaultUpdatePayload.class);
        assertEquals(defaults.map(ExecutionConfiguration::revision).orElse(0L), command.expectedRevision());
        if (failDefaultSave) {
            failDefaultSave = false;
            throw new IllegalStateException("模拟默认配置保存失败");
        }
        defaults = Optional.of(configuration(payload.execution(), command.expectedRevision() + 1));
        mutations.add("execution/default/update");
        return defaults.orElseThrow();
    }

    private Object updateRecent(JsonRpcRequest request) {
        WriteCommand command = json.decode(request.params(), WriteCommand.class);
        var payload = json.decode(command.payload(), ExecutionRpcContracts.RecentUpdatePayload.class);
        assertEquals(recent.map(ExecutionConfiguration::revision).orElse(0L), command.expectedRevision());
        if (failRecentSave) {
            failRecentSave = false;
            throw new IllegalStateException("模拟日常偏好保存失败");
        }
        assertEquals(DesktopModelPreferences.models(payload.execution()), payload.execution());
        recent = Optional.of(configuration(payload.execution(), command.expectedRevision() + 1));
        mutations.add("execution/recent/update");
        return recent.orElseThrow();
    }

    private Object updateThread(JsonRpcRequest request) {
        WriteCommand command = json.decode(request.params(), WriteCommand.class);
        threadUpdates.add(command);
        var payload = json.decode(command.payload(), ExecutionRpcContracts.ThreadUpdatePayload.class);
        requireWorkspace(payload.workspaceId(), payload.threadId());
        if (failThreadSave) {
            failThreadSave = false;
            throw new IllegalStateException("模拟 Thread 配置保存失败");
        }
        ExecutionConfiguration saved = updated.computeIfAbsent(command.idempotencyKey(), ignored -> {
            long before = Optional.ofNullable(configurations.get(payload.threadId()))
                    .map(ExecutionConfiguration::revision)
                    .orElse(0L);
            assertEquals(before, command.expectedRevision());
            var result = new ExecutionConfiguration(
                    Optional.of(payload.workspaceId()),
                    Optional.of(payload.threadId()),
                    payload.execution(),
                    before + 1,
                    DesktopTestFixtures.NOW);
            configurations.put(payload.threadId(), result);
            mutations.add("thread/execution/update");
            return result;
        });
        if (loseThreadSaveResponse) {
            loseThreadSaveResponse = false;
            throw new IllegalStateException("模拟 Thread 配置已保存但回执丢失");
        }
        return saved;
    }

    private Object preview(JsonRpcRequest request) {
        var payload = json.decode(request.params(), ExecutionRpcContracts.PreviewPayload.class);
        payload.threadId().ifPresent(thread -> requireWorkspace(payload.workspaceId(), thread));
        ExecutionOverrides inherited = effective(payload);
        Optional<ProviderRef> provider = lockedModel.or(inherited::provider);
        if (mismatchAfterSave && !threadUpdates.isEmpty()) {
            provider = Optional.of(new ProviderRef("other", 1, "changed"));
        }
        return new ExecutionPreview(
                Optional.of(new AgentRoleRef("default", 1)),
                provider,
                inherited.reasoning(),
                lockedModel.isPresent(),
                false,
                List.of(),
                provider.isPresent()
                        ? List.of()
                        : List.of(new ExecutionBlocker(ExecutionBlocker.Code.MODEL_REQUIRED, "请选择模型")));
    }

    private ExecutionOverrides effective(ExecutionRpcContracts.PreviewPayload payload) {
        ExecutionOverrides inherited =
                defaults.map(ExecutionConfiguration::overrides).orElseGet(ExecutionOverrides::empty);
        inherited =
                overlay(inherited, workspaceDefaults.getOrDefault(payload.workspaceId(), ExecutionOverrides.empty()));
        inherited = overlay(
                inherited,
                payload.threadId()
                        .map(configurations::get)
                        .map(ExecutionConfiguration::overrides)
                        .orElseGet(ExecutionOverrides::empty));
        return overlay(inherited, payload.execution());
    }

    private static ExecutionOverrides overlay(ExecutionOverrides inherited, ExecutionOverrides explicit) {
        return DesktopModelPreferences.replace(
                inherited,
                explicit.provider().or(inherited::provider),
                explicit.reasoning().or(inherited::reasoning));
    }

    private void requireWorkspace(WorkspaceId workspace, ThreadId thread) {
        if (!threads.containsKey(thread) || !threads.get(thread).workspaceId().equals(workspace)) {
            throw new IllegalStateException("Thread 不属于 Workspace");
        }
    }

    static ExecutionConfiguration configuration(ExecutionOverrides overrides, long revision) {
        return new ExecutionConfiguration(
                Optional.empty(), Optional.empty(), overrides, revision, DesktopTestFixtures.NOW);
    }

    @Override
    public void close() {
        server.close();
    }
}
