package com.javaclaw.desktop.settings;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.layout.VBox;

import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.desktop.component.PlatformComponentFactory;
import com.javaclaw.desktop.view.ViewCommandInvocation;
import com.javaclaw.desktop.view.ViewData;
import com.javaclaw.desktop.view.ViewGraphAction;
import com.javaclaw.desktop.view.ViewInteractionHandler;
import com.javaclaw.desktop.view.ViewPageDirection;
import com.javaclaw.desktop.view.ViewRenderLayout;
import com.javaclaw.desktop.view.ViewRenderSession;
import com.javaclaw.desktop.view.ViewRequestEpoch;
import com.javaclaw.desktop.view.ViewSchemaPolicy;
import com.javaclaw.desktop.view.ViewSchemaRenderer;
import com.javaclaw.extension.spi.ViewSchema;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ExtensionRpcContracts;
import com.javaclaw.protocol.ViewSchemaWireCodec;

/** 在统一设置中心内承载一个内置扩展的 ViewSchema v2 页面。 */
final class ViewSchemaSettingsPage implements ManagedSettingsPage {
    private final String extensionId;
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
    private final ViewPageCacheState cache = new ViewPageCacheState();
    private final ViewPageGraphQueries graphQueries;
    private final Set<String> dirtyForms = new HashSet<>();
    private final ViewPageEventState events = new ViewPageEventState();
    private final ViewPagePresentation presentation = new ViewPagePresentation();
    private final ViewPageTargetSelection targetSelection = new ViewPageTargetSelection();
    private final ViewPageDocumentChoice documentChoice;
    private final ViewPageEventSubscription eventSubscription;
    private Optional<WorkspaceId> workspaceId = Optional.empty();
    private ExtensionRpcContracts.ViewDocument document;
    private ViewSchema schema;
    private ViewData data = ViewData.empty();
    private Node rendered;
    private ViewRenderSession renderSession;
    private boolean commandPending;
    private long commandEpoch;
    private boolean refreshPending;
    private boolean catalogPending;
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
        ViewSchemaPageFailures.requireText(title, "title");
        ViewSchemaPageFailures.requireText(description, "description");
        this.preferredViewId = Optional.ofNullable(preferredViewId)
                .map(value -> ViewSchemaPageFailures.requireText(value, "preferredViewId"));
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        eventSubscription =
                new ViewPageEventSubscription(gateway, extensionId, this::extensionChanged, this::invalidateCache);
        graphQueries = new ViewPageGraphQueries(gateway, requests, loads, body, cache);
        documentChoice = new ViewPageDocumentChoice(documents, title, schemas, this::changeDocument);
        root = ViewSchemaPageLayout.create(title, description, documents, body);
        body.bindDraftActions(() -> rendered, this::discardDraft);
        ViewPageReconciler.install(
                root,
                () -> active
                        && workspaceId.isPresent()
                        && !operationPending()
                        && !hasDraft()
                        && !loads.pending()
                        && !catalogPending,
                this::refreshAuthoritativeState);
    }

    @Override
    public Node content() {
        return root;
    }

    @Override
    public void activate() {
        active = true;
        eventSubscription.activate();
        Optional.ofNullable(renderSession).ifPresent(ViewRenderSession::resume);
        if (workspaceId.isEmpty()) {
            body.showScopeRequired();
            return;
        }
        if (operationPending() || catalogPending || loads.pending()) {
            return;
        }
        if (refreshPending) {
            refreshAfterEvent();
            return;
        }
        if (cache.fresh() || hasDraft()) {
            return;
        }
        refreshAuthoritativeState();
    }

    @Override
    public void deactivate() {
        active = false;
        eventSubscription.deactivate();
        Optional.ofNullable(renderSession).ifPresent(ViewRenderSession::suspend);
        if (!commandPending) {
            cancelLoad();
            cancelUploads();
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
    public void invalidateCache() {
        cache.invalidate();
        events.clear();
        refreshPending = true;
        if (!commandPending) {
            cancelLoad();
        }
    }

    @Override
    public void workspaceChanged(Optional<Workspace> workspace) {
        Optional<WorkspaceId> next =
                Objects.requireNonNull(workspace, "workspace").map(Workspace::id);
        if (workspaceId.equals(next)) {
            return;
        }
        eventSubscription.clear();
        workspaceId = next;
        invalidateCache();
        cancelLoad();
        cancelUploads();
        if (hasDraft()) {
            documents.setDisable(true);
            body.showPageDraftOverlay("工作区已不可用", "当前草稿已保留，不会发送到其他工作区。", false);
            return;
        }
        resetCatalog();
        eventSubscription.workspaceChanged(next);
        if (active) {
            if (next.isPresent()) {
                refreshAuthoritativeState();
            } else {
                body.showScopeRequired();
            }
        }
    }

    @Override
    public void warnUnsavedChanges() {
        body.showPageDraftOverlay("页面存在未保存草稿", "请继续编辑，或丢弃草稿并读取服务端最新状态。", true);
    }

    @Override
    public void discardDraft() {
        if (operationPending()) {
            return;
        }
        presentation.discard();
        dirtyForms.clear();
        presentation.changed();
        if (workspaceId.isPresent()) {
            reload();
        } else {
            resetCatalog();
            body.showScopeRequired();
        }
    }

    @Override
    public void dispose() {
        active = false;
        cancelLoad();
        cancelUploads();
        eventSubscription.close();
        closeRenderSession();
    }

    /** 平台侧资源变更后重新读取当前 Site 扩展的权威投影；已有草稿不会被覆盖。 */
    void refreshAuthoritativeState() {
        refreshPending = true;
        if (active && !commandPending) {
            refreshAfterEvent();
        }
    }

    private void loadCatalog() {
        requireWorkspaceId();
        cache.loading();
        catalogPending = true;
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
        catalogPending = false;
        presentation.changed();
        if (failure != null) {
            body.showRetry("扩展页面目录读取失败", ViewSchemaPageFailures.detail(failure), this::loadCatalog);
            return;
        }
        cache.catalogLoaded();
        applyCatalog(loaded);
    }

    private void applyCatalog(List<ExtensionRpcContracts.ViewDocument> loaded) {
        List<ExtensionRpcContracts.ViewDocument> available = documentChoice.available(
                loaded, extensionId, presentation.layout() == null ? Optional.empty() : preferredViewId);
        if (available.isEmpty()) {
            resetCatalog();
            body.showEmpty("扩展当前不可用", "该内置扩展未启用、尚未提供第 2 版页面定义，或当前工作区不可访问。");
            cache.loaded();
            return;
        }
        documents.setDisable(commandPending);
        selectDocument(documentChoice.preferred(available, document, preferredViewId));
    }

    private void changeDocument(
            ExtensionRpcContracts.ViewDocument previous, ExtensionRpcContracts.ViewDocument selected) {
        if (!confirmContextChange()) {
            documentChoice.select(previous);
            return;
        }
        dirtyForms.clear();
        selectDocument(selected);
    }

    private void selectDocument(ExtensionRpcContracts.ViewDocument selected) {
        cancelLoad();
        cache.loading();
        try {
            ViewSchema next = ViewSchemaPolicy.requireSupported(schemas.decode(selected.schema()));
            if (presentation.layout() == null || !next.equals(schema)) {
                resetPageState();
            }
            document = Objects.requireNonNull(selected, "selected");
            schema = next;
            documentChoice.select(selected);
            body.showLoading(rendered, "正在读取页面权威数据");
            load(false);
        } catch (RuntimeException failure) {
            resetPageState();
            schema = null;
            body.showFatal("页面无法渲染", ViewSchemaPageFailures.detail(failure));
        }
    }

    private void load(boolean automatic) {
        cache.loading();
        var request = loads.begin(requests.begin(), automatic);
        presentation.changed();
        ExtensionRpcContracts.ViewDocument selected = Objects.requireNonNull(document, "document");
        ViewSchema selectedSchema = Objects.requireNonNull(schema, "schema");
        gateway.load(
                        requireWorkspaceId(),
                        selected,
                        selectedSchema,
                        navigation.request(renderSession == null ? Map.of() : renderSession.graphWindows()))
                .whenComplete(
                        (loaded, failure) -> FxStateDispatcher.dispatch(() -> completeLoad(request, loaded, failure)));
    }

    private void completeLoad(ViewPageLoadState.Request request, ViewData loaded, Throwable failure) {
        if (!requests.isCurrent(request.epoch())) {
            return;
        }
        if (!loads.complete(request, hasDraft() || presentation.pending())) {
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
        try {
            if (targetSelection.advance(loaded, navigation)) {
                load(false);
                return;
            }
        } catch (RuntimeException invalidPage) {
            body.showRetry("无法定位所选条目", ViewSchemaPageFailures.detail(invalidPage), this::reload);
            return;
        }
        applyLoaded(loaded);
        cache.loaded();
        refreshAfterEvent();
    }

    private void applyLoaded(ViewData loaded) {
        navigation.acceptInitialSelections(loaded);
        if (rendered != null && data.equals(loaded) && loads.unchanged()) {
            body.showContent(rendered);
            return;
        }
        ViewPageFocus focus = ViewPageFocus.capture(rendered);
        cancelUploads();
        data = Objects.requireNonNull(loaded, "loaded");
        dirtyForms.clear();
        if (renderSession == null || !renderSession.accepts(schema)) {
            closeRenderSession();
            renderSession =
                    new ViewRenderSession(Objects.requireNonNull(schema, "schema"), renderer, presentation.layout());
        }
        renderSession.apply(data, interactions());
        loads.applied();
        presentation.changed();
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
        presentation.changed();
    }

    private void browseGraph(ViewGraphAction action) {
        if (renderSession == null || !confirmContextChange()) {
            return;
        }
        dirtyForms.clear();
        loads.edited();
        graphQueries.browse(
                requireWorkspaceId(),
                extensionId,
                renderSession,
                action,
                rendered,
                this::reload,
                failure -> body.showPageDraftOverlay("图谱浏览未完成", ViewSchemaPageFailures.detail(failure), false));
    }

    private void executeCommand(Optional<String> formId, ViewCommandInvocation invocation) {
        if (operationPending()) {
            return;
        }
        if (!presentation.commandAllowed(schema, dirtyForms, formId, invocation)) {
            body.showPageDraftOverlay("其他分区存在未保存草稿", "请先保存或放弃其他分区的修改，再执行此操作。", true);
            return;
        }
        if (invocation.dangerous() && !ViewSchemaConfirmation.dangerous(root, invocation.operation())) {
            return;
        }
        cancelLoad();
        commandPending = true;
        presentation.changed();
        documents.setDisable(true);
        long epoch = requests.begin();
        commandEpoch = epoch;
        body.showPending(rendered, invocation.operation());
        gateway.execute(requireWorkspaceId(), extensionId, invocation)
                .whenComplete((result, failure) ->
                        FxStateDispatcher.dispatch(() -> completeCommand(epoch, invocation, result, failure)));
    }

    private void completeCommand(
            long epoch, ViewCommandInvocation invocation, ExtensionRpcContracts.CallResult result, Throwable failure) {
        if (commandEpoch != epoch) {
            return;
        }
        commandPending = false;
        documents.setDisable(documents.getItems().isEmpty());
        presentation.changed();
        if (!requests.isCurrent(epoch)) {
            return;
        }
        if (failure == null) {
            dirtyForms.clear();
            events.completed(invocation.operation(), result.revision());
            refreshPending = true;
            presentation.succeeded(invocation, result);
            presentation.changed();
            refreshAfterEvent();
        } else {
            body.showCommandFailure(rendered, failure, () -> body.restoreContent(rendered), this::discardDraft);
        }
    }

    private void changePageFromUser(String sourceId, ViewPageDirection direction) {
        if (confirmContextChange() && navigation.move(sourceId, data.source(sourceId), direction)) {
            reload();
        }
    }

    private boolean changeSelection(String sourceId, Optional<String> selectedKey) {
        String previous = navigation.selections.getOrDefault(
                sourceId, data.source(sourceId).selectedKey().orElse(""));
        String next = selectedKey.orElse("");
        if (Objects.equals(previous, next)) {
            return true;
        }
        if (!confirmContextChange()) {
            return false;
        }
        navigation.selections.put(sourceId, next);
        targetSelection.clear();
        reload();
        return true;
    }

    private void reloadFromUser() {
        if (confirmContextChange()) {
            dirtyForms.clear();
            reload();
        }
    }

    private void reload() {
        refreshPending = false;
        if (cache.requiresCatalog() || document == null || schema == null) {
            loadCatalog();
            return;
        }
        cancelUploads();
        body.showLoading(rendered, "正在读取页面权威数据");
        load(false);
    }

    private void extensionChanged(ExtensionRpcContracts.ExtensionEvent event) {
        if (!events.changed(event)) {
            return;
        }
        refreshPending = true;
        if (active && !commandPending) {
            refreshAfterEvent();
        }
    }

    private void refreshAfterEvent() {
        if (!refreshPending || !active || operationPending() || loads.pending() || catalogPending) {
            return;
        }
        if (hasDraft()) {
            body.showPageDraftOverlay("服务端状态已经更新", "当前草稿仍保留，页面不会自动覆盖。丢弃草稿后将重新读取权威状态。", true);
            return;
        }
        if (cache.requiresCatalog() || rendered == null || body.interactionBlocked()) {
            reload();
        } else {
            refreshPending = false;
            load(true);
        }
    }

    private void cancelLoad() {
        requests.cancel();
        loads.cancel();
        catalogPending = false;
        body.restoreInteraction();
        presentation.changed();
    }

    private void resetPageState() {
        cancelUploads();
        closeRenderSession();
        navigation.clear();
        targetSelection.clear();
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
        documentChoice.clear();
    }

    private WorkspaceId requireWorkspaceId() {
        return workspaceId.orElseThrow(() -> new IllegalStateException("请先在设置中心选择工作区"));
    }

    /** 配置平台拥有的固定页面布局；只能在首次加载前调用。 */
    void configurePresentation(
            ViewRenderLayout layout,
            BooleanSupplier externalDirty,
            BooleanSupplier externalPending,
            Runnable discardExternal) {
        presentation.configure(
                layout, externalDirty, externalPending, discardExternal, document == null && renderSession == null);
        eventSubscription.visibleOnly(preferredViewId.isPresent());
        ViewSchemaPageLayout.hideSelector(root, preferredViewId.isPresent());
    }

    /** 聚合本页与平台分区状态；确认放弃后才允许改变当前编辑上下文。 */
    boolean confirmContextChange() {
        return presentation.confirm(root, pending() || catalogPending || loads.pending(), dirty(), () -> {
            dirtyForms.clear();
            loads.edited();
            if (renderSession != null) {
                applyLoaded(data);
            }
        });
    }

    /** 从权威分页中定位目标行；命令成功回调内只登记目标，由统一刷新启动查询。 */
    boolean selectSource(String sourceId, Optional<String> selectedKey) {
        if (schema == null || !confirmContextChange()) {
            return false;
        }
        targetSelection.begin(schema, navigation, sourceId, selectedKey);
        refreshPending = true;
        if (!presentation.completing()) {
            reload();
        }
        return true;
    }

    /** 在本次命令清除 pending 和草稿后、权威刷新前通知平台壳。 */
    void onCommandSucceeded(BiConsumer<ViewCommandInvocation, ExtensionRpcContracts.CallResult> listener) {
        presentation.onSucceeded(listener);
    }

    /** 本地草稿或请求状态变化时更新平台动作；监听器不能提交业务操作。 */
    void onStateChanged(Runnable listener) {
        presentation.onChanged(listener);
    }

    private boolean hasDraft() {
        return dirty() || presentation.dirty();
    }

    private boolean operationPending() {
        return pending() || presentation.pending();
    }

    private void cancelUploads() {
        Optional.ofNullable(renderSession).ifPresent(ViewRenderSession::cancelUploads);
    }
}
