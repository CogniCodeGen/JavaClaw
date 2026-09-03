package com.javaclaw.desktop.settings;

import java.util.Objects;
import java.util.Optional;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

import com.javaclaw.api.CredentialMetadata;
import com.javaclaw.desktop.component.AsyncActionBar;
import com.javaclaw.desktop.component.AsyncActionBar.ActionState;
import com.javaclaw.desktop.component.DangerZone;
import com.javaclaw.desktop.component.FormSection;
import com.javaclaw.desktop.component.PlatformComponentFactory;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionSize;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionStyle;

/** 网站页面中由平台拥有的强类型密钥管理分区。 */
final class SiteCredentialSettingsSection {
    private static final String CLEAR_CONFIRMATION = "CLEAR SITE SECRET";

    private final PlatformComponentFactory components = new PlatformComponentFactory();
    private final SiteCredentialSettingsPresenter presenter;
    private final VBox root = new VBox(12);
    private final ComboBox<CredentialMetadata> credentials = new ComboBox<>();
    private final Label reference = value();
    private final Label revision = value();
    private final Label updatedAt = value();
    private final PasswordField secret = new PasswordField();
    private final TextField clearConfirmation = new TextField();
    private final Button refresh;
    private final Button create;
    private final Button rotate;
    private final AsyncActionBar actions;
    private final DangerZone clearZone;
    private SiteCredentialSettingsState state = SiteCredentialSettingsState.initial();
    private boolean rendering;

    SiteCredentialSettingsSection(CoreSettingsGateway gateway, Runnable authorityRefresh) {
        presenter = new SiteCredentialSettingsPresenter(gateway, authorityRefresh);
        refresh = action("刷新元数据", ActionStyle.GHOST, presenter::reload);
        create = action("创建新凭据", ActionStyle.PRIMARY, this::create);
        rotate = action("轮换所选凭据", ActionStyle.SOFT, this::rotate);
        actions = new AsyncActionBar(refresh, rotate, create);
        clearZone = new DangerZone(
                "永久清除网站凭据",
                "清除后密钥不能恢复，引用立即失效；网站绑定不会获得明文或替代值。",
                "永久清除",
                ignored -> CLEAR_CONFIRMATION.equals(clearConfirmation.getText()),
                this::clear);
        configure();
        presenter.subscribe(this::render);
    }

    Node content() {
        return root;
    }

    void activate() {
        presenter.reload();
    }

    boolean dirty() {
        return !secret.getText().isEmpty() || !clearConfirmation.getText().isEmpty();
    }

    boolean pending() {
        return state.phase() == SettingsLoadState.LOADING || state.phase() == SettingsLoadState.SAVING;
    }

    void warnUnsavedChanges() {
        actions.show(ActionState.DIRTY, "密钥或危险确认尚未提交；离页会清空本地输入");
    }

    void discardDraft() {
        secret.clear();
        clearConfirmation.clear();
        updateActions();
    }

    private void configure() {
        configureCredentialChoice();
        secret.setPromptText("输入新密钥；提交后立即清空");
        secret.setAccessibleText("网站密钥临时输入");
        clearConfirmation.setPromptText("精确输入 " + CLEAR_CONFIRMATION);
        clearConfirmation.setAccessibleText("网站密钥永久清除确认");
        secret.textProperty().addListener((ignored, previous, value) -> updateActions());
        clearConfirmation.textProperty().addListener((ignored, previous, value) -> updateActions());

        FormSection catalog = new FormSection("网站 HTTP 凭据", "只显示网站凭据的不透明标识、版本和更新时间；密钥永远不能读取、复制或导出。");
        catalog.addField("凭据", credentials);
        catalog.addField("凭据引用", reference);
        catalog.addField("版本", revision);
        catalog.addField("更新时间", updatedAt);
        catalog.addField("新密钥", secret);
        catalog.addFullWidth(actions);

        FormSection danger = new FormSection("危险操作", "永久清除时会校验当前脱敏版本，随后重新读取服务端状态。");
        danger.addField("确认文本", clearConfirmation);
        danger.addFullWidth(clearZone);
        root.getChildren().addAll(catalog, danger);
        root.getStyleClass().add("platform-page");
    }

    private void configureCredentialChoice() {
        credentials.setMaxWidth(Double.MAX_VALUE);
        credentials.setAccessibleText("网站凭据元数据选择");
        credentials.setCellFactory(
                ignored -> components.detailCell(this::credentialLabel, value -> "更新时间 " + value.updatedAt()));
        credentials.setButtonCell(components.textCell(this::credentialLabel));
        credentials.valueProperty().addListener((ignored, previous, selected) -> {
            if (!rendering && selected != null && !selected.equals(previous)) {
                clearConfirmation.clear();
                presenter.select(selected);
            }
        });
    }

    private void render(SiteCredentialSettingsState snapshot) {
        state = Objects.requireNonNull(snapshot, "snapshot");
        rendering = true;
        try {
            credentials.getItems().setAll(snapshot.credentials());
            credentials.setValue(snapshot.selected().orElse(null));
        } finally {
            rendering = false;
        }
        renderMetadata(snapshot.selected());
        updateActions();
    }

    private void renderMetadata(Optional<CredentialMetadata> selected) {
        reference.setText(selected.map(value -> value.reference().id()).orElse("—"));
        revision.setText(selected.map(value -> Long.toString(value.revision())).orElse("—"));
        updatedAt.setText(selected.map(value -> value.updatedAt().toString()).orElse("—"));
    }

    private void updateActions() {
        boolean pending = pending();
        boolean selected = state.selected().isPresent();
        boolean hasSecret = !secret.getText().isEmpty();
        refresh.setDisable(pending);
        credentials.setDisable(pending || state.credentials().isEmpty());
        create.setDisable(pending || !hasSecret);
        rotate.setDisable(pending || !selected || !hasSecret);
        clearZone.setActionDisabled(pending || !selected || !CLEAR_CONFIRMATION.equals(clearConfirmation.getText()));
        if (pending) {
            actions.show(ActionState.PENDING, state.message());
        } else if (state.phase() == SettingsLoadState.ERROR) {
            actions.show(ActionState.ERROR, state.message());
        } else if (dirty()) {
            actions.show(ActionState.DIRTY, "密钥只会写入密钥库，不会显示在网站文档或日志中");
        } else {
            ActionState result = state.message().isBlank() ? ActionState.IDLE : ActionState.SUCCESS;
            actions.show(result, state.message());
        }
    }

    private void create() {
        char[] value = secret.getText().toCharArray();
        secret.clear();
        presenter.create(value);
    }

    private void rotate() {
        char[] value = secret.getText().toCharArray();
        secret.clear();
        presenter.rotate(value);
    }

    private void clear() {
        clearConfirmation.clear();
        presenter.clear();
    }

    private Button action(String label, ActionStyle style, Runnable operation) {
        Button button = components.action(label, style, ActionSize.NORMAL);
        button.setOnAction(event -> operation.run());
        return button;
    }

    private String credentialLabel(CredentialMetadata value) {
        return value.reference().id() + " · 版本 " + value.revision();
    }

    private static Label value() {
        Label label = new Label("—");
        label.setWrapText(true);
        label.getStyleClass().add("platform-detail-text");
        return label;
    }
}
