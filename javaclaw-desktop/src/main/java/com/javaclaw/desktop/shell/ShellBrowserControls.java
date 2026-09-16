package com.javaclaw.desktop.shell;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;

import com.javaclaw.builtin.contracts.BrowserCommands;
import com.javaclaw.builtin.contracts.BrowserContracts;
import com.javaclaw.client.extension.BrowserClient;
import com.javaclaw.desktop.DesktopBrowserGateway;
import com.javaclaw.desktop.DesktopNotificationSubscription;
import com.javaclaw.desktop.component.PlatformComponentFactory;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionSize;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionStyle;
import com.javaclaw.desktop.state.ConnectionState;
import com.javaclaw.desktop.state.DesktopState;

/** 当前聊天的浏览器控制栏；通知只令缓存失效，所有状态均通过 SDK 权威重读。 */
final class ShellBrowserControls extends FlowPane implements AutoCloseable {
    private final DesktopBrowserGateway gateway;
    private final Label status = new Label();
    private final Button takeover;
    private final Button giveBack;
    private final Button close;
    private final Button refresh;
    private final Button capture;
    private final Button saveLogin;
    private final Button sources;
    private BrowserGrantDialog grantsDialog;
    private Optional<Binding> binding = Optional.empty();
    private DesktopNotificationSubscription subscription = () -> {};
    private BrowserCommands.Status snapshot;
    private long epoch;
    private boolean pending;
    private boolean refreshAgain;
    private boolean closed;

    ShellBrowserControls(DesktopBrowserGateway gateway) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        PlatformComponentFactory components = new PlatformComponentFactory();
        takeover = components.action("我来操作", ActionStyle.SOFT, ActionSize.COMPACT);
        giveBack = components.action("交还助手", ActionStyle.SOFT, ActionSize.COMPACT);
        close = components.action("关闭浏览器", ActionStyle.GHOST, ActionSize.COMPACT);
        refresh = components.action("刷新", ActionStyle.GHOST, ActionSize.COMPACT);
        capture = components.action("保存网页登录", ActionStyle.SOFT, ActionSize.COMPACT);
        saveLogin = components.action("仅保存登录态", ActionStyle.GHOST, ActionSize.COMPACT);
        sources = components.action("来源授权", ActionStyle.GHOST, ActionSize.COMPACT);
        sources.setId("browserManageGrants");
        capture.setTooltip(new Tooltip("先选择我来操作，完成登录后选择表单并确认保存密码和登录态"));
        saveLogin.setTooltip(new Tooltip("保存当前账号登录态并在后续自动更新，不读取密码"));
        status.getStyleClass().add("chat-top-meta");
        status.setWrapText(true);
        status.maxWidthProperty().bind(widthProperty());
        setHgap(8);
        setVgap(6);
        setMaxWidth(960);
        setAccessibleText("当前对话浏览器控制");
        getChildren().setAll(status, takeover, giveBack, capture, saveLogin, sources, close, refresh);
        takeover.setOnAction(event -> control(BrowserClient.Control.TAKE_OVER));
        giveBack.setOnAction(event -> control(BrowserClient.Control.RETURN));
        close.setOnAction(event -> control(BrowserClient.Control.CLOSE));
        refresh.setOnAction(event -> refresh());
        capture.setOnAction(event -> saveAccount(true));
        saveLogin.setOnAction(event -> saveAccount(false));
        sources.setOnAction(event -> manageGrants());
        render();
    }

    void bind(DesktopState state) {
        Optional<Binding> next = state.connection().status() == ConnectionState.Status.CONNECTED
                ? state.connection()
                        .connectedAt()
                        .flatMap(connected -> state.threads()
                                .selectedThread()
                                .map(thread -> new Binding(
                                        new DesktopBrowserGateway.Scope(thread.workspaceId(), thread.id()), connected)))
                : Optional.empty();
        if (closed || next.equals(binding)) {
            return;
        }
        epoch++;
        closeGrants();
        subscription.close();
        binding = next;
        snapshot = null;
        pending = false;
        refreshAgain = false;
        render();
        binding.ifPresent(value -> {
            subscription = gateway.subscribe(value.scope(), this::refresh);
            refresh();
        });
    }

    private void manageGrants() {
        if (closed || binding.isEmpty() || grantsDialog != null) {
            return;
        }
        long request = epoch;
        var scope = binding.orElseThrow().scope();
        grantsDialog = new BrowserGrantDialog(
                getScene() == null ? null : getScene().getWindow(),
                gateway,
                scope,
                () -> !closed && request == epoch,
                this::refresh,
                () -> {
                    grantsDialog = null;
                    render();
                });
        sources.setDisable(true);
        grantsDialog.show();
    }

    private void closeGrants() {
        if (grantsDialog != null) {
            grantsDialog.close();
            grantsDialog = null;
        }
    }

    private void refresh() {
        if (closed || binding.isEmpty()) {
            return;
        }
        if (pending) {
            refreshAgain = true;
            return;
        }
        long request = epoch;
        DesktopBrowserGateway.Scope scope = binding.orElseThrow().scope();
        pending = true;
        render();
        gateway.status(scope).whenComplete((value, failure) -> {
            if (closed || request != epoch) {
                return;
            }
            pending = false;
            if (failure != null) {
                status.setText("浏览器状态读取失败，可刷新重试");
                renderActions(false);
            } else {
                apply(scope, value);
            }
            if (refreshAgain) {
                refreshAgain = false;
                refresh();
            }
        });
    }

    private void control(BrowserClient.Control command) {
        if (pending
                || binding.isEmpty()
                || snapshot == null
                || snapshot.session().isEmpty()) {
            return;
        }
        long request = epoch;
        var scope = binding.orElseThrow().scope();
        long generation = snapshot.session().orElseThrow().lease().generation();
        pending = true;
        render();
        gateway.control(scope, command, generation).whenComplete((value, failure) -> {
            if (closed || request != epoch) {
                return;
            }
            pending = false;
            if (failure != null) {
                status.setText("浏览器操作未确认，请刷新状态后重试");
                renderActions(false);
                refreshAgain = false;
                return;
            }
            apply(scope, value);
            refreshAgain = false;
            refresh();
        });
    }

    private void saveAccount(boolean credentials) {
        if (pending
                || binding.isEmpty()
                || snapshot == null
                || snapshot.session().isEmpty()) {
            return;
        }
        var scope = binding.orElseThrow().scope();
        var session = snapshot.session().orElseThrow();
        if (session.lease().mode() != BrowserContracts.ControlMode.HUMAN
                || session.owner().account().isEmpty()) {
            return;
        }
        long request = epoch;
        long generation = session.lease().generation();
        pending = true;
        render();
        var saving = credentials ? captureRequest(scope, generation, request) : gateway.saveLogin(scope, generation);
        saving.whenComplete((value, failure) -> {
            if (closed || request != epoch) {
                return;
            }
            pending = false;
            if (failure != null) {
                status.setText("登录保存未确认，可仅保存登录态或刷新后重试");
                renderActions(false);
                refreshAgain = false;
            } else {
                refreshAgain = false;
                refresh();
            }
        });
    }

    private java.util.concurrent.CompletableFuture<
                    com.javaclaw.builtin.contracts.SiteAccountContracts.AccountProjection>
            captureRequest(DesktopBrowserGateway.Scope scope, long generation, long request) {
        return gateway.loginForms(scope, generation).thenCompose(forms -> {
            if (closed || request != epoch) {
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
            var selected = BrowserLoginCaptureDialog.choose(
                    getScene() == null ? null : getScene().getWindow(), forms.forms());
            return !closed && request == epoch && selected.isPresent()
                    ? gateway.capture(scope, generation, selected.orElseThrow())
                    : java.util.concurrent.CompletableFuture.completedFuture(null);
        });
    }

    private void apply(DesktopBrowserGateway.Scope scope, BrowserCommands.Status value) {
        if (value.session()
                .filter(session -> !session.owner().workspaceId().equals(scope.workspace())
                        || !session.owner().threadId().equals(scope.thread()))
                .isPresent()) {
            snapshot = null;
            render();
            return;
        }
        snapshot = value;
        render();
    }

    private void render() {
        boolean visible = binding.isPresent();
        setVisible(visible);
        setManaged(visible);
        if (!visible) {
            return;
        }
        sources.setDisable(grantsDialog != null);
        if (snapshot == null) {
            status.setText("浏览器状态读取中…");
            renderActions(false);
            return;
        }
        if (snapshot.session().isEmpty()) {
            status.setText("浏览器 · "
                    + snapshot.continuation()
                            .map(BrowserCommands.ContinuationStatus::detail)
                            .orElse(snapshot.detail()));
            status.setTooltip(new Tooltip(snapshot.detail()));
            renderActions(false);
            return;
        }
        var session = snapshot.session().orElseThrow();
        String mode =
                switch (session.lease().mode()) {
                    case HUMAN -> "你正在操作 · 助手已暂停";
                    case ASSISTANT -> "助手正在使用";
                    case NONE -> "等待下一步";
                };
        String continuation = snapshot.continuation()
                .filter(value -> value.state() == BrowserCommands.ContinuationState.FAILED
                        || value.state() == BrowserCommands.ContinuationState.STOPPED)
                .map(value -> " · " + value.detail())
                .orElse("");
        status.setText("浏览器 · " + mode + continuation);
        status.setTooltip(new Tooltip(snapshot.detail()));
        renderActions(snapshot.available() && session.state() == BrowserContracts.SessionState.OPEN);
    }

    private void renderActions(boolean ready) {
        boolean hasSession = snapshot != null && snapshot.session().isPresent();
        for (Button button : java.util.List.of(takeover, giveBack, close, capture, saveLogin)) {
            button.setVisible(hasSession);
            button.setManaged(hasSession);
        }
        boolean human = snapshot != null
                && snapshot.session()
                        .map(session -> session.lease().mode() == BrowserContracts.ControlMode.HUMAN)
                        .orElse(false);
        takeover.setDisable(pending || !ready || human);
        giveBack.setDisable(pending || !ready || !human);
        close.setDisable(pending || !ready);
        refresh.setDisable(pending);
        renderLoginActions(ready, human);
    }

    private void renderLoginActions(boolean ready, boolean human) {
        boolean account = snapshot != null
                && snapshot.session()
                        .flatMap(session -> session.owner().account())
                        .isPresent();
        boolean loginReady = !pending && ready && human && account;
        capture.setDisable(!loginReady);
        saveLogin.setDisable(!loginReady);
    }

    @Override
    public void close() {
        closed = true;
        epoch++;
        closeGrants();
        subscription.close();
        binding = Optional.empty();
        snapshot = null;
        render();
    }

    private record Binding(DesktopBrowserGateway.Scope scope, Instant connection) {}
}
