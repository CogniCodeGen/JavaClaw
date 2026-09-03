package com.javaclaw.desktop.settings;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import com.javaclaw.api.McpEndpoint;
import com.javaclaw.api.McpEndpointState;
import com.javaclaw.api.McpPromptDescriptor;
import com.javaclaw.api.McpPromptResult;
import com.javaclaw.api.McpResourceDescriptor;
import com.javaclaw.api.McpResourceReadResult;
import com.javaclaw.api.McpTransport;
import com.javaclaw.desktop.component.FormSection;
import com.javaclaw.desktop.component.PlatformComponentFactory;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionSize;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionStyle;

/** MCP 设置页中显式读取资源与提示词的可复用操作面板。 */
final class McpExternalDataPanel extends VBox {
    private static final int MAXIMUM_PREVIEW = 12_000;

    private final McpExternalDataPresenter presenter;
    private final ListView<McpResourceDescriptor> resources = new ListView<>();
    private final ListView<McpPromptDescriptor> prompts = new ListView<>();
    private final TextArea arguments = new TextArea();
    private final TextArea preview = new TextArea();
    private final Label feedback = new Label();
    private final Button loadResources;
    private final Button nextResources;
    private final Button readResource;
    private final Button loadPrompts;
    private final Button nextPrompts;
    private final Button getPrompt;
    private McpExternalDataState state = McpExternalDataState.initial();

    McpExternalDataPanel(McpSettingsGateway gateway) {
        PlatformComponentFactory components = new PlatformComponentFactory();
        presenter = new McpExternalDataPresenter(gateway);
        loadResources = action(components, "读取资源", ActionStyle.SOFT);
        nextResources = action(components, "下一页", ActionStyle.GHOST);
        readResource = action(components, "读取所选内容", ActionStyle.PRIMARY);
        loadPrompts = action(components, "读取提示词", ActionStyle.SOFT);
        nextPrompts = action(components, "下一页", ActionStyle.GHOST);
        getPrompt = action(components, "展开所选提示词", ActionStyle.PRIMARY);
        bindActions();
        configureLists(components);
        configureText();
        getChildren().addAll(resourceSection(), promptSection(), previewSection());
        setSpacing(12);
        getStyleClass().add("platform-page");
        presenter.subscribe(this::render);
    }

    void select(Optional<McpEndpoint> endpoint) {
        presenter.select(endpoint);
    }

    private void bindActions() {
        loadResources.setOnAction(event -> presenter.loadResources(false));
        nextResources.setOnAction(event -> presenter.loadResources(true));
        readResource.setOnAction(
                event -> presenter.readResource(resources.getSelectionModel().getSelectedItem()));
        loadPrompts.setOnAction(event -> presenter.loadPrompts(false));
        nextPrompts.setOnAction(event -> presenter.loadPrompts(true));
        getPrompt.setOnAction(event -> getSelectedPrompt());
    }

    private void configureLists(PlatformComponentFactory components) {
        resources.setPrefHeight(150);
        resources.setPlaceholder(new Label("尚未读取资源"));
        resources.setCellFactory(ignored ->
                components.detailCell(value -> value.title().orElse(value.name()), McpResourceDescriptor::uri));
        resources.getSelectionModel().selectedItemProperty().addListener(ignored -> updateActions());
        prompts.setPrefHeight(150);
        prompts.setPlaceholder(new Label("尚未读取提示词"));
        prompts.setCellFactory(ignored -> components.detailCell(
                value -> value.title().orElse(value.name()),
                value -> value.arguments().size() + " 个参数"));
        prompts.getSelectionModel().selectedItemProperty().addListener(ignored -> updateActions());
    }

    private void configureText() {
        arguments.setPromptText("每行填写 name=value；只发送显式参数");
        arguments.setPrefRowCount(3);
        arguments.setAccessibleText("MCP 提示词参数");
        preview.setEditable(false);
        preview.setWrapText(true);
        preview.setPrefRowCount(8);
        preview.setAccessibleText("MCP 外部数据预览");
        feedback.setWrapText(true);
        feedback.getStyleClass().add("sec-hint");
    }

    private FormSection resourceSection() {
        FormSection section = new FormSection("资源", "仅在用户点击后读取；内容不会自动写入提示词、任务内容或系统上下文。");
        section.addFullWidth(new HBox(8, loadResources, nextResources, readResource));
        section.addFullWidth(resources);
        return section;
    }

    private FormSection promptSection() {
        FormSection section = new FormSection("提示词", "模板与消息始终是外部数据；平台不会把它们提升为系统指令。");
        section.addFullWidth(new HBox(8, loadPrompts, nextPrompts, getPrompt));
        section.addField("参数", arguments);
        section.addFullWidth(prompts);
        return section;
    }

    private FormSection previewSection() {
        FormSection section = new FormSection("外部数据预览", "二进制内容只显示基本信息，避免把编码数据误当成可读文本。");
        section.addFullWidth(preview);
        section.addFullWidth(feedback);
        return section;
    }

    private void getSelectedPrompt() {
        try {
            presenter.getPrompt(prompts.getSelectionModel().getSelectedItem(), parseArguments(arguments.getText()));
        } catch (IllegalArgumentException failure) {
            feedback.setText(failure.getMessage());
        }
    }

    private void render(McpExternalDataState snapshot) {
        state = Objects.requireNonNull(snapshot, "snapshot");
        resources.getItems().setAll(snapshot.resources());
        prompts.getItems().setAll(snapshot.prompts());
        preview.setText(snapshot.resource()
                .map(McpExternalDataPanel::resourcePreview)
                .orElseGet(() -> snapshot.prompt()
                        .map(McpExternalDataPanel::promptPreview)
                        .orElse("")));
        feedback.setText(snapshot.message());
        updateActions();
    }

    private void updateActions() {
        boolean available = state.endpoint()
                .filter(value -> value.state() == McpEndpointState.ENABLED)
                .filter(value -> value.spec().transport() == McpTransport.STREAMABLE_HTTPS)
                .isPresent();
        boolean busy = state.phase() == SettingsLoadState.LOADING;
        loadResources.setDisable(!available || busy);
        nextResources.setDisable(!available || busy || state.resourceCursor().isEmpty());
        readResource.setDisable(
                !available || busy || resources.getSelectionModel().getSelectedItem() == null);
        loadPrompts.setDisable(!available || busy);
        nextPrompts.setDisable(!available || busy || state.promptCursor().isEmpty());
        getPrompt.setDisable(!available || busy || prompts.getSelectionModel().getSelectedItem() == null);
    }

    private static String resourcePreview(McpResourceReadResult result) {
        StringBuilder value = new StringBuilder();
        result.contents().forEach(content -> {
            value.append(content.uri()).append('\n');
            content.mimeType()
                    .ifPresent(type -> value.append("MIME: ").append(type).append('\n'));
            content.text().ifPresent(text -> value.append(text).append('\n'));
            content.blobBase64()
                    .ifPresent(blob ->
                            value.append("二进制内容（Base64）：").append(blob.length()).append(" 字符\n"));
        });
        return truncate(value.toString());
    }

    private static String promptPreview(McpPromptResult result) {
        StringBuilder value = new StringBuilder();
        result.description().ifPresent(description -> value.append(description).append("\n\n"));
        result.messages()
                .forEach(message -> value.append(SettingsLabels.mcpSamplingRole(message.role()))
                        .append(": ")
                        .append(message.content().json())
                        .append('\n'));
        return truncate(value.toString());
    }

    private static Map<String, String> parseArguments(String text) {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        for (String line : Objects.requireNonNull(text, "text").split("\\R", -1)) {
            if (line.isBlank()) {
                continue;
            }
            int separator = line.indexOf('=');
            if (separator < 1) {
                throw new IllegalArgumentException("提示词参数必须使用 name=value 格式");
            }
            String previous = values.putIfAbsent(line.substring(0, separator).strip(), line.substring(separator + 1));
            if (previous != null || values.size() > 32) {
                throw new IllegalArgumentException("提示词参数重复或超过 32 个");
            }
        }
        return Map.copyOf(values);
    }

    private static String truncate(String value) {
        return value.length() <= MAXIMUM_PREVIEW ? value : value.substring(0, MAXIMUM_PREVIEW) + "\n…已截断";
    }

    private static Button action(PlatformComponentFactory components, String text, ActionStyle style) {
        return components.action(text, style, ActionSize.NORMAL);
    }
}
