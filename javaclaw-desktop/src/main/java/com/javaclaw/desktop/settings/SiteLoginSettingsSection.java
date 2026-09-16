package com.javaclaw.desktop.settings;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.VBox;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.SiteContracts;
import com.javaclaw.desktop.component.PlatformComponentFactory;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionSize;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionStyle;
import com.javaclaw.desktop.view.ViewCommandInvocation;
import com.javaclaw.desktop.view.ViewData;
import com.javaclaw.desktop.view.ViewRenderSession;
import com.javaclaw.desktop.view.ViewSchemaPolicy;
import com.javaclaw.desktop.view.ViewSchemaRenderer;
import com.javaclaw.extension.spi.ViewQueryRequest;
import com.javaclaw.extension.spi.ViewQueryResult;
import com.javaclaw.extension.spi.ViewSchema;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ExtensionRpcContracts;
import com.javaclaw.protocol.ViewSchemaWireCodec;

/** 网页登录局部视图；父页拥有网站选择，只在账号分区及本区同时展开时读取，旧 scope 回执不能更新新选择。 */
final class SiteLoginSettingsSection {
    private static final String VIEW_ID = "site.browser-login";
    private final ExtensionSettingsGateway gateway;
    private final Runnable changed;
    private final CanonicalJson json = new CanonicalJson();
    private final Label status = new Label("展开后读取当前网站的登录会话");
    private final VBox view = new VBox(12);
    private final Button refresh =
            new PlatformComponentFactory().action("刷新登录会话", ActionStyle.GHOST, ActionSize.COMPACT);
    private final TitledPane root = new TitledPane("网页登录", new VBox(8, status, refresh, view));
    private Optional<WorkspaceId> workspace = Optional.empty();
    private Optional<SiteContracts.Projection> site = Optional.empty();
    private BooleanSupplier externalDirty = () -> false;
    private BooleanSupplier externalPending = () -> false;
    private Runnable stateChanged = () -> {};
    private ViewRenderSession session;
    private ViewSchema schema;
    private ViewData data = ViewData.empty();
    private CompletableFuture<?> loading;
    private long epoch;
    private boolean active;
    private boolean disposed;
    private boolean cacheValid;
    private boolean reading;
    private boolean commandPending;
    private boolean restoringExpansion;

    SiteLoginSettingsSection(ExtensionSettingsGateway gateway, Runnable changed) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.changed = Objects.requireNonNull(changed, "changed");
        root.setExpanded(false);
        root.setAnimated(false);
        status.setWrapText(true);
        refresh.setOnAction(event -> reload());
        root.expandedProperty().addListener((ignored, previous, expanded) -> {
            if (!restoringExpansion && commandPending) {
                restoringExpansion = true;
                root.setExpanded(previous);
                restoringExpansion = false;
            } else if (expanded) {
                loadIfNeeded();
            } else {
                cancelRead();
            }
        });
        refreshContext();
    }

    Node content() {
        return root;
    }

    void setSite(Optional<SiteContracts.Projection> next) {
        Objects.requireNonNull(next, "next");
        if (!site.equals(next)) {
            site = next;
            clearContext();
            loadIfNeeded();
        }
    }

    void workspaceChanged(Optional<Workspace> next) {
        workspace = Objects.requireNonNull(next, "next").map(Workspace::id);
        site = Optional.empty();
        clearContext();
    }

    void activate() {
        if (!disposed) {
            active = true;
            loadIfNeeded();
            refreshContext();
        }
    }

    void deactivate() {
        active = false;
        cancelRead();
        refreshContext();
    }

    void invalidateCache() {
        cacheValid = false;
        if (!commandPending) {
            cancelRead();
        }
    }

    void dispose() {
        disposed = true;
        active = false;
        workspace = Optional.empty();
        site = Optional.empty();
        clearContext();
        stateChanged = () -> {};
    }

    boolean dirty() {
        // 本区只渲染开始 Card 与会话 Table，没有可编辑表单或秘密输入。
        return false;
    }

    boolean pending() {
        return commandPending;
    }

    void discardDraft() {
        // 会话行选择不是可持久化草稿，折叠不会重建表单。
    }

    void warnUnsavedChanges() {
        status.setText("请先保存或放弃其他分区的修改，再操作网页登录");
    }

    void setContextGuard(BooleanSupplier dirty, BooleanSupplier pending) {
        externalDirty = Objects.requireNonNull(dirty, "dirty");
        externalPending = Objects.requireNonNull(pending, "pending");
        refreshContext();
    }

    void onStateChanged(Runnable listener) {
        stateChanged = Objects.requireNonNull(listener, "listener");
    }

    void refreshContext() {
        boolean unavailable = disposed || !active || workspace.isEmpty() || site.isEmpty();
        view.setDisable(unavailable
                || reading
                || commandPending
                || externalDirty.getAsBoolean()
                || externalPending.getAsBoolean());
        refresh.setDisable(unavailable || reading || commandPending);
    }

    private void clearContext() {
        epoch++;
        cancelRead();
        commandPending = false;
        cacheValid = false;
        schema = null;
        data = ViewData.empty();
        if (session != null) {
            session.close();
            session = null;
        }
        view.getChildren().clear();
        status.setText(site.isEmpty() ? "请选择网站" : "展开后读取当前网站的登录会话");
        notifyState();
    }

    private void cancelRead() {
        // 取消只读 Future；已经发送的业务命令不能因页面离开被自动重放。
        if (reading && !commandPending) {
            epoch++;
        }
        CompletableFuture<?> previous = loading;
        loading = null;
        reading = false;
        if (previous != null) {
            previous.cancel(false);
        }
    }

    private boolean visible() {
        return active && root.isExpanded() && !disposed && workspace.isPresent() && site.isPresent();
    }

    private void loadIfNeeded() {
        if (visible() && !cacheValid && !reading && !commandPending) {
            reload();
        }
    }

    private void reload() {
        if (!visible() || reading || commandPending) {
            return;
        }
        long requestEpoch = ++epoch;
        WorkspaceId scope = workspace.orElseThrow();
        SiteContracts.Projection selected = site.orElseThrow();
        reading = true;
        status.setText("正在读取登录会话…");
        refreshContext();
        CompletableFuture<ViewSchema> catalog =
                gateway.list(BuiltinExtensionIds.SITE).thenApply(this::loginSchema);
        loading = catalog.thenCompose(definition -> requestEpoch == epoch
                        ? querySessions(scope, selected, definition)
                        : CompletableFuture.failedFuture(new IllegalStateException("网站选择已变化")))
                .whenComplete((result, failure) -> FxStateDispatcher.dispatch(() -> {
                    if (requestEpoch != epoch || disposed) {
                        return;
                    }
                    reading = false;
                    loading = null;
                    if (failure == null) {
                        apply(result.schema(), result.data());
                        cacheValid = true;
                        status.setText(
                                Boolean.FALSE.equals(data.source("loginSessions")
                                                .values()
                                                .get("interactiveLoginAvailable"))
                                        ? "当前平台尚未提供隔离网页登录"
                                        : "登录会话仅属于当前网站");
                    } else {
                        cacheValid = false;
                        view.getChildren().clear();
                        status.setText(SettingsFailures.message(failure));
                    }
                    notifyState();
                }));
    }

    private ViewSchema loginSchema(List<ExtensionRpcContracts.ViewDocument> documents) {
        ExtensionRpcContracts.ViewDocument document = documents.stream()
                .filter(value -> value.extensionId().equals(BuiltinExtensionIds.SITE)
                        && value.viewId().equals(VIEW_ID))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("网页登录配置当前不可用"));
        ViewSchema checked = ViewSchemaPolicy.requireSupported(new ViewSchemaWireCodec(json).decode(document.schema()));
        if (!checked.viewId().equals(VIEW_ID)) {
            throw new IllegalArgumentException("网页登录视图身份不匹配");
        }
        ViewSchema.Card start = checked.nodes().stream()
                .filter(node -> node.id().equals("login-start"))
                .map(ViewSchema.Card.class::cast)
                .findFirst()
                .orElseThrow();
        ViewSchema.Table sessions = checked.nodes().stream()
                .filter(node -> node.id().equals("login-sessions"))
                .map(ViewSchema.Table.class::cast)
                .findFirst()
                .orElseThrow();
        return ViewSchemaPolicy.requireSupported(new ViewSchema(
                checked.schemaVersion(),
                checked.viewId(),
                checked.title(),
                checked.dataSources(),
                List.of(start, sessions)));
    }

    private CompletableFuture<Loaded> querySessions(
            WorkspaceId scope, SiteContracts.Projection selected, ViewSchema definition) {
        ViewQueryRequest request =
                new ViewQueryRequest("loginSessions", Map.of("siteId", selected.id()), "", 100, Optional.empty());
        return gateway.query(scope, BuiltinExtensionIds.SITE, "login.view", json.encode(request))
                .thenApply(response -> {
                    ViewQueryResult result = json.decode(response.payload(), ViewQueryResult.class);
                    if (!result.dataSourceId().equals("loginSessions")) {
                        throw new IllegalArgumentException("登录会话数据源不匹配");
                    }
                    List<Map<String, Object>> rows = result.rows().stream()
                            .map(this::fields)
                            .filter(row -> selected.id().equals(row.get("siteId")))
                            .toList();
                    ViewData.Source sessions = new ViewData.Source(
                            rows,
                            fields(result.values()),
                            "",
                            result.nextCursor(),
                            result.hasMore(),
                            result.revision(),
                            0,
                            Optional.empty());
                    Map<String, Object> selectedValues = fields(json.encode(selected));
                    ViewData.Source documents = new ViewData.Source(
                            List.of(selectedValues),
                            selectedValues,
                            "",
                            "",
                            false,
                            selected.revision(),
                            0,
                            Optional.of(selected.id()));
                    return new Loaded(
                            definition, new ViewData(Map.of("documents", documents, "loginSessions", sessions)));
                });
    }

    private Map<String, Object> fields(CanonicalPayload payload) {
        Map<?, ?> raw = json.decode(payload, Map.class);
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            if (value != null) {
                result.put(Objects.toString(key), value);
            }
        });
        return Map.copyOf(result);
    }

    private void apply(ViewSchema definition, ViewData loaded) {
        if (session == null || !session.accepts(definition)) {
            if (session != null) {
                session.close();
            }
            session = new ViewRenderSession(definition, new ViewSchemaRenderer());
        }
        schema = definition;
        data = loaded;
        long renderedEpoch = epoch;
        session.apply(
                data,
                new ViewPageInteractions(
                        (id, dirty) -> {},
                        (formId, invocation) -> execute(renderedEpoch, invocation),
                        this::reload,
                        (id, direction) -> {},
                        (id, key) ->
                                !commandPending && !externalPending.getAsBoolean() && !externalDirty.getAsBoolean(),
                        request -> CompletableFuture.failedFuture(new IllegalStateException("网页登录不支持上传附件")),
                        action -> {}));
        view.getChildren().setAll(session.node());
    }

    private void execute(long renderedEpoch, ViewCommandInvocation invocation) {
        if (renderedEpoch != epoch || !visible() || commandPending || reading) {
            return;
        }
        if (externalDirty.getAsBoolean() || externalPending.getAsBoolean()) {
            warnUnsavedChanges();
            return;
        }
        if (invocation.dangerous() && !ViewSchemaConfirmation.dangerous(root, invocation.operation())) {
            return;
        }
        long requestEpoch = ++epoch;
        commandPending = true;
        status.setText("正在执行网页登录操作…");
        notifyState();
        try {
            gateway.execute(workspace.orElseThrow(), BuiltinExtensionIds.SITE, invocation)
                    .whenComplete((result, failure) ->
                            FxStateDispatcher.dispatch(() -> commandCompleted(requestEpoch, failure)));
        } catch (RuntimeException failure) {
            commandCompleted(requestEpoch, failure);
        }
    }

    private void commandCompleted(long requestEpoch, Throwable failure) {
        if (requestEpoch != epoch || disposed) {
            return;
        }
        commandPending = false;
        if (failure == null) {
            cacheValid = false;
            changed.run();
            loadIfNeeded();
        } else {
            status.setText(SettingsFailures.message(failure));
            apply(schema, data);
        }
        notifyState();
    }

    private void notifyState() {
        refreshContext();
        stateChanged.run();
    }

    /** 完成固定 schema 与所选网站查询后的只读结果。 */
    private record Loaded(ViewSchema schema, ViewData data) {}
}
