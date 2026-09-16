package com.javaclaw.desktop.settings;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.SiteContracts;
import com.javaclaw.builtin.contracts.SiteRegistrationContracts;
import com.javaclaw.desktop.component.FormSection;
import com.javaclaw.desktop.component.PlatformComponentFactory;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionSize;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionStyle;
import com.javaclaw.desktop.component.PlatformDialogs;

/** 添加网站的原生窗口；只展示脱敏候选，密码与登录态从不进入 Desktop 控件或普通 JSON。 */
final class SiteRegistrationDialog {
    private final Dialog<Void> dialog = new Dialog<>();
    private final SiteRegistrationPresenter presenter;
    private final Runnable changed;
    private final TextField address = new TextField();
    private final TextField name = new TextField();
    private final TextField origin = new TextField();
    private final Label current = new Label();
    private final Label status = new Label();
    private final Label origins = new Label();
    private final ComboBox<CredentialChoice> credentials = new ComboBox<>();
    private final Button begin;
    private final Button allow;
    private final Button refresh;
    private final Button finish;
    private final FormSection registration = new FormSection("登记网站", "在新打开的隔离浏览器中登录，完成后一次保存网站和默认账号。");
    private final FormSection access = new FormSection("额外访问来源", "需要其他登录来源时，请核对后手动输入并允许；发现的来源不会自动获得授权。");
    private boolean rendering;
    private boolean nameEdited;
    private boolean choiceEdited;
    private boolean disposed;

    SiteRegistrationDialog(ExtensionSettingsGateway gateway, WorkspaceId workspace,
            Consumer<SiteRegistrationContracts.Completed> completed, Runnable changed) {
        this.changed = Objects.requireNonNull(changed, "changed");
        presenter = new SiteRegistrationPresenter(gateway, workspace, value -> {
            dispose();
            completed.accept(value);
        });
        begin = button("打开隔离浏览器", ActionStyle.PRIMARY, () -> presenter.begin(address.getText()));
        allow = button("允许此来源", ActionStyle.SOFT, () -> presenter.allowOrigin(origin.getText()));
        refresh = button("刷新状态", ActionStyle.GHOST, presenter::refresh);
        finish = button("完成添加", ActionStyle.PRIMARY, this::complete);
        configure();
        presenter.subscribe(this::render);
    }

    void show(Node owner) {
        if (!disposed) {
            PlatformDialogs.style(dialog, owner);
            dialog.show();
            changed.run();
        }
    }

    boolean dirty() {
        return dialog.isShowing() && presenter.state().phase() != SiteRegistrationPresenter.Phase.TERMINAL;
    }

    boolean pending() {
        return dialog.isShowing() && presenter.pending();
    }

    void warnUnsavedChanges() {
        status.setText("请先完成添加，或关闭此窗口取消未完成的登记。");
    }

    /** 原作用域离开后失效轮询，清理尚未提交的浏览器；不以关闭动作重放未知提交。 */
    void dispose() {
        if (!disposed) {
            disposed = true;
            presenter.close();
            dialog.setOnCloseRequest(null);
            dialog.close();
            changed.run();
        }
    }

    private void configure() {
        dialog.setTitle("添加网站地址");
        dialog.setHeaderText("在隔离浏览器中登录并添加网站");
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.CLOSE);
        dialog.getDialogPane().setPrefWidth(640);
        dialog.setResizable(true);
        address.setPromptText("https://example.com/login");
        address.setAccessibleText("添加网站地址");
        name.setAccessibleText("登记网站名称");
        origin.setPromptText("https://login.example.com");
        origin.setAccessibleText("本次登记额外允许的 HTTPS 来源");
        credentials.setAccessibleText("保存的密码候选");
        credentials.setMaxWidth(Double.MAX_VALUE);
        configureChoices();
        for (Label label : List.of(current, status, origins)) {
            label.setWrapText(true);
        }
        registration.addField("网站地址", address);
        registration.addFullWidth(begin);
        registration.addFullWidth(current);
        registration.addField("网站名称", name);
        registration.addField("保存密码", credentials);
        registration.addFullWidth(new javafx.scene.layout.FlowPane(8, 8, refresh, finish));
        registration.addFullWidth(status);
        access.addFullWidth(origins);
        access.addField("额外来源", origin);
        access.addFullWidth(allow);
        dialog.getDialogPane().setContent(new VBox(12, registration, access));
        configureListeners();
    }

    private void configureChoices() {
        credentials.setConverter(new StringConverter<>() {
            @Override
            public String toString(CredentialChoice choice) {
                return choice == null ? "请选择要保存的密码，或仅保存登录态" : choice.label();
            }

            @Override
            public CredentialChoice fromString(String value) {
                throw new UnsupportedOperationException("密码候选只能从当前会话选择");
            }
        });
    }

    private void configureListeners() {
        address.textProperty().addListener((ignored, before, after) -> updateActions());
        origin.textProperty().addListener((ignored, before, after) -> updateActions());
        name.textProperty().addListener((ignored, before, after) -> {
            if (!rendering) {
                nameEdited = true;
            }
            updateActions();
        });
        credentials.valueProperty().addListener((ignored, before, after) -> {
            if (!rendering) {
                choiceEdited = true;
            }
            updateActions();
        });
        dialog.setOnCloseRequest(event -> {
            if (presenter.pending()) {
                event.consume();
            }
        });
        dialog.setOnHidden(event -> {
            presenter.close();
            disposed = true;
            changed.run();
        });
    }

    private void render(SiteRegistrationPresenter.Snapshot snapshot) {
        status.setText(snapshot.message());
        rendering = true;
        snapshot.session().ifPresent(session -> {
            SiteRegistrationContracts.Page page = session.page();
            current.setText(page.uri().map(URI::toString).orElse("等待浏览器页面…")
                    + (page.title().isBlank() ? "" : "\n" + page.title()));
            if (!nameEdited) {
                name.setText(page.title().isBlank() ? page.uri().map(URI::getHost).orElse("") : page.title());
            }
            renderCandidates(page);
            origins.setText("已允许：" + displayOrigins(session.access().allowedOrigins())
                    + "\n等待授权：" + displayOrigins(session.access().pendingOrigins()));
        });
        rendering = false;
        updateActions();
        changed.run();
    }

    private void renderCandidates(SiteRegistrationContracts.Page page) {
        List<CredentialChoice> choices = new ArrayList<>();
        choices.add(new CredentialChoice(Optional.empty(), "只保存登录态（不保存密码）"));
        Optional<URI> currentOrigin = page.uri().map(SiteContracts::originOf);
        page.candidates().stream().filter(candidate -> currentOrigin.filter(candidate.origin()::equals).isPresent())
                .map(candidate -> new CredentialChoice(Optional.of(candidate.id()), candidate.label()))
                .forEach(choices::add);
        if (credentials.getItems().equals(choices)) {
            return;
        }
        CredentialChoice previous = credentials.getValue();
        credentials.getItems().setAll(choices);
        if (choiceEdited && choices.contains(previous)) {
            credentials.setValue(previous);
        } else if (choices.size() <= 2) {
            credentials.setValue(choices.getLast());
        } else {
            credentials.setValue(null);
        }
    }

    private void complete() {
        CredentialChoice selected = credentials.getValue();
        if (!finish.isDisabled() && selected != null) {
            presenter.complete(name.getText(), selected.id());
        }
    }

    private void updateActions() {
        var snapshot = presenter.state();
        boolean editing = snapshot.phase() == SiteRegistrationPresenter.Phase.EDITING;
        boolean retry = snapshot.phase() == SiteRegistrationPresenter.Phase.START_FAILED;
        boolean active = snapshot.phase() == SiteRegistrationPresenter.Phase.ACTIVE && !snapshot.reading();
        begin.setText(retry ? "重试启动" : "打开隔离浏览器");
        begin.setDisable(!(editing || retry) || address.getText().isBlank());
        address.setDisable(!editing);
        name.setDisable(!active);
        credentials.setDisable(!active);
        origin.setDisable(!active);
        allow.setDisable(!active || origin.getText().isBlank());
        refresh.setDisable(snapshot.session().isEmpty() || presenter.pending() || snapshot.reading());
        updateFinish(active);
        dialog.getDialogPane().lookupButton(ButtonType.CLOSE).setDisable(presenter.pending());
    }

    private void updateFinish(boolean active) {
        boolean pageReady = presenter.state().session().filter(session -> session.page().pageRevision() > 0)
                .filter(session -> session.page().uri().isPresent()).isPresent();
        finish.setDisable(!active || !pageReady || name.getText().isBlank() || credentials.getValue() == null);
    }

    private static String displayOrigins(java.util.Set<URI> values) {
        return values.isEmpty() ? "无" : values.stream().map(URI::toString).sorted().collect(java.util.stream.Collectors.joining("、"));
    }

    private static Button button(String label, ActionStyle style, Runnable action) {
        Button button = new PlatformComponentFactory().action(label, style, ActionSize.NORMAL);
        button.setOnAction(ignored -> action.run());
        return button;
    }

    /** @param id 不含输入值的候选身份；空表示只保存登录态 @param label 用户看到的脱敏说明 */
    private record CredentialChoice(Optional<String> id, String label) {}
}
