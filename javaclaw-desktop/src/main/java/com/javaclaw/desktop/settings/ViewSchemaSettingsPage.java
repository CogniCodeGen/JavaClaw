package com.javaclaw.desktop.settings;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.layout.VBox;

import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.client.RemoteRpcException;
import com.javaclaw.desktop.DesktopNotificationSubscription;
import com.javaclaw.desktop.component.PlatformComponentFactory;
import com.javaclaw.desktop.view.ViewCommandInvocation;
import com.javaclaw.desktop.view.ViewData;
import com.javaclaw.desktop.view.ViewGraphAction;
import com.javaclaw.desktop.view.ViewInteractionHandler;
import com.javaclaw.desktop.view.ViewLoadRequest;
import com.javaclaw.desktop.view.ViewPageDirection;
import com.javaclaw.desktop.view.ViewRenderSession;
import com.javaclaw.desktop.view.ViewRequestEpoch;
import com.javaclaw.desktop.view.ViewSchemaPolicy;
import com.javaclaw.desktop.view.ViewSchemaRenderer;
import com.javaclaw.extension.spi.ViewSchema;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ExtensionRpcContracts;
import com.javaclaw.protocol.ProtocolErrorCode;
import com.javaclaw.protocol.ViewSchemaWireCodec;

/** 在统一设置中心内承载一个内置扩展的 ViewSchema v2 页面。 */
final class ViewSchemaSettingsPage implements ManagedSettingsPage {
    private final String extensionId;
    private final String title;
    private final String description;
    private final Optional<String> preferredViewId;
    private final ExtensionSettingsGateway gateway;
    private final ViewSchemaWireCodec schemas = new ViewSchemaWireCodec(new CanonicalJson());
    private final ViewSchemaRenderer renderer = new ViewSchemaRenderer();
    private final PlatformComponentFactory components = new PlatformComponentFactory();
    private final ViewRequestEpoch requests = new ViewRequestEpoch();
    private final ComboBox<ExtensionRpcContracts.ViewDocument> documents = new ComboBox<>();
    private final ViewSchemaFeedbackPane body = new ViewSchemaFeedbackPane(components);
    private final VBox root;
    private final ViewPageCursorState navigation = new ViewPageCursorState();
    private final ViewPageLoadState loads = new ViewPageLoadState();
    private final ViewPageGraphQueries graphQueries;
    private final Set<String> dirtyForms = new HashSet<>();
    private final Map<ViewEventKey, Long> eventRevisions = new LinkedHashMap<>();
    private final Map<String, Long> completedCommandRevisions = new LinkedHashMap<>();
    private DesktopNotificationSubscription eventSubscription = () -> {};
    private Optional<WorkspaceId> workspaceId = Optional.empty();
    private ExtensionRpcContracts.ViewDocument document;
    private ViewSchema schema;
    private ViewData data = ViewData.empty();
    private Node rendered;
    private ViewRenderSession renderSession;
    private boolean restoringDocument;
    private boolean commandPending;
    private boolean refreshPending;
    private boolean active;

    ViewSchemaSettingsPage(String extensionId, String title, String description, ExtensionSettingsGateway gateway) {
        this(extensionId, title, description, null, gateway);
    }

    ViewSchemaSettingsPage(
            String extensionId,
            String title,
            String description,
            String preferredViewId,
            ExtensionSettingsGateway gateway) {
        this.extensionId = ViewSchemaPageFailures.requireText(extensionId, "extensionId");
        this.title = ViewSchemaPageFailures.requireText(title, "title");
        this.description = ViewSchemaPageFailures.requireText(description, "description");
        this.preferredViewId = Optional.ofNullable(preferredViewId)
                .map(value -> ViewSchemaPageFailures.requireText(value, "preferredViewId"));
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        graphQueries = new ViewPageGraphQueries(gateway, requests, loads);
        configureDocumentChoice();
        root = ViewSchemaPageLayout.create(title, description, documents, body);
        ViewPageReconciler.install(
                root, () -> active && !commandPending && !dirty() && !loads.pending(), this::refreshAuthoritativeState);
    }

    @Override
    public Node content() {
        return root;
    }

    @Override
    public void activate() {
        active = true;
        Optional.ofNullable(renderSession).ifPresent(ViewRenderSession::resume);
        if (workspaceId.isEmpty()) {
            showScopeRequired();
            return;
        }
        if (commandPending) {
            return;
        }
        if (refreshPending) {
            refreshAfterEvent();
            return;
        }
        if (document != null && schema != null) {
            if (!dirty()) {
                reload();
            }
            return;
        }
        renderer.cancelUploads(rendered);
        cancelLoad();
        loadCatalog();
    }

    @Override
    public void deactivate() {
        active = false;
        Optional.ofNullable(renderSession).ifPresent(ViewRenderSession::suspend);
        if (!commandPending) {
            cancelLoad();
            renderer.cancelUploads(rendered);
        }
    }

    @Override
    public boolean dirty() {
        return !dirtyForms.isEmpty();
    }

    @Override
    public boolean pending() {
        return commandPending;
    }

    @Override
    public void workspaceChanged(Optional<Workspace> workspace) {
        Optional<WorkspaceId> next =
                Objects.requireNonNull(workspace, "workspace").map(Workspace::id);
        if (workspaceId.equals(next)) {
            return;
        }
        eventSubscription.close();
        eventSubscription = () -> {};
        workspaceId = next;
        cancelLoad();
        renderer.cancelUploads(rendered);
        if (dirty()) {
            documents.setDisable(true);
            showDraftOverlay("工作区已不可用", "当前草稿已保留，不会发送到其他工作区。", false);
            return;
        }
        resetCatalog();
        next.ifPresent(value -> eventSubscription = gateway.subscribe(value, extensionId, this::extensionChanged));
        if (active) {
            if (next.isPresent()) {
                loadCatalog();
            } else {
                showScopeRequired();
            }
        }
    }

    @Override
    public void warnUnsavedChanges() {
        showDraftOverlay("页面存在未保存草稿", "请继续编辑，或丢弃草稿并读取服务端最新状态。", true);
    }

    @Override
    public void discardDraft() {
        dirtyForms.clear();
        refreshPending = false;
        reload();
    }

    @Override
    public void dispose() {
        active = false;
        cancelLoad();
        renderer.cancelUploads(rendered);
        eventSubscription.close();
        closeRenderSession();
    }

    /**
     * 在扩展选择器之前插入平台拥有的强类型设置分区。
     *
     * <p>该入口只用于 Secret 等不能由 ViewSchema 承载的平台能力，扩展仍不能注入 JavaFX Controller。
     *
     * @param section 平台实现的设置节点
     */
    void addPlatformSection(Node section) {
        root.getChildren().add(2, Objects.requireNonNull(section, "section"));
    }

    /** 平台侧资源变更后重新读取当前 Site 扩展的权威投影；已有草稿不会被覆盖。 */
    void refreshAuthoritativeState() {
        refreshPending = true;
        if (active && !commandPending) {
            refreshAfterEvent();
        }
    }

    private void configureDocumentChoice() {
        documents.setMaxWidth(Double.MAX_VALUE);
        documents.setAccessibleText(title + "页面选择");
        documents.setCellFactory(ignored -> components.detailCell(
                value -> ViewSchemaPageFailures.documentLabel(value, schemas),
                ExtensionRpcContracts.ViewDocument::viewId));
        documents.setButtonCell(components.textCell(value -> ViewSchemaPageFailures.documentLabel(value, schemas)));
        documents.valueProperty().addListener((observable, previous, selected) -> {
            if (!restoringDocument && selected != null && !selected.equals(previous)) {
                changeDocument(previous, selected);
            }
        });
    }

    private void loadCatalog() {
        requireWorkspaceId();
        long epoch = requests.begin();
        body.showLoading("正在读取扩展页面目录");
        gateway.list(extensionId)
                .whenComplete(
                        (loaded, failure) -> FxStateDispatcher.dispatch(() -> completeCatalog(epoch, loaded, failure)));
    }

    private void completeCatalog(long epoch, List<ExtensionRpcContracts.ViewDocument> loaded, Throwable failure) {
        if (!requests.isCurrent(epoch)) {
            return;
        }
        if (failure != null) {
            body.showRetry("扩展页面目录读取失败", ViewSchemaPageFailures.detail(failure), this::loadCatalog);
            return;
        }
        applyCatalog(loaded);
    }

    private void applyCatalog(List<ExtensionRpcContracts.ViewDocument> loaded) {
        List<ExtensionRpcContracts.ViewDocument> available = List.copyOf(loaded).stream()
                .filter(candidate -> extensionId.equals(candidate.extensionId()))
                .sorted(java.util.Comparator.comparing(ExtensionRpcContracts.ViewDocument::viewId))
                .toList();
        documents.getItems().setAll(available);
        if (available.isEmpty()) {
            document = null;
            schema = null;
            documents.setDisable(true);
            showEmpty();
            return;
        }
        documents.setDisable(commandPending);
        ExtensionRpcContracts.ViewDocument preferred = preferredViewId
                .flatMap(viewId -> available.stream()
                        .filter(candidate -> candidate.viewId().equals(viewId))
                        .findFirst())
                .orElse(available.getFirst());
        selectDocument(preferred);
    }

    private void changeDocument(
            ExtensionRpcContracts.ViewDocument previous, ExtensionRpcContracts.ViewDocument selected) {
        if (commandPending) {
            restoreDocument(previous);
            return;
        }
        if (!canDiscardDraft()) {
            restoreDocument(previous);
            return;
        }
        dirtyForms.clear();
        selectDocument(selected);
    }

    private void selectDocument(ExtensionRpcContracts.ViewDocument selected) {
        cancelLoad();
        resetPageState();
        try {
            document = Objects.requireNonNull(selected, "selected");
            schema = ViewSchemaPolicy.requireSupported(schemas.decode(selected.schema()));
            restoringDocument = true;
            documents.setValue(selected);
            restoringDocument = false;
            body.showLoading("正在读取页面权威数据");
            load(false);
        } catch (RuntimeException failure) {
            restoringDocument = false;
            body.showFatal("页面无法渲染", ViewSchemaPageFailures.detail(failure));
        }
    }

    private void load(boolean automatic) {
        var request = loads.begin(requests.begin(), automatic);
        ExtensionRpcContracts.ViewDocument selected = Objects.requireNonNull(document, "document");
        ViewSchema selectedSchema = Objects.requireNonNull(schema, "schema");
        gateway.load(requireWorkspaceId(), selected, selectedSchema, loadRequest())
                .whenComplete(
                        (loaded, failure) -> FxStateDispatcher.dispatch(() -> completeLoad(request, loaded, failure)));
    }

    private void completeLoad(ViewPageLoadState.Request request, ViewData loaded, Throwable failure) {
        if (!requests.isCurrent(request.epoch())) {
            return;
        }
        if (!loads.complete(request, dirty())) {
            refreshPending = true;
            return;
        }
        if (failure != null) {
            if (request.automatic()) {
                refreshPending = true;
            } else {
                body.showRetry("页面数据读取失败", ViewSchemaPageFailures.detail(failure), this::reload);
            }
            return;
        }
        applyLoaded(loaded);
        refreshAfterEvent();
    }

    private void applyLoaded(ViewData loaded) {
        navigation.acceptInitialSelections(loaded);
        if (rendered != null && data.equals(loaded) && loads.unchanged()) {
            body.showContent(rendered);
            return;
        }
        ViewPageFocus focus = ViewPageFocus.capture(rendered);
        renderer.cancelUploads(rendered);
        data = Objects.requireNonNull(loaded, "loaded");
        dirtyForms.clear();
        if (renderSession == null || !renderSession.accepts(schema)) {
            closeRenderSession();
            renderSession = new ViewRenderSession(Objects.requireNonNull(schema, "schema"), renderer);
        }
        renderSession.apply(data, interactions());
        loads.applied();
        rendered = renderSession.node();
        body.showContent(rendered);
        Optional.ofNullable(focus).ifPresent(value -> value.restore(rendered));
    }

    private ViewInteractionHandler interactions() {
        return new ViewPageInteractions(
                this::updateDirty,
                this::executeCommand,
                this::reloadFromUser,
                this::changePageFromUser,
                this::changeSelection,
                request -> gateway.upload(requireWorkspaceId(), request),
                this::browseGraph);
    }

    private void updateDirty(String formId, boolean dirty) {
        loads.edited();
        if (dirty) {
            dirtyForms.add(formId);
        } else {
            dirtyForms.remove(formId);
        }
    }

    private void browseGraph(ViewGraphAction action) {
        if (commandPending || renderSession == null || !canDiscardDraft()) {
            return;
        }
        dirtyForms.clear();
        ViewRenderSession owner = renderSession;
        loads.edited();
        try {
            if (action.filter().isPresent()) {
                owner.filterGraph(action);
                reload();
                return;
            }
            body.showLoading(rendered, "正在读取所选节点的邻居");
            graphQueries.load(requireWorkspaceId(), extensionId, owner, action, failure -> {
                if (failure == null) {
                    reload();
                } else {
                    showDraftOverlay("图谱浏览未完成", ViewSchemaPageFailures.detail(failure), false);
                }
            });
        } catch (RuntimeException failure) {
            showDraftOverlay("图谱浏览未完成", ViewSchemaPageFailures.detail(failure), false);
        }
    }

    private void executeCommand(ViewCommandInvocation invocation) {
        if (commandPending) {
            return;
        }
        if (invocation.dangerous() && !ViewSchemaConfirmation.dangerous(root, invocation.operation())) {
            return;
        }
        cancelLoad();
        commandPending = true;
        documents.setDisable(true);
        long epoch = requests.begin();
        body.showPending(rendered, invocation.operation());
        gateway.execute(requireWorkspaceId(), extensionId, invocation)
                .whenComplete((result, failure) ->
                        FxStateDispatcher.dispatch(() -> completeCommand(epoch, invocation, result, failure)));
    }

    private void completeCommand(
            long epoch, ViewCommandInvocation invocation, ExtensionRpcContracts.CallResult result, Throwable failure) {
        commandPending = false;
        documents.setDisable(documents.getItems().isEmpty());
        if (!requests.isCurrent(epoch)) {
            return;
        }
        if (failure == null) {
            dirtyForms.clear();
            completedCommandRevisions.merge(invocation.operation(), result.revision(), Math::max);
            refreshPending = true;
            refreshAfterEvent();
        } else {
            commandFailed(failure);
        }
    }

    private void commandFailed(Throwable failure) {
        Throwable cause = ViewSchemaPageFailures.unwrap(failure);
        if (cause instanceof RemoteRpcException remote && remote.code() == ProtocolErrorCode.REVISION_CONFLICT) {
            showDraftOverlay("内容已被其他操作更新", "当前草稿仍保留。重新加载会丢弃草稿并读取最新版本。", true);
        } else {
            showDraftOverlay("操作未完成", ViewSchemaPageFailures.detail(cause), false);
        }
    }

    private void changePageFromUser(String sourceId, ViewPageDirection direction) {
        if (canDiscardDraft()) {
            dirtyForms.clear();
            changePage(sourceId, direction);
        }
    }

    private void changePage(String sourceId, ViewPageDirection direction) {
        if (navigation.move(sourceId, data.source(sourceId), direction)) {
            reload();
        }
    }

    private void changeSelection(String sourceId, Optional<String> selectedKey) {
        String previous = navigation.selections.get(sourceId);
        String next = selectedKey.orElse("");
        if (Objects.equals(previous, next) || !canDiscardDraft()) {
            return;
        }
        navigation.selections.put(sourceId, next);
        dirtyForms.clear();
        reload();
    }

    private void reloadFromUser() {
        if (canDiscardDraft()) {
            dirtyForms.clear();
            reload();
        }
    }

    private void reload() {
        refreshPending = false;
        if (document == null || schema == null) {
            loadCatalog();
            return;
        }
        renderer.cancelUploads(rendered);
        body.showLoading(rendered, "正在读取页面权威数据");
        load(false);
    }

    private void extensionChanged(ExtensionRpcContracts.ExtensionEvent event) {
        ViewEventKey key = new ViewEventKey(event.scope(), event.resourceId(), event.operation());
        Long previous = eventRevisions.get(key);
        if (previous != null && event.revision() <= previous) {
            return;
        }
        eventRevisions.put(key, event.revision());
        if (event.revision() <= completedCommandRevisions.getOrDefault(event.operation(), 0L)) {
            return;
        }
        refreshPending = true;
        if (active && !commandPending) {
            refreshAfterEvent();
        }
    }

    private void refreshAfterEvent() {
        if (!refreshPending || !active || commandPending || loads.pending()) {
            return;
        }
        if (dirty()) {
            showDraftOverlay("服务端状态已经更新", "当前草稿仍保留，页面不会自动覆盖。丢弃草稿后将重新读取权威状态。", true);
            return;
        }
        if (rendered == null || body.interactionBlocked()) {
            reload();
        } else {
            refreshPending = false;
            load(true);
        }
    }

    private ViewLoadRequest loadRequest() {
        return navigation.request(renderSession == null ? Map.of() : renderSession.graphWindows());
    }

    private void cancelLoad() {
        requests.cancel();
        loads.cancel();
        body.restoreInteraction();
    }

    private void resetPageState() {
        renderer.cancelUploads(rendered);
        closeRenderSession();
        navigation.clear();
        data = ViewData.empty();
        rendered = null;
    }

    private void closeRenderSession() {
        java.util.Optional.ofNullable(renderSession).ifPresent(ViewRenderSession::close);
        renderSession = null;
    }

    private void resetCatalog() {
        resetPageState();
        document = null;
        schema = null;
        restoringDocument = true;
        try {
            documents.getItems().clear();
            documents.setValue(null);
            documents.setDisable(true);
        } finally {
            restoringDocument = false;
        }
    }

    private WorkspaceId requireWorkspaceId() {
        return workspaceId.orElseThrow(() -> new IllegalStateException("请先在设置中心选择工作区"));
    }

    private void showScopeRequired() {
        body.showEmpty("请选择工作区", "扩展页面只会读写设置中心顶部固定的工作区。");
    }

    private void showEmpty() {
        body.showEmpty("扩展当前不可用", "该内置扩展未启用、尚未提供第 2 版页面定义，或当前工作区不可访问。");
    }

    private void showDraftOverlay(String heading, String detail, boolean offerReload) {
        body.showDraftOverlay(
                rendered, heading, detail, this::restoreRendered, offerReload ? this::discardDraft : null);
    }

    private void restoreRendered() {
        if (rendered != null) {
            body.showContent(rendered);
        }
    }

    private boolean canDiscardDraft() {
        return !dirty() || ViewSchemaConfirmation.discard(root);
    }

    private void restoreDocument(ExtensionRpcContracts.ViewDocument previous) {
        restoringDocument = true;
        try {
            documents.setValue(previous);
        } finally {
            restoringDocument = false;
        }
    }
}
