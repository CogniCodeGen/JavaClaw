package com.javaclaw.desktop.settings;

import java.util.Optional;

import javafx.scene.Node;
import javafx.scene.control.Button;
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

/** 智能体方案页内可复用的提示词来源只读面板。 */
public final class PromptPreviewPanel {
    private final PlatformComponentFactory components = new PlatformComponentFactory();
    private final PromptPreviewSettingsPresenter presenter;
    private final FormSection content = new FormSection("提示词来源", "服务端按精确版本组装下一个任务的预览。项目约定、技能和扩展上下文只显示来源信息，不显示正文。");
    private final Button preview;
    private final Label status = new Label();
    private final Label identity = new Label("尚未生成预览");
    private final Label digest = new Label("—");
    private final Label tokens = new Label("—");
    private final ListView<PromptSourceMetadata> sources = new ListView<>();
    private final TextArea coreTemplate = readOnlyArea();
    private final TextArea profileInstruction = readOnlyArea();
    private Optional<Workspace> scopedWorkspace = Optional.empty();

    /**
     * 创建面板。
     *
     * @param gateway 提示词预览 SDK 边界
     */
    public PromptPreviewPanel(PromptPreviewSettingsGateway gateway) {
        presenter = new PromptPreviewSettingsPresenter(gateway);
        preview = components.action("生成预览", ActionStyle.SOFT, ActionSize.COMPACT);
        configureControls();
        buildLayout();
        bindEvents();
        presenter.subscribe(this::render);
    }

    /** @return 可嵌入智能体方案页面表单的根节点 */
    public Node content() {
        return content;
    }

    /** 激活面板并刷新当前固定 Workspace。 */
    public void activate() {
        scopedWorkspace.ifPresent(presenter::selectWorkspace);
    }

    /**
     * 固定提示词预览的 Workspace 作用域。
     *
     * @param workspace 设置中心作用域
     */
    public void workspaceChanged(Optional<Workspace> workspace) {
        Optional<Workspace> checked = java.util.Objects.requireNonNull(workspace, "workspace");
        if (scopedWorkspace.equals(checked)) {
            return;
        }
        scopedWorkspace = checked;
        presenter.selectWorkspace(checked.orElse(null));
    }

    /**
     * 切换当前权威智能体方案；草稿尚未保存时传空。
     *
     * @param profile 当前智能体方案
     */
    public void selectProfile(Optional<AgentProfile> profile) {
        presenter.selectProfile(profile);
    }

    private void configureControls() {
        identity.setWrapText(true);
        digest.setWrapText(true);
        digest.getStyleClass().add("platform-monospace");
        tokens.setWrapText(true);
        sources.setPrefHeight(150);
        sources.setCellFactory(ignored -> components.detailCell(
                source -> SettingsLabels.promptSourceKind(source.kind()) + " · " + source.sourceId(),
                PromptPreviewPanel::sourceDetail));
        status.setWrapText(true);
        status.getStyleClass().add("sec-hint");
    }

    private void buildLayout() {
        content.addFullWidth(new HBox(8, preview, status));
        content.addField("冻结引用", identity);
        content.addField("清单指纹（SHA-256）", digest);
        content.addField("输入令牌估算", tokens);
        content.addField("来源", sources);
        content.addField("内置系统提示词", coreTemplate);
        content.addField("智能体方案提示词", profileInstruction);
        VBox.setVgrow(sources, Priority.ALWAYS);
    }

    private void bindEvents() {
        preview.setOnAction(event -> presenter.preview());
    }

    private void render(PromptPreviewSettingsState state) {
        renderPreview(state.preview());
        status.setText(state.message());
        status.getStyleClass().remove("platform-action-error");
        if (state.phase() == SettingsLoadState.ERROR) {
            status.getStyleClass().add("platform-action-error");
        }
        preview.setDisable(state.phase() == SettingsLoadState.LOADING
                || scopedWorkspace.isEmpty()
                || state.workspace().isEmpty()
                || state.profile().isEmpty());
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
                "智能体方案 " + current.profile().id() + "@" + current.profile().revision()
                        + " · 模型服务 " + current.provider().endpointId() + "@"
                        + current.provider().endpointRevision()
                        + " · 权限方案 " + current.permissionProfile().id() + "@"
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
        return source.includedBytes() + " 字节" + revision + digest + warnings;
    }
}
