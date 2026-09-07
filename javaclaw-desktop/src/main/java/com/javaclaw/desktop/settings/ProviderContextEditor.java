package com.javaclaw.desktop.settings;

import java.util.Optional;
import java.util.OptionalLong;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

import com.javaclaw.api.ProviderRef;
import com.javaclaw.desktop.component.FormSection;
import com.javaclaw.desktop.component.PlatformComponentFactory;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionSize;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionStyle;

/** 复用设置中心表单与动作样式的逐模型容量编辑器。 */
final class ProviderContextEditor extends VBox {
    private final ProviderContextPresenter presenter;
    private final TextField window = new TextField();
    private final TextField output = new TextField();
    private final Label status = new Label();
    private final Label model = new Label();
    private final Button save;
    private Optional<com.javaclaw.api.ModelContextLimits> rendered = Optional.empty();

    ProviderContextEditor(ProviderContextSettingsGateway gateway, Runnable saved) {
        presenter = new ProviderContextPresenter(gateway, saved);
        FormSection section =
                new FormSection("模型上下文容量", "留空表示模型容量未知；平台运行默认窗口 32768 token、输出 4096 token，不代表模型声明。修改将创建新版本，不改变运行中的任务。");
        window.setId("providerContextWindowTokens");
        output.setId("providerMaximumOutputTokens");
        window.setPromptText("未知");
        output.setPromptText("未知");
        section.addField("上下文窗口（token）", window);
        section.addField("单次输出上限（token）", output);
        save = new PlatformComponentFactory().action("保存模型容量", ActionStyle.SOFT, ActionSize.COMPACT);
        save.setId("providerContextSaveButton");
        save.setOnAction(event -> presenter.save(window.getText(), output.getText()));
        status.getStyleClass().add("sec-hint");
        status.setWrapText(true);
        Button discard = new PlatformComponentFactory().action("放弃容量更改", ActionStyle.GHOST, ActionSize.COMPACT);
        discard.setOnAction(event -> resetFields());
        getChildren().addAll(model, section, new javafx.scene.layout.HBox(8, save, discard), status);
        setSpacing(8);
        presenter.subscribe(this::render);
    }

    void bind(Optional<ProviderRef> provider) {
        if (dirty() && !presenter.state().provider().equals(provider)) {
            warnUnsavedChanges();
            return;
        }
        presenter.bind(provider);
    }

    boolean dirty() {
        return rendered.isPresent()
                && (!window.getText().equals(text(rendered.orElseThrow().contextWindowTokens()))
                        || !output.getText().equals(text(rendered.orElseThrow().maximumOutputTokens())));
    }

    boolean pending() {
        return presenter.state().pending();
    }

    void warnUnsavedChanges() {
        status.setText("请先保存或放弃当前模型的容量更改");
    }

    private void resetFields() {
        window.setText(rendered.map(value -> text(value.contextWindowTokens())).orElse(""));
        output.setText(rendered.map(value -> text(value.maximumOutputTokens())).orElse(""));
    }

    private void render(ProviderContextPresenter.State state) {
        if (!rendered.equals(state.limits())) {
            rendered = state.limits();
            window.setText(state.limits()
                    .map(value -> text(value.contextWindowTokens()))
                    .orElse(""));
            output.setText(state.limits()
                    .map(value -> text(value.maximumOutputTokens()))
                    .orElse(""));
        }
        boolean disabled = state.pending() || state.limits().isEmpty();
        window.setDisable(disabled);
        output.setDisable(disabled);
        save.setDisable(disabled);
        model.setText(state.provider()
                .map(value -> value.model() + " · 版本 " + value.endpointRevision())
                .orElse(""));
        status.setText(state.message());
    }

    private static String text(OptionalLong value) {
        return value.isPresent() ? Long.toString(value.getAsLong()) : "";
    }
}
