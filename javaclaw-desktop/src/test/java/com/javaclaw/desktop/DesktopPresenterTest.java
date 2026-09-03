package com.javaclaw.desktop;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.ApprovalDecision;
import com.javaclaw.api.ApprovalState;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.client.sdk.JavaClawClient;
import com.javaclaw.desktop.state.ConnectionState;
import com.javaclaw.desktop.state.DesktopState;
import com.javaclaw.desktop.view.ViewCommandInvocation;
import com.javaclaw.desktop.view.ViewData;
import com.javaclaw.desktop.view.ViewLoadRequest;
import com.javaclaw.extension.spi.ViewArgumentBinding;
import com.javaclaw.extension.spi.ViewBinding;
import com.javaclaw.extension.spi.ViewDataSource;
import com.javaclaw.extension.spi.ViewPlatformDataSource;
import com.javaclaw.extension.spi.ViewQueryRequest;
import com.javaclaw.extension.spi.ViewSchema;
import com.javaclaw.extension.spi.ViewSelectionMode;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ExtensionRpcContracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopPresenterTest {
    private static final Clock CLOCK = Clock.fixed(DesktopTestFixtures.NOW, ZoneOffset.UTC);

    @Test
    void projectsSdkCatalogTurnsApprovalsAndExtensionViewsIntoImmutableState() throws Exception {
        PresenterRpcServer server = new PresenterRpcServer();
        DesktopPresenter presenter = new DesktopPresenter(server::client, Runnable::run, CLOCK);
        AtomicReference<DesktopState> latest = observe(presenter);
        try {
            presenter.connect();
            await(() -> latest.get().connection().status() == ConnectionState.Status.CONNECTED);
            await(() -> latest.get().transcript().nextSequence() == 1);
            assertEquals(
                    "javaclaw-app-server 5.0.0-SNAPSHOT",
                    latest.get().connection().detail());
            assertEquals(
                    DesktopTestFixtures.NOW,
                    latest.get().connection().connectedAt().orElseThrow());
            assertEquals(
                    server.workspace(),
                    latest.get().threads().selectedWorkspace().orElseThrow());
            assertEquals(
                    server.thread(), latest.get().threads().selectedThread().orElseThrow());
            assertEquals(List.of(server.profile()), latest.get().interaction().profiles());
            assertTrue(latest.get().interaction().selectedProfile().isEmpty());

            presenter.selectWorkspace(server.workspace());
            await(() -> latest.get().threads().selectedThread().isPresent());
            presenter.selectThread(server.thread());
            await(() -> latest.get().transcript().nextSequence() == 1);
            presenter.createWorkspace("新增工作区", Path.of("/tmp/new-workspace"));
            await(() -> server.workspaceCreates.get() == 1);
            presenter.createThread("新 Thread");
            await(() -> server.threadCreates.get() == 1);
            assertEquals(0, server.lastThreadCreateExpectedRevision);

            presenter.selectProfile(server.profile());
            assertEquals(
                    server.profile(),
                    latest.get().interaction().selectedProfile().orElseThrow());
            presenter.clearProfileSelection();
            assertTrue(latest.get().interaction().selectedProfile().isEmpty());
            presenter.selectProfile(server.profile());
            server.completion = TurnStatus.COMPLETED;
            presenter.send("  执行升级  ");
            await(() ->
                    server.turnStarts.get() == 1 && !latest.get().interaction().busy());
            assertEquals(0, server.lastTurnStartExpectedRevision);
            assertEquals(
                    server.profile().id(),
                    server.lastTurnStart.profile().orElseThrow().id());
            assertEquals(
                    server.profile().revision(),
                    server.lastTurnStart.profile().orElseThrow().revision());
            assertEquals("执行升级", server.lastTurnStart.message());
            assertEquals(2, latest.get().transcript().nextSequence());
            assertTrue(latest.get().threads().activeTurn().isEmpty());

            presenter.resolveApproval(
                    DesktopTestFixtures.approval(ApprovalState.PENDING), ApprovalDecision.APPROVED, "批准执行");
            await(() -> server.approvalResolutions.get() == 1);

        } finally {
            presenter.close();
            presenter.close();
        }
        assertTrue(server.closed);
    }

    @Test
    void observesResolvesAndCancelsWorkflowOrMcpInputThroughSdk() throws Exception {
        PresenterRpcServer server = new PresenterRpcServer();
        DesktopPresenter presenter = new DesktopPresenter(server::client, Runnable::run, CLOCK);
        AtomicReference<DesktopState> latest = observe(presenter);
        try {
            presenter.connect();
            await(() -> !latest.get().interaction().inputs().pendingRequests().isEmpty());
            var request = latest.get().interaction().inputs().pendingRequests().getFirst();

            CanonicalPayload response =
                    new CanonicalJson().parse("{\"confirmed\":true,\"mode\":\"safe\",\"retries\":3}");
            presenter.inputs().resolve(request, response).join();

            await(() -> server.inputResolutions.get() == 1
                    && latest.get().interaction().inputs().pendingRequests().isEmpty());
            assertEquals(response, server.lastInputResponse);
            assertTrue(latest.get().interaction().inputs().error().isEmpty());

            server.input = DesktopTestFixtures.input();
            await(() -> latest.get().interaction().inputs().pendingRequests().size() == 1);
            presenter
                    .inputs()
                    .cancel(latest.get()
                            .interaction()
                            .inputs()
                            .pendingRequests()
                            .getFirst())
                    .join();

            await(() -> server.turnCancels.get() == 1
                    && latest.get().interaction().inputs().pendingRequests().isEmpty());
            assertEquals(com.javaclaw.api.InputRequestState.CANCELLED, server.input.state());
        } finally {
            presenter.close();
        }
    }

    @Test
    void inputObserverProjectsTransportDisconnectAndStopsUsingOldSession() throws Exception {
        PresenterRpcServer server = new PresenterRpcServer();
        DesktopPresenter presenter = new DesktopPresenter(server::client, Runnable::run, CLOCK);
        AtomicReference<DesktopState> latest = observe(presenter);
        try {
            presenter.connect();
            await(() -> !latest.get().interaction().inputs().pendingRequests().isEmpty());

            server.close();

            await(() -> latest.get().connection().status() == ConnectionState.Status.FAILED);
            assertTrue(latest.get().interaction().inputs().error().isPresent());
        } finally {
            presenter.close();
        }
    }

    @Test
    void loadsDeclarativeExtensionQueriesAndRoutesCommandsWithRevision() throws Exception {
        PresenterRpcServer server = new PresenterRpcServer();
        DesktopPresenter presenter = new DesktopPresenter(server::client, Runnable::run, CLOCK);
        AtomicReference<DesktopState> latest = observe(presenter);
        try {
            presenter.connect();
            await(() -> latest.get().connection().status() == ConnectionState.Status.CONNECTED);
            List<ExtensionRpcContracts.ViewDocument> views =
                    presenter.listExtensionViews(Optional.of("plan")).join();
            assertEquals("plan.documents", views.getFirst().viewId());

            ViewData data = presenter
                    .loadExtensionViewData(
                            server.workspace().id(), views.getFirst(), querySchema(), ViewLoadRequest.initial())
                    .join();
            assertEquals(5, server.extensionQueries.get());
            assertEquals("计划", data.source("list").rows().getFirst().get("title"));

            presenter
                    .executeExtensionViewCommand(
                            server.workspace().id(),
                            "plan",
                            new ViewCommandInvocation("put", Map.of("title", "升级"), 7, false))
                    .join();
            await(() -> server.extensionCommands.get() == 1);
            assertEquals(7, server.lastExpectedRevision);
            presenter
                    .executeExtensionViewCommand(
                            server.workspace().id(), "plan", new ViewCommandInvocation("put", Map.of(), 0, false))
                    .join();
            await(() -> server.extensionCommands.get() == 2);
            assertEquals(0, server.lastExpectedRevision);
        } finally {
            presenter.close();
        }
    }

    @Test
    void loadsMasterBeforeDependentsAndBindsSelectedDefinitionFields() throws Exception {
        PresenterRpcServer server = new PresenterRpcServer();
        DesktopPresenter presenter = new DesktopPresenter(server::client, Runnable::run, CLOCK);
        AtomicReference<DesktopState> latest = observe(presenter);
        try {
            presenter.connect();
            await(() -> latest.get().connection().status() == ConnectionState.Status.CONNECTED);
            ExtensionRpcContracts.ViewDocument document =
                    presenter.listExtensionViews(Optional.of("plan")).join().getFirst();

            ViewData initial = presenter
                    .loadExtensionViewData(
                            server.workspace().id(), document, masterDetailSchema(), ViewLoadRequest.initial())
                    .join();

            assertEquals(
                    Optional.of("workflow-1"), initial.source("definitions").selectedKey());
            assertEquals(
                    List.of("definitions", "graphNodes", "graphEdges"),
                    server.viewQueries.stream()
                            .map(ViewQueryRequest::dataSourceId)
                            .toList());
            assertEquals(
                    Map.of("definitionId", "workflow-1", "definitionRevision", "3"),
                    server.viewQueries.get(1).arguments());

            ViewLoadRequest second = new ViewLoadRequest(Map.of(), Map.of("definitions", "workflow-2"), Map.of());
            ViewData switched = presenter
                    .loadExtensionViewData(server.workspace().id(), document, masterDetailSchema(), second)
                    .join();

            assertEquals(
                    Optional.of("workflow-2"), switched.source("definitions").selectedKey());
            assertEquals(
                    Map.of("definitionId", "workflow-2", "definitionRevision", "7"),
                    server.viewQueries.get(4).arguments());
        } finally {
            presenter.close();
        }
    }

    @Test
    void loadsGovernedToolAndScalarPointerOptionsFromExactWorkspaceProfile() throws Exception {
        PresenterRpcServer server = new PresenterRpcServer();
        DesktopPresenter presenter = new DesktopPresenter(server::client, Runnable::run, CLOCK);
        AtomicReference<DesktopState> latest = observe(presenter);
        try {
            presenter.connect();
            await(() -> latest.get().connection().status() == ConnectionState.Status.CONNECTED);
            ViewSchema schema = new ViewSchema(
                    ViewSchema.CURRENT_VERSION,
                    "governed-options",
                    "受治理选项",
                    List.of(
                            new ViewDataSource("tools", ViewPlatformDataSource.TOOL_CATALOG, Map.of(), List.of(), 100),
                            new ViewDataSource(
                                    "fields", ViewPlatformDataSource.TOOL_OUTPUT_FIELDS, Map.of(), List.of(), 200)),
                    List.of(new ViewSchema.Card("summary", "目录", "平台只读目录", List.of())));
            ExtensionRpcContracts.ViewDocument document = new ExtensionRpcContracts.ViewDocument(
                    "loop", "loop.management", new CanonicalJson().encode(schema));

            ViewData loaded = presenter
                    .loadExtensionViewData(server.workspace().id(), document, schema, ViewLoadRequest.initial())
                    .join();

            assertEquals("read_file", loaded.source("tools").rows().getFirst().get("toolName"));
            assertEquals(
                    List.of("/exitCode", "/metadata/verified"),
                    loaded.source("fields").rows().stream()
                            .map(row -> row.get("fieldPointer"))
                            .toList());
            assertEquals(9, loaded.source("tools").revision());
            assertEquals(server.workspace().id(), server.lastToolCatalog.workspaceId());
            assertEquals(
                    server.profile().spec().permissionProfile().id(), server.lastToolCatalog.permissionProfileId());
            assertEquals(
                    server.profile().spec().permissionProfile().version(),
                    server.lastToolCatalog.permissionProfileVersion());
            assertEquals(
                    new com.javaclaw.api.AgentProfileRef(
                            server.profile().id(), server.profile().revision()),
                    server.lastToolCatalog.agentProfile().orElseThrow());
            assertEquals(0, server.extensionQueries.get());
        } finally {
            presenter.close();
        }
    }

    @Test
    void governedToolOptionsFailClosedWithoutWorkspaceDefaultProfile() throws Exception {
        PresenterRpcServer server = new PresenterRpcServer();
        server.profileBound = false;
        DesktopPresenter presenter = new DesktopPresenter(server::client, Runnable::run, CLOCK);
        AtomicReference<DesktopState> latest = observe(presenter);
        try {
            presenter.connect();
            await(() -> latest.get().connection().status() == ConnectionState.Status.CONNECTED);
            ViewSchema schema = new ViewSchema(
                    ViewSchema.CURRENT_VERSION,
                    "governed-options",
                    "受治理选项",
                    List.of(new ViewDataSource("tools", ViewPlatformDataSource.TOOL_CATALOG, Map.of(), List.of(), 100)),
                    List.of(new ViewSchema.Card("summary", "目录", "平台只读目录", List.of())));
            ExtensionRpcContracts.ViewDocument document = new ExtensionRpcContracts.ViewDocument(
                    "loop", "loop.management", new CanonicalJson().encode(schema));

            RuntimeException failure = assertThrows(
                    RuntimeException.class,
                    () -> presenter
                            .loadExtensionViewData(server.workspace().id(), document, schema, ViewLoadRequest.initial())
                            .join());

            assertTrue(failure.getCause().getMessage().contains("尚未绑定默认智能体"));
            assertEquals(0, server.extensionQueries.get());
        } finally {
            presenter.close();
        }
    }

    @Test
    void cancellationAndFailedTurnStopObservationWithoutLeavingBusyState() throws Exception {
        PresenterRpcServer server = new PresenterRpcServer();
        DesktopPresenter presenter = new DesktopPresenter(server::client, Runnable::run, CLOCK);
        AtomicReference<DesktopState> latest = observe(presenter);
        try {
            presenter.connect();
            await(() -> latest.get().connection().status() == ConnectionState.Status.CONNECTED
                    && latest.get().threads().selectedThread().isPresent()
                    && latest.get().transcript().nextSequence() == 1);
            server.completion = TurnStatus.RUNNING;
            presenter.send("等待取消");
            await(() -> latest.get().threads().activeTurn().isPresent());
            assertTrue(server.lastTurnStart.profile().isEmpty());
            presenter.cancelActiveTurn();
            await(() ->
                    server.turnCancels.get() == 1 && !latest.get().interaction().busy());
            assertTrue(latest.get().threads().activeTurn().isEmpty());

            server.completion = TurnStatus.FAILED;
            presenter.send("失败场景");
            await(() ->
                    server.turnStarts.get() == 2 && !latest.get().interaction().busy());
            assertTrue(latest.get().threads().activeTurn().isEmpty());
        } finally {
            presenter.close();
        }
    }

    @Test
    void reportsConfigurationAndInputFailuresWithoutGuessingFallbacks() throws Exception {
        DesktopPresenter failed = new DesktopPresenter(
                notifications -> {
                    throw new IOException("x".repeat(600));
                },
                Runnable::run,
                CLOCK);
        AtomicReference<DesktopState> failedState = observe(failed);
        failed.connect();
        await(() -> failedState.get().connection().status() == ConnectionState.Status.FAILED);
        assertEquals(500, failedState.get().interaction().error().orElseThrow().length());
        failed.close();

        DesktopPresenter unnamed = new DesktopPresenter(
                notifications -> {
                    throw new IOException();
                },
                Runnable::run,
                CLOCK);
        AtomicReference<DesktopState> unnamedState = observe(unnamed);
        unnamed.connect();
        await(() -> unnamedState.get().connection().status() == ConnectionState.Status.FAILED);
        assertEquals("IOException", unnamedState.get().connection().detail());
        unnamed.close();

        PresenterRpcServer server = new PresenterRpcServer();
        DesktopPresenter presenter = new DesktopPresenter(server::client, Runnable::run, CLOCK);
        AtomicReference<DesktopState> latest = observe(presenter);
        try {
            assertThrows(NullPointerException.class, () -> presenter.selectWorkspace(null));
            assertThrows(NullPointerException.class, () -> presenter.selectThread(null));
            assertThrows(NullPointerException.class, () -> presenter.selectProfile(null));
            assertThrows(
                    NullPointerException.class, () -> presenter.resolveApproval(null, ApprovalDecision.DENIED, "拒绝"));
            assertThrows(NullPointerException.class, () -> presenter.listExtensionViews(null));
            assertThrows(
                    NullPointerException.class,
                    () -> presenter.loadExtensionViewData(null, null, querySchema(), ViewLoadRequest.initial()));
            assertThrows(NoSuchElementException.class, () -> presenter.createThread("无工作区"));
            assertThrows(
                    NullPointerException.class,
                    () -> presenter.executeExtensionViewCommand(
                            null, "plan", new ViewCommandInvocation("put", Map.of(), 0, false)));

            presenter.connect();
            await(() -> latest.get().connection().status() == ConnectionState.Status.CONNECTED
                    && latest.get().threads().selectedThread().isPresent());
            assertThrows(IllegalArgumentException.class, () -> presenter.send(" "));
            assertThrows(NullPointerException.class, () -> presenter.send(null));
            assertThrows(IllegalArgumentException.class, () -> new ViewCommandInvocation("put", Map.of(), -1, false));
            assertFalse(latest.get().interaction().busy());
        } finally {
            presenter.close();
        }
    }

    @Test
    void closesConnectionThatFinishesAfterPresenterShutdown() throws Exception {
        PresenterRpcServer server = new PresenterRpcServer();
        JavaClawClient client = server.client(ignored -> {});
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        DesktopPresenter presenter = new DesktopPresenter(
                notifications -> {
                    entered.countDown();
                    boolean interrupted = false;
                    while (release.getCount() != 0) {
                        try {
                            if (!release.await(5, TimeUnit.SECONDS)) {
                                throw new IOException("test connector timeout");
                            }
                        } catch (InterruptedException failure) {
                            interrupted = true;
                        }
                    }
                    if (interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return client;
                },
                Runnable::run,
                CLOCK);

        presenter.connect();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        presenter.close();
        release.countDown();
        await(() -> server.closed);
    }

    @Test
    void reconnectClosesPreviousSessionAndPublishesOnlyNewCatalog() throws Exception {
        PresenterRpcServer first = new PresenterRpcServer();
        PresenterRpcServer second = new PresenterRpcServer();
        AtomicInteger attempts = new AtomicInteger();
        DesktopPresenter presenter = new DesktopPresenter(
                notifications ->
                        attempts.getAndIncrement() == 0 ? first.client(notifications) : second.client(notifications),
                Runnable::run,
                CLOCK);
        AtomicReference<DesktopState> latest = observe(presenter);
        try {
            presenter.connect();
            await(() -> latest.get().connection().status() == ConnectionState.Status.CONNECTED);
            assertEquals("5.0.0-SNAPSHOT", presenter.reconnect().join().serverVersion());
            await(() -> attempts.get() == 2 && latest.get().connection().status() == ConnectionState.Status.CONNECTED);
            assertTrue(first.closed);
            assertFalse(second.closed);
        } finally {
            presenter.close();
        }
        assertTrue(second.closed);
    }

    @Test
    void extensionEvent只投递当前Workspace并尊重订阅生命周期() throws Exception {
        PresenterRpcServer server = new PresenterRpcServer();
        DesktopPresenter presenter = new DesktopPresenter(server::client, Runnable::run, CLOCK);
        AtomicReference<DesktopState> latest = observe(presenter);
        AtomicInteger deliveries = new AtomicInteger();
        AtomicReference<ExtensionRpcContracts.ExtensionEvent> last = new AtomicReference<>();
        DesktopNotificationSubscription subscription =
                presenter.subscribeExtensionEvents(server.workspace().id(), "plan", event -> {
                    deliveries.incrementAndGet();
                    last.set(event);
                });
        try {
            presenter.connect();
            await(() -> latest.get().connection().status() == ConnectionState.Status.CONNECTED);
            server.emit(event(server.workspace().id(), "plan", 1));
            await(() -> deliveries.get() == 1);

            server.emit(event(server.workspace().id(), "memory", 2));
            server.emit(event(WorkspaceId.parse("0795bb32-0e6e-4998-819c-2e89203297f2"), "plan", 2));
            server.emit(event(server.workspace().id(), "plan", 3));
            await(() -> deliveries.get() == 2);
            assertEquals(3, last.get().revision());

            subscription.close();
            server.emit(event(server.workspace().id(), "plan", 4));
            presenter.listExtensionViews(Optional.of("plan")).join();
            assertEquals(2, deliveries.get());
        } finally {
            subscription.close();
            presenter.close();
        }
    }

    private static AtomicReference<DesktopState> observe(DesktopPresenter presenter) {
        AtomicReference<DesktopState> latest = new AtomicReference<>();
        presenter.subscribe(latest::set);
        return latest;
    }

    private static ExtensionRpcContracts.ExtensionEvent event(
            WorkspaceId workspaceId, String extensionId, long revision) {
        return new ExtensionRpcContracts.ExtensionEvent(
                workspaceId, extensionId, "document", "primary", "put", revision);
    }

    private static ViewSchema querySchema() {
        return new ViewSchema(
                ViewSchema.CURRENT_VERSION,
                "queries",
                "查询",
                List.of(
                        new ViewDataSource("list", "view.list", Map.of(), List.of(), 20),
                        new ViewDataSource("table", "view.table", Map.of(), List.of(), 20),
                        new ViewDataSource("progress", "view.progress", Map.of(), List.of(), 1),
                        new ViewDataSource("timeline", "view.timeline", Map.of(), List.of(), 20),
                        new ViewDataSource("content", "view.content", Map.of(), List.of(), 1)),
                List.of(
                        new ViewSchema.ListView(
                                "list", "列表", "list", "title", "title", "detail", ViewSelectionMode.NONE, List.of()),
                        new ViewSchema.Table(
                                "table",
                                "表格",
                                "table",
                                "title",
                                List.of(new ViewSchema.Column("title", "标题", Optional.empty())),
                                ViewSelectionMode.NONE,
                                List.of()),
                        new ViewSchema.Progress("progress", "进度", "progress", "value", "title"),
                        new ViewSchema.Timeline("timeline", "时间线", "timeline", "title", "detail"),
                        new ViewSchema.Markdown("markdown", "说明", new ViewBinding("content", "description"))));
    }

    private static ViewSchema masterDetailSchema() {
        List<ViewArgumentBinding> selection = List.of(
                new ViewArgumentBinding("definitionId", "definitions", "id"),
                new ViewArgumentBinding("definitionRevision", "definitions", "revision"));
        return new ViewSchema(
                ViewSchema.CURRENT_VERSION,
                "workflow.graph",
                "工作流图",
                List.of(
                        new ViewDataSource("definitions", "view.list", Map.of(), List.of(), 20),
                        new ViewDataSource("graphNodes", "graph/node/view.list", Map.of(), selection, 100),
                        new ViewDataSource("graphEdges", "graph/edge/view.list", Map.of(), selection, 100)),
                List.of(
                        new ViewSchema.Table(
                                "definitions",
                                "定义",
                                "definitions",
                                "id",
                                List.of(new ViewSchema.Column("name", "名称", Optional.empty())),
                                ViewSelectionMode.SINGLE,
                                List.of()),
                        new ViewSchema.Graph(
                                "graph", "图", "graphNodes", "graphEdges", "id", "name", "kind", "from", "to")));
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("异步状态未在期限内到达");
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError("测试等待被中断", failure);
            }
        }
    }
}
