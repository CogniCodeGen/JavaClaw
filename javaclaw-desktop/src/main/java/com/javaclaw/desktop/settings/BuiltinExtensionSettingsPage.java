package com.javaclaw.desktop.settings;

import java.util.Objects;
import java.util.Optional;

import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import com.javaclaw.desktop.component.AsyncActionBar;
import com.javaclaw.desktop.component.AsyncActionBar.ActionState;
import com.javaclaw.desktop.component.FormSection;
import com.javaclaw.desktop.component.ListDetailPane;
import com.javaclaw.desktop.component.PlatformComponentFactory;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionSize;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionStyle;
import com.javaclaw.desktop.component.PlatformComponentFactory.FeedbackKind;
import com.javaclaw.desktop.component.PlatformDialogs;
import com.javaclaw.desktop.component.RevisionConflictPane;
import com.javaclaw.extension.spi.ExtensionAvailability;
import com.javaclaw.extension.spi.ExtensionState;
import com.javaclaw.protocol.BuiltinExtensionRpcContracts;

/** 可选内置扩展包与 MCP 平台能力的统一实时启停页。 */
public final class BuiltinExtensionSettingsPage extends VBox implements ManagedSettingsPage {
    private final PlatformComponentFactory components = new PlatformComponentFactory();
    private final BuiltinExtensionSettingsPresenter presenter;
    private final ListDetailPane<BuiltinExtensionRpcContracts.Status> masterDetail = new ListDetailPane<>();
    private final Label identity = value();
    private final Label runtime = value();
    private final Label lifecycle = value();
    private final Label availability = value();
    private final Label descriptorRevision = value();
    private final Label stateRevision = value();
    private final Label contributions = value();
    private final Label updatedAt = value();
    private final Button refresh;
    private final Button toggle;
    private final AsyncActionBar actions;
    private final RevisionConflictPane conflict;
    private BuiltinExtensionSettingsState state = BuiltinExtensionSettingsState.initial();
    private boolean rendering;

    /** @param gateway 强类型 SDK 设置边界 */
    public BuiltinExtensionSettingsPage(BuiltinExtensionSettingsGateway gateway) {
        presenter = new BuiltinExtensionSettingsPresenter(gateway);
        refresh = action("刷新", ActionStyle.GHOST, presenter::reload);
        toggle = action("停用", ActionStyle.SOFT, this::confirmTransition);
        actions = new AsyncActionBar(refresh, toggle);
        conflict = new RevisionConflictPane(presenter::reload, this::describeConflict);
        configurePage();
        presenter.subscribe(this::render);
    }

    @Override
    public Node content() {
        return this;
    }

    @Override
    public Optional<Node> actionContent() {
        return Optional.of(actions);
    }

    @Override
    public void activate() {
        presenter.reload();
    }

    @Override
    public boolean dirty() {
        return false;
    }

    @Override
    public void warnUnsavedChanges() {}

    @Override
    public void discardDraft() {}

    private void configurePage() {
        Label title = new Label("内置扩展");
        title.getStyleClass().addAll("sec-title", "platform-page-title");
        Label hint = new Label("停用会立即阻止新的工具、页面、定时器和命令；已有数据不会删除。MCP 平台能力使用同一安全开关。");
        hint.setWrapText(true);
        hint.getStyleClass().add("sec-hint");
        masterDetail
                .list()
                .setCellFactory(ignored -> components.detailCell(
                        item -> item.displayName() + " · " + SettingsLabels.extensionState(item.state()),
                        item -> item.id() + " · " + availability(item.availability())));
        masterDetail
                .list()
                .getSelectionModel()
                .selectedItemProperty()
                .addListener((observable, previous, selected) -> select(selected));
        masterDetail
                .list()
                .setPlaceholder(components.feedback(FeedbackKind.EMPTY, "暂无内置能力", "服务端没有返回内置扩展包或 MCP 平台能力。"));
        masterDetail.showDetail(detail());
        getChildren().addAll(title, hint, masterDetail);
        getStyleClass().add("platform-page");
    }

    private Node detail() {
        FormSection status = new FormSection("实时状态", "状态来自 H2 v5 权威目录；页面不会从本地进程或旧配置推测启停结果。");
        status.addField("能力", identity);
        status.addField("执行边界", runtime);
        status.addField("生命周期", lifecycle);
        status.addField("可用性", availability);
        status.addField("贡献描述版本", descriptorRevision);
        status.addField("启停状态版本", stateRevision);
        status.addField("贡献点", contributions);
        status.addField("更新时间", updatedAt);
        VBox detail = new VBox(12, status, conflict);
        detail.getStyleClass().add("platform-page");
        return detail;
    }

    private void select(BuiltinExtensionRpcContracts.Status selected) {
        if (!rendering && selected != null && !selected.equals(state.selected().orElse(null))) {
            presenter.select(selected);
        }
    }

    private void render(BuiltinExtensionSettingsState snapshot) {
        state = Objects.requireNonNull(snapshot, "snapshot");
        rendering = true;
        try {
            masterDetail.list().getItems().setAll(snapshot.extensions());
            masterDetail.list().getSelectionModel().select(snapshot.selected().orElse(null));
        } finally {
            rendering = false;
        }
        renderDetails(snapshot.selected());
        renderActions(snapshot);
    }

    private void renderDetails(Optional<BuiltinExtensionRpcContracts.Status> selected) {
        identity.setText(
                selected.map(item -> item.displayName() + " · " + item.id()).orElse("—"));
        runtime.setText(selected.map(item -> switch (item.runtimeKind()) {
                    case BUNDLE -> "可信进程内扩展包";
                    case PLATFORM -> "JavaClaw 服务平台能力";
                })
                .orElse("—"));
        lifecycle.setText(selected.map(item -> SettingsLabels.extensionState(item.state()))
                .orElse("—"));
        availability.setText(
                selected.map(item -> availability(item.availability())).orElse("—"));
        descriptorRevision.setText(
                selected.map(item -> Long.toString(item.descriptorRevision())).orElse("—"));
        stateRevision.setText(
                selected.map(item -> Long.toString(item.stateRevision())).orElse("—"));
        contributions.setText(selected.map(item -> item.contributionKinds().stream()
                        .map(SettingsLabels::contributionKind)
                        .sorted()
                        .collect(java.util.stream.Collectors.joining(", ")))
                .orElse("—"));
        updatedAt.setText(selected.map(item -> item.updatedAt().toString()).orElse("—"));
    }

    private void renderActions(BuiltinExtensionSettingsState snapshot) {
        Optional<BuiltinExtensionRpcContracts.Status> selected = snapshot.selected();
        boolean pending = snapshot.pending();
        boolean mutable = selected.filter(item -> item.availability() == ExtensionAvailability.OPTIONAL)
                .filter(item -> item.state() == ExtensionState.ENABLED || item.state() == ExtensionState.DISABLED)
                .isPresent();
        refresh.setDisable(pending);
        toggle.setDisable(pending || !mutable);
        toggle.setText(
                selected.filter(item -> item.state() == ExtensionState.ENABLED).isPresent() ? "停用" : "启用");
        if (snapshot.revisionConflict()) {
            conflict.showUnknownActual(selected.map(BuiltinExtensionRpcContracts.Status::stateRevision)
                    .orElse(0L));
        } else {
            conflict.hide();
        }
        showStatus(snapshot);
    }

    private void showStatus(BuiltinExtensionSettingsState snapshot) {
        if (snapshot.pending()) {
            actions.show(ActionState.PENDING, snapshot.message());
        } else if (snapshot.phase() == SettingsLoadState.ERROR) {
            actions.show(ActionState.ERROR, snapshot.message());
        } else {
            actions.show(snapshot.message().isBlank() ? ActionState.IDLE : ActionState.SUCCESS, snapshot.message());
        }
    }

    private void confirmTransition() {
        BuiltinExtensionRpcContracts.Status selected = state.selected().orElseThrow();
        boolean enable = selected.state() != ExtensionState.ENABLED;
        if (enable) {
            presenter.setEnabled(true);
            return;
        }
        Alert dialog = new Alert(Alert.AlertType.CONFIRMATION);
        dialog.setTitle("停用内置能力");
        dialog.setHeaderText("停用 " + selected.displayName());
        dialog.setContentText("新工具、页面、定时器和命令会立即被拒绝；已有数据将保留。");
        PlatformDialogs.style(dialog, this);
        dialog.showAndWait().filter(ButtonType.OK::equals).ifPresent(ignored -> presenter.setEnabled(false));
    }

    private void describeConflict() {
        actions.show(ActionState.ERROR, "服务端启停版本已变化；重新读取后再确认当前状态");
    }

    private Button action(String text, ActionStyle style, Runnable action) {
        Button button = components.action(text, style, ActionSize.COMPACT);
        button.setOnAction(event -> action.run());
        return button;
    }

    private static Label value() {
        Label label = new Label("—");
        label.setWrapText(true);
        label.getStyleClass().add("platform-detail-text");
        return label;
    }

    private static String availability(ExtensionAvailability value) {
        return value == ExtensionAvailability.OPTIONAL ? "可选，可停用" : "必需，只读";
    }
}
