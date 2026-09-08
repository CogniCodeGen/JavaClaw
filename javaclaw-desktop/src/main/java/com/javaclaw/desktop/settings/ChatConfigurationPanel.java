package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Optional;

import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.ExecutionBlocker;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.ExecutionPreview;
import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ReasoningPreference;
import com.javaclaw.api.Workspace;
import com.javaclaw.desktop.component.PlatformStylesheets;

/**
 * 聊天区仅常驻模型、思考和更多设置；选择先保存并预览，再允许发送。
 * 模型新增和使用捕获当前工作区及对话，后台完成不会改写其他页面的草稿。
 */
public final class ChatConfigurationPanel extends VBox implements AutoCloseable {
    private final CoreSettingsGateway gateway;
    private final ChatConfigurationPresenter presenter;
    private final ExecutionSelectionControl advanced = new ExecutionSelectionControl();
    private final ChatModelPicker model;
    private final ComboBox<ReasoningPreference> reasoning = new ComboBox<>();
    private final Button more = new Button("更多 ⋯");
    private final ContextMenu details = new ContextMenu();
    private final Label status = new Label();
    private final Button repair = new Button();
    private final Button retry = new Button("重试");
    private final Button discard = new Button("放弃更改");
    private final Runnable manage;
    private final Runnable chooseWorkspace;
    private final Runnable createThread;
    private Runnable listener = () -> {};
    private boolean rendering;

    /**
     * @param gateway 共享 SDK 配置边界 @param manage 打开模型管理 @param chooseWorkspace 工作区选择入口
     * @param createThread 在当前工作区创建空对话
     */
    public ChatConfigurationPanel(CoreSettingsGateway gateway, Runnable manage, Runnable chooseWorkspace, Runnable createThread) {
        super(6);
        this.gateway = gateway;
        this.manage = manage;
        this.chooseWorkspace = chooseWorkspace;
        this.createThread = createThread;
        presenter = new ChatConfigurationPresenter(gateway);
        model = new ChatModelPicker(this::selectModel, this::addModel, manage, this::restoreProject, this::showMore);
        configure();
        presenter.subscribe(this::render);
    }

    /** @param workspace 聊天工作区 @param thread 聊天对话；缺省时显示创建入口 */
    public void bind(Optional<Workspace> workspace, Optional<ConversationThread> thread) {
        presenter.bind(workspace, thread);
    }

    /** 重连或重新获得焦点后补读；多次请求合并，不丢弃在途失效。 */
    public void refresh() {
        presenter.refresh();
    }

    /** @return 只有已保存且服务端预览就绪时为 true */
    public boolean ready() {
        return presenter.scope().thread().isPresent() && presenter.state().ready();
    }

    /** @return 当前对话的完整显式覆盖；未编辑限制原样保留 */
    public ExecutionOverrides execution() {
        return presenter.state().selection();
    }

    /** @param callback 预览、保存或读取变化时更新发送按钮 */
    public void onStateChanged(Runnable callback) {
        listener = callback;
        listener.run();
    }

    /** 仅清除模型和思考覆盖，恢复项目规则，不修改其他执行字段或日常默认。 */
    public void discard() {
        restoreProject();
    }

    private void configure() {
        advanced.showAdvancedOnly();
        advanced.onChanged(value -> presenter.edit(value, false));
        Label support = new Label("思考档位由接口类型决定；自定义服务是否接受仍以实际请求为准。");
        support.setWrapText(true);
        support.getStyleClass().add("sec-hint");
        Button refresh = new Button("刷新配置");
        refresh.setOnAction(event -> presenter.refresh());
        VBox content = new VBox(10, advanced, support, refresh);
        content.setPrefWidth(360);
        PlatformStylesheets.applyTo(content);
        details.getItems().add(new CustomMenuItem(content, false));
        more.setOnAction(event -> showMore());
        reasoning.setId("chatReasoning");
        reasoning.getStyleClass().add("composer-select");
        reasoning.setAccessibleText("思考强度，跟随设置与关闭思考是不同选项");
        reasoning.setConverter(reasoningConverter());
        reasoning.valueProperty().addListener((ignored, before, after) -> selectReasoning(after));
        more.getStyleClass().add("sidebar-manage-btn");
        status.setWrapText(true);
        status.getStyleClass().add("sec-hint");
        for (Button button : List.of(repair, retry, discard)) {
            button.getStyleClass().add("sidebar-manage-btn");
        }
        retry.setOnAction(event -> presenter.retry());
        discard.setOnAction(event -> presenter.discard());
        repair.setOnAction(event -> repair());
        FlowPane toolbar = new FlowPane(8, 6, model, reasoning, more);
        toolbar.getStyleClass().add("composer-toolbar");
        getChildren().addAll(toolbar, new FlowPane(8, 4, status, repair, retry, discard));
    }

    private void render(ChatConfigurationState state) {
        rendering = true;
        try {
            var resolved = state.preview();
            var provider = resolved.flatMap(ExecutionPreview::provider).or(state.selection()::provider);
            var catalog = state.snapshot().map(ExecutionSelectionLoader.Snapshot::catalog);
            model.render(catalog.map(ExecutionSelectionLoader.Catalog::providers).orElse(List.of()), provider,
                    resolved.map(ExecutionPreview::modelLocked).orElse(false));
            catalog.ifPresent(value -> advanced.setCatalog(value.roles(), value.providers(), value.permissions()));
            advanced.setValue(state.selection());
            state.snapshot().ifPresent(value -> {
                advanced.showSource(value.sources().description());
                advanced.showInheritedRole(value.inheritedRole());
            });
            renderReasoning(state, provider);
            model.setDisable(state.pending());
            more.setDisable(state.pending() || presenter.scope().thread().isEmpty());
            more.setText(state.selection().role().isPresent() || state.selection().permissionProfile().isPresent()
                    ? "更多 · 自定义" : "更多 ⋯");
            status.setText(state.message());
            visible(status, !state.message().isBlank());
            visible(repair, !state.pending() && !state.dirty() && (!state.ready() || presenter.scope().thread().isEmpty()));
            repair.setText(repairLabel(state));
            visible(retry, !state.pending() && (state.dirty() || state.preview().isEmpty()) && presenter.scope().workspace().isPresent());
            visible(discard, state.dirty() && !state.pending());
        } finally {
            rendering = false;
        }
        listener.run();
    }

    private void renderReasoning(ChatConfigurationState state, Optional<ProviderRef> provider) {
        var adapter = state.snapshot().stream().flatMap(value -> value.catalog().providers().stream())
                .filter(endpoint -> provider.map(ref -> ref.endpointId().equals(endpoint.id())).orElse(false))
                .map(endpoint -> endpoint.spec().adapter()).findFirst();
        reasoning.getItems().clear();
        reasoning.getItems().add(null);
        reasoning.getItems().addAll(allowed(adapter));
        state.selection().reasoning().filter(value -> !reasoning.getItems().contains(value)).ifPresent(reasoning.getItems()::add);
        reasoning.setValue(state.selection().reasoning().orElse(null));
        var actual = state.preview().flatMap(ExecutionPreview::reasoning);
        reasoning.setPromptText("思考：" + actual.map(ChatConfigurationPanel::reasoningLabel).orElse("跟随设置"));
        boolean locked = state.preview().map(ExecutionPreview::reasoningLocked).orElse(false);
        if (locked) {
            reasoning.setValue(actual.orElse(null));
        }
        reasoning.setDisable(state.pending() || locked || presenter.scope().thread().isEmpty());
        reasoning.setTooltip(new Tooltip(locked ? "思考强度由 Agent 固定，可在更多设置中更换 Agent"
                : "跟随设置会继承项目配置；关闭则明确请求不推理。具体档位由服务商接受情况决定。"));
    }

    private void selectModel(ProviderRef selected) {
        if (presenter.scope().thread().isEmpty()) {
            ProviderSetupWizard.useModel(getScene().getWindow(), gateway, target(), selected, presenter::refresh);
        } else {
            presenter.edit(replace(selected, presenter.state().selection().reasoning().orElse(null)), true);
        }
    }

    private void selectReasoning(ReasoningPreference selected) {
        if (!rendering) {
            ProviderRef provider = presenter.state().preview().flatMap(ExecutionPreview::provider)
                    .or(presenter.state().selection()::provider).orElse(null);
            presenter.edit(replace(provider, selected), true);
        }
    }

    private ExecutionOverrides replace(ProviderRef provider, ReasoningPreference reasoning) {
        var previous = presenter.state().selection();
        return new ExecutionOverrides(previous.role(), Optional.ofNullable(provider), previous.permissionProfile(),
                previous.approvalPolicy(), previous.budget(), previous.visibleCapabilities(), Optional.ofNullable(reasoning));
    }

    private void restoreProject() {
        presenter.edit(replace(null, null), false);
    }

    private void addModel() {
        ProviderSetupWizard.show(getScene().getWindow(), gateway, target(), presenter::refresh);
    }

    private ProviderSetupTarget target() {
        var scope = presenter.scope();
        return new ProviderSetupTarget(scope.workspace().map(Workspace::id), scope.thread().map(ConversationThread::id),
                scope.workspace().map(Workspace::name).orElse(""));
    }

    private String repairLabel(ChatConfigurationState state) {
        if (presenter.scope().workspace().isEmpty()) {
            return "选择工作区";
        }
        if (state.preview().filter(ExecutionPreview::ready).isPresent() && presenter.scope().thread().isEmpty()) {
            return "开始新对话";
        }
        return state.preview().flatMap(value -> value.blockers().stream().findFirst()).map(blocker -> switch (blocker.code()) {
            case MODEL_REQUIRED, MODEL_UNAVAILABLE -> "选择模型";
            case WORKSPACE_UNAVAILABLE, THREAD_UNAVAILABLE -> "选择工作区";
            case ROLE_UNAVAILABLE, CONFIGURATION_INVALID -> "调整更多设置";
            default -> "修复连接";
        }).orElse("添加模型");
    }

    private void repair() {
        switch (repair.getText()) {
            case "选择工作区" -> chooseWorkspace.run();
            case "开始新对话" -> createThread.run();
            case "选择模型" -> model.fire();
            case "调整更多设置" -> showMore();
            case "修复连接" -> manage.run();
            default -> addModel();
        }
    }

    private void showMore() {
        details.show(more, Side.TOP, 0, 0);
    }

    private StringConverter<ReasoningPreference> reasoningConverter() {
        return new StringConverter<>() {
            @Override
            public String toString(ReasoningPreference value) {
                return value == null ? "跟随设置" : "思考：" + reasoningLabel(value);
            }

            @Override
            public ReasoningPreference fromString(String value) {
                throw new UnsupportedOperationException("思考档位不可自由输入");
            }
        };
    }

    private static List<ReasoningPreference> allowed(Optional<ProviderAdapter> adapter) {
        return List.of(ReasoningPreference.values()).stream().filter(value -> switch (adapter.orElse(ProviderAdapter.OPENAI_COMPATIBLE)) {
            case OPENAI_COMPATIBLE, OPENAI_RESPONSES -> value != ReasoningPreference.MAX;
            case ANTHROPIC -> value != ReasoningPreference.MINIMAL && value != ReasoningPreference.XHIGH;
            case GOOGLE_GENAI -> value != ReasoningPreference.XHIGH && value != ReasoningPreference.MAX;
        }).toList();
    }

    private static String reasoningLabel(ReasoningPreference value) {
        return switch (value) {
            case NONE -> "关闭";
            case MINIMAL -> "最低";
            case LOW -> "低";
            case MEDIUM -> "中";
            case HIGH -> "高";
            case XHIGH -> "更高";
            case MAX -> "最高";
        };
    }

    private static void visible(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    /** 释放配置订阅和弹出层，不关闭共享 SDK。 */
    @Override
    public void close() {
        presenter.close();
        model.close();
        details.hide();
    }
}
