package com.javaclaw.desktop.settings;

import java.util.Optional;

import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import com.javaclaw.api.AgentProfile;
import com.javaclaw.api.PromptManifestPreview;
import com.javaclaw.api.PromptSourceMetadata;
import com.javaclaw.api.Workspace;
import com.javaclaw.desktop.component.FormSection;
import com.javaclaw.desktop.component.PlatformComponentFactory;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionSize;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionStyle;

/** Agent Profile 页内可复用的 Prompt provenance 只读面板。 */
public final class PromptPreviewPanel {
    private final PlatformComponentFactory components = new PlatformComponentFactory();
    private final PromptPreviewSettingsPresenter presenter;
    private final FormSection content =
            new FormSection("Prompt provenance", "服务端按精确 revision 组装下一 Turn 预览；项目约定、Skill 和 Context 只显示来源元数据，不返回正文。");
    private final ComboBox<Workspace> workspace = new ComboBox<>();
    private final Button preview;
    private final Label status = new Label();
    private final Label identity = new Label("尚未生成预览");
    private final Label digest = new Label("—");
    private final Label tokens = new Label("—");
    private final ListView<PromptSourceMetadata> sources = new ListView<>();
    private final TextArea coreTemplate = readOnlyArea();
    private final TextArea profileInstruction = readOnlyArea();
    private boolean rendering;

    /**
     * 创建面板。
     *
     * @param gateway Prompt 预览 SDK 边界
     */
    public PromptPreviewPanel(PromptPreviewSettingsGateway gateway) {
        presenter = new PromptPreviewSettingsPresenter(gateway);
        preview = components.action("生成预览", ActionStyle.SOFT, ActionSize.COMPACT);
        configureControls();
        buildLayout();
        bindEvents();
        presenter.subscribe(this::render);
    }

    /** @return 可嵌入 Profile 页面表单的根节点 */
    public Node content() {
        return content;
    }

    /** 激活面板并刷新 Workspace 目录。 */
    public void activate() {
        presenter.reloadWorkspaces();
    }

    /**
     * 切换当前权威 Profile；草稿尚未保存时传空。
     *
     * @param profile 当前 Profile
     */
    public void selectProfile(Optional<AgentProfile> profile) {
        presenter.selectProfile(profile);
    }

    private void configureControls() {
        workspace.setPromptText("选择 Workspace");
        workspace.setCellFactory(ignored -> components.detailCell(
                Workspace::name, value -> value.id().value().toString()));
        workspace.setButtonCell(components.textCell(
                value -> value == null ? "" : value.name() + " · " + value.id().value()));
        identity.setWrapText(true);
        digest.setWrapText(true);
        digest.getStyleClass().add("platform-monospace");
        tokens.setWrapText(true);
        sources.setPrefHeight(150);
        sources.setCellFactory(ignored -> components.detailCell(
                source -> source.kind() + " · " + source.sourceId(), PromptPreviewPanel::sourceDetail));
        status.setWrapText(true);
        status.getStyleClass().add("sec-hint");
    }

    private void buildLayout() {
        content.addField("Workspace", workspace);
        content.addFullWidth(new HBox(8, preview, status));
        content.addField("冻结引用", identity);
        content.addField("Manifest SHA-256", digest);
        content.addField("输入 token 估算", tokens);
        content.addField("来源", sources);
        content.addField("Core template", coreTemplate);
        content.addField("Profile instruction", profileInstruction);
        VBox.setVgrow(sources, Priority.ALWAYS);
    }

    private void bindEvents() {
        workspace.valueProperty().addListener((ignored, previous, value) -> {
            if (!rendering) {
                presenter.selectWorkspace(value);
            }
        });
        preview.setOnAction(event -> presenter.preview());
    }

    private void render(PromptPreviewSettingsState state) {
        rendering = true;
        try {
            workspace.setItems(FXCollections.observableArrayList(state.workspaces()));
            workspace.setValue(state.workspace().orElse(null));
            renderPreview(state.preview());
            status.setText(state.message());
            status.getStyleClass().remove("platform-action-error");
            if (state.phase() == SettingsLoadState.ERROR) {
                status.getStyleClass().add("platform-action-error");
            }
            preview.setDisable(state.phase() == SettingsLoadState.LOADING
                    || state.workspace().isEmpty()
                    || state.profile().isEmpty());
        } finally {
            rendering = false;
        }
    }

    private void renderPreview(Optional<PromptManifestPreview> value) {
        PromptManifestPreview current = value.orElse(null);
        if (current == null) {
            identity.setText("尚未生成预览");
            digest.setText("—");
            tokens.setText("—");
            sources.getItems().clear();
            coreTemplate.clear();
            profileInstruction.clear();
            return;
        }
        identity.setText(
                "Profile " + current.profile().id() + "@" + current.profile().revision()
                        + " · Provider " + current.provider().endpointId() + "@"
                        + current.provider().endpointRevision()
                        + " · Permission " + current.permissionProfile().id() + "@"
                        + current.permissionProfile().version());
        digest.setText(current.manifestDigest());
        tokens.setText(current.estimatedInputTokens() + " · " + current.tokenEstimator());
        sources.getItems().setAll(current.sources());
        coreTemplate.setText(current.coreTemplate());
        profileInstruction.setText(current.profileInstruction());
    }

    private static TextArea readOnlyArea() {
        TextArea area = new TextArea();
        area.setEditable(false);
        area.setWrapText(true);
        area.setPrefRowCount(5);
        return area;
    }

    private static String sourceDetail(PromptSourceMetadata source) {
        String revision = source.revision().map(value -> " · " + value).orElse("");
        String digest =
                source.digest().map(value -> " · " + value.substring(0, 12)).orElse(" · 未纳入");
        String warnings = source.warnings().isEmpty() ? "" : " · " + String.join(", ", source.warnings());
        return source.includedBytes() + " bytes" + revision + digest + warnings;
    }
}
