package com.javaclaw.desktop;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.api.WorkspaceLifecycle;
import com.javaclaw.desktop.state.ConnectionState;
import com.javaclaw.desktop.state.DesktopState;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.protocol.WriteCommand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopWorkspaceCreationTest {
    @Test
    void 自定义中文名称经SDK创建后在当前工作区与目录保持一致() throws Exception {
        PresenterRpcServer server = new PresenterRpcServer();
        AtomicReference<CoreRpcContracts.WorkspaceCreatePayload> submitted = new AtomicReference<>();
        AtomicReference<Workspace> created = new AtomicReference<>();
        respondToCreation(server, submitted, created);
        try (DesktopPresenter presenter = new DesktopPresenter(server::client, Runnable::run, Clock.systemUTC())) {
            AtomicReference<DesktopState> state = new AtomicReference<>();
            presenter.subscribe(state::set);
            presenter.connect();
            await(() -> state.get().connection().status() == ConnectionState.Status.CONNECTED
                    && state.get().transcript().nextSequence() > 0);
            String name = "自定义中文项目 Alpha 2026";
            Path directory = Path.of("/tmp/directory-name-is-different");
            presenter.createWorkspace(name, directory, ExecutionOverrides.empty());
            await(() -> state.get()
                    .threads()
                    .selectedWorkspace()
                    .map(Workspace::name)
                    .filter(name::equals)
                    .isPresent());
            assertEquals(name, submitted.get().name());
            assertEquals(directory, submitted.get().root());
            assertEquals(
                    created.get(), state.get().threads().selectedWorkspace().orElseThrow());
            assertEquals(
                    name,
                    state.get().threads().workspaces().stream()
                            .filter(workspace ->
                                    workspace.id().equals(created.get().id()))
                            .findFirst()
                            .orElseThrow()
                            .name());
        }
    }

    private static void respondToCreation(
            PresenterRpcServer server,
            AtomicReference<CoreRpcContracts.WorkspaceCreatePayload> submitted,
            AtomicReference<Workspace> created) {
        CanonicalJson json = new CanonicalJson();
        server.requestOverride = request -> {
            if (request.method().equals("workspace/create")) {
                var command = json.decode(request.params(), WriteCommand.class);
                var payload = json.decode(command.payload(), CoreRpcContracts.WorkspaceCreatePayload.class);
                submitted.set(payload);
                Workspace result = new Workspace(
                        WorkspaceId.random(),
                        payload.name(),
                        payload.root(),
                        WorkspaceLifecycle.ACTIVE,
                        1,
                        DesktopTestFixtures.NOW,
                        DesktopTestFixtures.NOW);
                created.set(result);
                return Optional.of(result);
            }
            if (request.method().equals("workspace/list") && created.get() != null) {
                return Optional.of(
                        new CoreRpcContracts.WorkspaceListResult(List.of(server.workspace(), created.get())));
            }
            if (request.method().equals("thread/list") && created.get() != null) {
                return Optional.of(new CoreRpcContracts.ThreadListResult(List.of()));
            }
            return Optional.empty();
        };
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertTrue(condition.getAsBoolean(), "工作区创建结果未在限定时间内返回主界面");
    }
}
