package com.javaclaw.desktop.settings;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.layout.VBox;

import com.javaclaw.api.AttachmentRef;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.client.RemoteRpcException;
import com.javaclaw.desktop.DesktopNotificationSubscription;
import com.javaclaw.desktop.component.PlatformComponentFactory;
import com.javaclaw.desktop.view.ViewAttachmentUploadRequest;
import com.javaclaw.desktop.view.ViewCommandInvocation;
import com.javaclaw.desktop.view.ViewData;
import com.javaclaw.desktop.view.ViewInteractionHandler;
import com.javaclaw.desktop.view.ViewLoadRequest;
import com.javaclaw.desktop.view.ViewPageDirection;
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
    private final Map<String, String> cursors = new LinkedHashMap<>();
    private final Map<String, String> selections = new LinkedHashMap<>();
    private final Map<String, Integer> pageIndexes = new LinkedHashMap<>();
    private final Map<String, Deque<String>> cursorHistory = new LinkedHashMap<>();
    private final Set<String> dirtyForms = new HashSet<>();
    private final Map<EventKey, Long> eventRevisions = new LinkedHashMap<>();
    private final Map<String, Long> completedCommandRevisions = new LinkedHashMap<>();
    private DesktopNotificationSubscription eventSubscription = () -> {};
    private Optional<WorkspaceId> workspaceId = Optional.empty();
    private ExtensionRpcContracts.ViewDocument document;
    private ViewSchema schema;
    private ViewData data = ViewData.empty();
    private Node rendered;
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
        this.extensionId = requireText(extensionId, "extensionId");
        this.title = requireText(title, "title");
        this.description = requireText(description, "description");
        this.preferredViewId = Optional.ofNullable(preferredViewId).map(value -> requireText(value, "preferredViewId"));
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        configureDocumentChoice();
        root = ViewSchemaPageLayout.create(title, description, documents, body);
    }

    @Override
    public Node content() {
        return root;
    }

    @Override
    public void activate() {
        active = true;
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
        cancelRenderedUploads();
        requests.cancel();
        loadCatalog();
    }

    @Override
    public void deactivate() {
        active = false;
        if (!commandPending) {
            requests.cancel();
            cancelRenderedUploads();
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
        requests.cancel();
        cancelRenderedUploads();
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
        requests.cancel();
        cancelRenderedUploads();
        eventSubscription.close();
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
        documents.setCellFactory(
                ignored -> components.detailCell(this::documentLabel, ExtensionRpcContracts.ViewDocument::viewId));
        documents.setButtonCell(components.textCell(this::documentLabel));
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
            showRetry("扩展页面目录读取失败", failureDetail(failure), this::loadCatalog);
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
        requests.cancel();
        resetPageState();
        try {
            document = Objects.requireNonNull(selected, "selected");
            schema = ViewSchemaPolicy.requireSupported(schemas.decode(selected.schema()));
            restoringDocument = true;
            documents.setValue(selected);
            restoringDocument = false;
            body.showLoading("正在读取页面权威数据");
            load();
        } catch (RuntimeException failure) {
            restoringDocument = false;
            body.showFatal("页面无法渲染", failureDetail(failure));
        }
    }

    private void load() {
        long epoch = requests.begin();
        ExtensionRpcContracts.ViewDocument selected = Objects.requireNonNull(document, "document");
        ViewSchema selectedSchema = Objects.requireNonNull(schema, "schema");
        gateway.load(requireWorkspaceId(), selected, selectedSchema, loadRequest())
                .whenComplete(
                        (loaded, failure) -> FxStateDispatcher.dispatch(() -> completeLoad(epoch, loaded, failure)));
    }

    private void completeLoad(long epoch, ViewData loaded, Throwable failure) {
        if (!requests.isCurrent(epoch)) {
            return;
        }
        if (failure != null) {
            showRetry("页面数据读取失败", failureDetail(failure), this::reload);
            return;
        }
        applyLoaded(loaded);
    }

    private void applyLoaded(ViewData loaded) {
        cancelRenderedUploads();
        data = Objects.requireNonNull(loaded, "loaded");
        dirtyForms.clear();
        rendered = renderer.render(Objects.requireNonNull(schema, "schema"), data, interactions());
        body.showContent(rendered);
    }

    private ViewInteractionHandler interactions() {
        return new ViewInteractionHandler() {
            @Override
            public void dirty(String formId, boolean dirty) {
                if (dirty) {
                    dirtyForms.add(formId);
                } else {
                    dirtyForms.remove(formId);
                }
            }

            @Override
            public void execute(ViewCommandInvocation invocation) {
                executeCommand(invocation);
            }

            @Override
            public void reload() {
                reloadFromUser();
            }

            @Override
            public void page(String sourceId, ViewPageDirection direction) {
                changePageFromUser(sourceId, direction);
            }

            @Override
            public void select(String sourceId, Optional<String> selectedKey) {
                changeSelection(sourceId, selectedKey);
            }

            @Override
            public CompletableFuture<AttachmentRef> upload(ViewAttachmentUploadRequest request) {
                return gateway.upload(requireWorkspaceId(), request);
            }
        };
    }

    private void executeCommand(ViewCommandInvocation invocation) {
        if (commandPending) {
            return;
        }
        if (invocation.dangerous() && !confirmDanger(invocation.operation())) {
            return;
        }
        commandPending = true;
        documents.setDisable(true);
        long epoch = requests.begin();
        showPending(invocation.operation());
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
        Throwable cause = unwrap(failure);
        if (cause instanceof RemoteRpcException remote && remote.code() == ProtocolErrorCode.REVISION_CONFLICT) {
            showDraftOverlay("内容已被其他操作更新", "当前草稿仍保留。重新加载会丢弃草稿并读取最新版本。", true);
        } else {
            showDraftOverlay("操作未完成", failureDetail(cause), false);
        }
    }

    private void changePageFromUser(String sourceId, ViewPageDirection direction) {
        if (canDiscardDraft()) {
            dirtyForms.clear();
            changePage(sourceId, direction);
        }
    }

    private void changePage(String sourceId, ViewPageDirection direction) {
        ViewData.Source source = data.source(sourceId);
        Deque<String> history = cursorHistory.computeIfAbsent(sourceId, ignored -> new ArrayDeque<>());
        if (direction == ViewPageDirection.NEXT && source.hasMore()) {
            history.push(source.cursor());
            cursors.put(sourceId, source.nextCursor());
            pageIndexes.put(sourceId, source.pageIndex() + 1);
        } else if (direction == ViewPageDirection.PREVIOUS && !history.isEmpty()) {
            cursors.put(sourceId, history.pop());
            pageIndexes.put(sourceId, Math.max(0, source.pageIndex() - 1));
        } else {
            return;
        }
        reload();
    }

    private void changeSelection(String sourceId, Optional<String> selectedKey) {
        String previous = selections.get(sourceId);
        String next = selectedKey.orElse(null);
        if (Objects.equals(previous, next) || !canDiscardDraft()) {
            return;
        }
        selectedKey.ifPresentOrElse(key -> selections.put(sourceId, key), () -> selections.remove(sourceId));
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
        cancelRenderedUploads();
        body.showLoading("正在读取页面权威数据");
        load();
    }

    private void extensionChanged(ExtensionRpcContracts.ExtensionEvent event) {
        EventKey key = new EventKey(event.scope(), event.resourceId(), event.operation());
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
        if (!refreshPending || !active || commandPending) {
            return;
        }
        if (dirty()) {
            showDraftOverlay("服务端状态已经更新", "当前草稿仍保留，页面不会自动覆盖。丢弃草稿后将重新读取权威状态。", true);
            return;
        }
        reload();
    }

    private ViewLoadRequest loadRequest() {
        return new ViewLoadRequest(cursors, selections, pageIndexes);
    }

    private void resetPageState() {
        cancelRenderedUploads();
        cursors.clear();
        selections.clear();
        pageIndexes.clear();
        cursorHistory.clear();
        data = ViewData.empty();
        rendered = null;
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

    private void cancelRenderedUploads() {
        renderer.cancelUploads(rendered);
    }

    private void showEmpty() {
        body.showEmpty("扩展当前不可用", "该内置扩展未启用、尚未提供第 2 版页面定义，或当前工作区不可访问。");
    }

    private void showPending(String operation) {
        body.showPending(rendered, operation);
    }

    private void showRetry(String heading, String detail, Runnable retryAction) {
        body.showRetry(heading, detail, retryAction);
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

    private boolean confirmDanger(String operation) {
        return ViewSchemaConfirmation.dangerous(root, operation);
    }

    private void restoreDocument(ExtensionRpcContracts.ViewDocument previous) {
        restoringDocument = true;
        try {
            documents.setValue(previous);
        } finally {
            restoringDocument = false;
        }
    }

    private String documentLabel(ExtensionRpcContracts.ViewDocument value) {
        try {
            return schemas.decode(value.schema()).title();
        } catch (RuntimeException failure) {
            return value.viewId();
        }
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = Objects.requireNonNull(failure, "failure");
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String failureDetail(Throwable failure) {
        Throwable cause = unwrap(failure);
        String message = cause.getMessage();
        return message == null || message.isBlank() ? "扩展返回了不受支持的页面数据。" : message;
    }

    private static String requireText(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return normalized;
    }

    private record EventKey(String scope, String resourceId, String operation) {
        private EventKey {
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(resourceId, "resourceId");
            Objects.requireNonNull(operation, "operation");
        }
    }
}
