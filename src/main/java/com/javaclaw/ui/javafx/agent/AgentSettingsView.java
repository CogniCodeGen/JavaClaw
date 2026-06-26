package com.javaclaw.ui.javafx.agent;

import com.javaclaw.agent.expert.AgentPromptOptimizer;
import com.javaclaw.agent.expert.CustomAgentConfig;
import com.javaclaw.agent.expert.CustomAgentConfig.CustomAgentDef;
import com.javaclaw.agent.model.ModelFactory;
import com.javaclaw.app.UIHelper;
import com.javaclaw.config.AgentConfig;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 智能体管理设置面板 — 展示内置智能体 + 管理自定义智能体
 *
 * <p>嵌入 SettingsView 的分类面板。左侧列表展示所有智能体（内置只读、自定义可编辑），
 * 右侧编辑区按卡片划分：头部摘要、基本信息、描述、系统提示词、底部操作栏。</p>
 *
 * @author JavaClaw
 */
public class AgentSettingsView {

    private final CustomAgentConfig customConfig = CustomAgentConfig.getInstance();

    /** 配置变更回调（通知 SettingsView 需要重建智能体服务） */
    private Runnable onConfigChanged;

    /** 模型工厂（用于 AI 智能补全提示词；为 null 时按钮自动隐藏） */
    private ModelFactory modelFactory;

    /** Token 用量追踪器（注入后 AI 提示词补全消耗会计入会话统计；可空） */
    private com.javaclaw.agent.TokenTracker tokenTracker;

    // UI 组件
    private VBox agentListBox;
    private StackPane editorArea;
    private Node emptyPanel;
    private Node editorPanel;
    private Label statusLabel;

    // 头部摘要
    private Label headerAvatar;
    private Label headerNameLabel;
    private Label headerSubLabel;
    private Label headerBadge;

    // 编辑表单
    private TextField nameField;
    private TextField toolNameField;
    private TextArea descriptionArea;
    private TextArea sysPromptArea;
    private Spinner<Integer> maxItersSpinner;
    private CheckBox enabledCheck;
    private Button optimizePromptBtn;
    private Button saveBtn;
    private Button deleteBtn;

    // 当前选中
    private String selectedAgentId;
    private boolean isBuiltIn;

    /**
     * 内置智能体概要（仅展示，不可编辑）
     */
    private record BuiltInAgent(String name, String toolName, String description, int maxIters) {}

    public void setOnConfigChanged(Runnable callback) {
        this.onConfigChanged = callback;
    }

    /**
     * 注入模型工厂以启用 AI 智能补全提示词按钮；为 null 时按钮隐藏。
     */
    public void setModelFactory(ModelFactory modelFactory) {
        this.modelFactory = modelFactory;
        if (optimizePromptBtn != null) {
            boolean show = modelFactory != null;
            optimizePromptBtn.setVisible(show);
            optimizePromptBtn.setManaged(show);
        }
    }

    /**
     * 注入 TokenTracker 让提示词优化的模型调用消耗也计入会话统计；可空。
     */
    public void setTokenTracker(com.javaclaw.agent.TokenTracker tokenTracker) {
        this.tokenTracker = tokenTracker;
    }

    /**
     * 构建面板（供 SettingsView 嵌入）
     */
    public Node buildPanel() {
        // ==================== 左侧列表 ====================
        Label listTitle = new Label("智能体列表");
        listTitle.getStyleClass().add("agent-list-title");

        Button addBtn = new Button("+ 添加智能体");
        addBtn.getStyleClass().add("agent-add-btn");
        addBtn.setMaxWidth(Double.MAX_VALUE);
        addBtn.setOnAction(e -> onCreateAgent());

        agentListBox = new VBox(2);
        agentListBox.setPadding(new Insets(4, 0, 4, 0));

        ScrollPane listScroll = new ScrollPane(agentListBox);
        listScroll.setFitToWidth(true);
        listScroll.getStyleClass().add("agent-settings-scroll");
        VBox.setVgrow(listScroll, Priority.ALWAYS);

        VBox leftPane = new VBox(10, listTitle, addBtn, listScroll);
        leftPane.getStyleClass().add("agent-left-pane");
        leftPane.setPadding(new Insets(4, 12, 4, 0));
        leftPane.setMinWidth(200);
        leftPane.setPrefWidth(210);

        // ==================== 右侧编辑区 ====================
        emptyPanel = buildEmptyPanel();
        editorPanel = buildEditorPanel();
        editorPanel.setVisible(false);
        editorPanel.setManaged(false);

        editorArea = new StackPane(emptyPanel, editorPanel);
        editorArea.getStyleClass().add("agent-editor-area");
        HBox.setHgrow(editorArea, Priority.ALWAYS);

        // ==================== 组装 ====================
        HBox mainLayout = new HBox(16, leftPane, editorArea);
        mainLayout.setPadding(new Insets(0));

        // 初始加载列表
        refreshAgentList();

        return mainLayout;
    }

    // ==================== 左侧列表 ====================

    private void refreshAgentList() {
        agentListBox.getChildren().clear();

        // 内置智能体
        Label builtInLabel = new Label("内置智能体");
        builtInLabel.getStyleClass().add("agent-section-label");
        agentListBox.getChildren().add(builtInLabel);

        for (BuiltInAgent agent : getBuiltInAgents()) {
            agentListBox.getChildren().add(createAgentRow(
                    agent.name(), "builtin_" + agent.toolName(), true, true));
        }

        // 自定义智能体
        Label customLabel = new Label("自定义智能体");
        customLabel.getStyleClass().add("agent-section-label");
        agentListBox.getChildren().add(customLabel);

        List<CustomAgentDef> customAgents = customConfig.getAll();
        if (customAgents.isEmpty()) {
            Label emptyHint = new Label("暂无自定义智能体");
            emptyHint.getStyleClass().add("settings-hint");
            emptyHint.setPadding(new Insets(4, 12, 4, 12));
            agentListBox.getChildren().add(emptyHint);
        } else {
            for (CustomAgentDef agent : customAgents) {
                agentListBox.getChildren().add(createAgentRow(
                        agent.name, agent.id, false, agent.enabled));
            }
        }
    }

    private HBox createAgentRow(String name, String id, boolean builtIn, boolean enabled) {
        Label statusDot = new Label(enabled ? "●" : "○");
        statusDot.getStyleClass().add(enabled ? "status-dot-enabled" : "status-dot-disabled");

        Label nameLabel = new Label(name);
        nameLabel.getStyleClass().add("skill-list-name");
        HBox.setHgrow(nameLabel, Priority.ALWAYS);
        nameLabel.setMaxWidth(Double.MAX_VALUE);

        HBox row;
        if (builtIn) {
            Label tag = new Label("内置");
            tag.getStyleClass().add("agent-builtin-tag");
            row = new HBox(8, statusDot, nameLabel, tag);
        } else {
            row = new HBox(8, statusDot, nameLabel);
        }
        row.getStyleClass().add("skill-list-row");
        row.setAlignment(Pos.CENTER_LEFT);

        if (id.equals(selectedAgentId)) {
            row.getStyleClass().add("skill-list-row-selected");
        }

        row.setOnMouseClicked(e -> {
            selectedAgentId = id;
            isBuiltIn = builtIn;
            refreshAgentList();
            if (builtIn) {
                showBuiltInEditor(id.replace("builtin_", ""));
            } else {
                showCustomEditor(id);
            }
        });
        return row;
    }

    // ==================== 右侧面板 ====================

    private Node buildEmptyPanel() {
        Label icon = new Label("⚙");
        icon.getStyleClass().add("agent-empty-icon");

        Label title = new Label("智能体配置");
        title.getStyleClass().add("agent-empty-title");

        Label hint = new Label("从左侧选择一个智能体查看配置\n或点击「+ 添加智能体」创建自定义智能体");
        hint.getStyleClass().addAll("settings-hint", "agent-empty-hint");

        VBox box = new VBox(10, icon, title, hint);
        box.setAlignment(Pos.CENTER);
        return box;
    }

    private Node buildEditorPanel() {
        // ===== 头部摘要卡 =====
        headerAvatar = new Label("🤖");
        headerAvatar.getStyleClass().add("agent-header-avatar");

        headerNameLabel = new Label();
        headerNameLabel.getStyleClass().add("agent-header-name");

        headerBadge = new Label();
        headerBadge.getStyleClass().add("agent-header-badge");

        HBox headerNameRow = new HBox(10, headerNameLabel, headerBadge);
        headerNameRow.setAlignment(Pos.CENTER_LEFT);

        headerSubLabel = new Label();
        headerSubLabel.getStyleClass().add("agent-header-sub");

        VBox headerTextBox = new VBox(4, headerNameRow, headerSubLabel);
        headerTextBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(headerTextBox, Priority.ALWAYS);

        enabledCheck = new CheckBox("启用");
        enabledCheck.getStyleClass().add("settings-checkbox");

        HBox headerCard = new HBox(14, headerAvatar, headerTextBox, enabledCheck);
        headerCard.setAlignment(Pos.CENTER_LEFT);
        headerCard.getStyleClass().add("agent-section-card");

        // ===== 基本信息卡 =====
        Label basicTitle = new Label("基本信息");
        basicTitle.getStyleClass().add("agent-card-title");

        nameField = new TextField();
        nameField.getStyleClass().add("settings-field");
        nameField.setPromptText("例如：Java 编程专家");
        nameField.setMaxWidth(Double.MAX_VALUE);

        toolNameField = new TextField();
        toolNameField.getStyleClass().add("settings-field");
        toolNameField.setPromptText("英文+下划线，如 my_expert");
        toolNameField.setMaxWidth(Double.MAX_VALUE);

        maxItersSpinner = new Spinner<>(1, 30, 1);
        maxItersSpinner.setEditable(true);
        maxItersSpinner.setPrefWidth(120);
        maxItersSpinner.getStyleClass().add("settings-field");

        VBox nameBlock = buildFieldBlock("名称", nameField, null);
        VBox toolNameBlock = buildFieldBlock("工具名",
                toolNameField,
                "编排器调用此智能体时使用的标识，需保证唯一");
        VBox itersBlock = buildFieldBlock("最大迭代",
                maxItersSpinner,
                "智能体每次执行的最大推理轮次");

        VBox basicForm = new VBox(14, nameBlock, toolNameBlock, itersBlock);
        VBox basicCard = new VBox(12, basicTitle, basicForm);
        basicCard.getStyleClass().add("agent-section-card");

        // ===== 描述卡 =====
        Label descTitle = new Label("智能体描述");
        descTitle.getStyleClass().add("agent-card-title");

        Label descHint = new Label("告诉编排器何时调用此智能体，建议简洁概括能力边界");
        descHint.getStyleClass().add("settings-hint");
        descHint.setWrapText(true);

        descriptionArea = new TextArea();
        descriptionArea.getStyleClass().add("settings-field");
        descriptionArea.setPromptText("例如：处理 Java 项目相关的代码编写、调试、重构、依赖管理任务");
        descriptionArea.setPrefRowCount(6);
        descriptionArea.setWrapText(true);

        VBox descCard = new VBox(8, descTitle, descHint, descriptionArea);
        descCard.getStyleClass().add("agent-section-card");

        // ===== 系统提示词卡 =====
        Label promptTitle = new Label("系统提示词");
        promptTitle.getStyleClass().add("agent-card-title");

        Label promptHint = new Label("定义智能体的角色、能力和行为准则");
        promptHint.getStyleClass().add("settings-hint");
        promptHint.setWrapText(true);

        optimizePromptBtn = new Button("✨ AI 补全");
        optimizePromptBtn.getStyleClass().add("agent-optimize-btn");
        optimizePromptBtn.setTooltip(new Tooltip("根据名称和描述调用模型生成系统提示词"));
        optimizePromptBtn.setOnAction(e -> onOptimizePrompt());
        boolean canOptimize = modelFactory != null;
        optimizePromptBtn.setVisible(canOptimize);
        optimizePromptBtn.setManaged(canOptimize);

        Region promptHeaderSpacer = new Region();
        HBox.setHgrow(promptHeaderSpacer, Priority.ALWAYS);
        VBox promptTitleBox = new VBox(2, promptTitle, promptHint);
        promptTitleBox.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(promptTitleBox, Priority.ALWAYS);
        HBox promptHeader = new HBox(8, promptTitleBox, promptHeaderSpacer, optimizePromptBtn);
        promptHeader.setAlignment(Pos.CENTER_LEFT);

        sysPromptArea = new TextArea();
        sysPromptArea.getStyleClass().add("skill-content-editor");
        sysPromptArea.setPromptText("# 角色\n你是一名…\n\n# 能力\n…\n\n# 行为准则\n…");
        sysPromptArea.setPrefRowCount(12);
        sysPromptArea.setWrapText(true);
        VBox.setVgrow(sysPromptArea, Priority.ALWAYS);

        VBox promptCard = new VBox(10, promptHeader, sysPromptArea);
        promptCard.getStyleClass().add("agent-section-card");
        VBox.setVgrow(promptCard, Priority.ALWAYS);

        // ===== 底部操作栏 =====
        statusLabel = new Label();
        statusLabel.getStyleClass().add("settings-hint");

        saveBtn = new Button("保存");
        saveBtn.getStyleClass().add("settings-save-button");
        saveBtn.setOnAction(e -> onSaveAgent());

        deleteBtn = new Button("删除");
        deleteBtn.getStyleClass().add("skill-delete-btn");
        deleteBtn.setOnAction(e -> onDeleteAgent());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox buttonBar = new HBox(10, statusLabel, spacer, deleteBtn, saveBtn);
        buttonBar.setAlignment(Pos.CENTER_LEFT);
        buttonBar.getStyleClass().add("agent-action-bar");

        // ===== 整体内容（可滚动） =====
        VBox content = new VBox(14, headerCard, basicCard, descCard, promptCard);
        content.setPadding(new Insets(2, 2, 2, 2));
        VBox.setVgrow(promptCard, Priority.ALWAYS);

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("agent-settings-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        VBox editor = new VBox(10, scroll, buttonBar);
        editor.setPadding(new Insets(0));

        return editor;
    }

    // ==================== 编辑操作 ====================

    private void showCustomEditor(String id) {
        CustomAgentDef def = customConfig.get(id);
        if (def == null) return;

        // 头部
        headerAvatar.setText("🤖");
        headerNameLabel.setText(def.name == null || def.name.isBlank() ? "未命名智能体" : def.name);
        headerSubLabel.setText("工具名 " + (def.toolName == null ? "" : def.toolName));
        headerBadge.setText("自定义");
        headerBadge.getStyleClass().removeAll("agent-header-badge-builtin", "agent-header-badge-custom");
        headerBadge.getStyleClass().add("agent-header-badge-custom");

        // 表单
        nameField.setText(def.name);
        nameField.setDisable(false);
        toolNameField.setText(def.toolName);
        toolNameField.setDisable(false);
        descriptionArea.setText(def.description);
        descriptionArea.setDisable(false);
        sysPromptArea.setText(def.sysPrompt);
        sysPromptArea.setDisable(false);
        sysPromptArea.setPromptText("# 角色\n你是一名…\n\n# 能力\n…\n\n# 行为准则\n…");
        maxItersSpinner.getValueFactory().setValue(def.maxIters);
        maxItersSpinner.setDisable(false);
        enabledCheck.setSelected(def.enabled);
        enabledCheck.setDisable(false);
        if (optimizePromptBtn != null) {
            optimizePromptBtn.setDisable(false);
        }

        showEditor(false);
        clearStatus();
    }

    private void showBuiltInEditor(String toolName) {
        BuiltInAgent agent = getBuiltInAgents().stream()
                .filter(a -> a.toolName().equals(toolName))
                .findFirst().orElse(null);
        if (agent == null) return;

        // 头部
        headerAvatar.setText("🛠");
        headerNameLabel.setText(agent.name());
        headerSubLabel.setText("工具名 " + agent.toolName());
        headerBadge.setText("内置");
        headerBadge.getStyleClass().removeAll("agent-header-badge-builtin", "agent-header-badge-custom");
        headerBadge.getStyleClass().add("agent-header-badge-builtin");

        // 表单
        nameField.setText(agent.name());
        nameField.setDisable(true);
        toolNameField.setText(agent.toolName());
        toolNameField.setDisable(true);
        descriptionArea.setText(agent.description());
        descriptionArea.setDisable(true);
        sysPromptArea.clear();
        sysPromptArea.setPromptText("内置智能体的系统提示词由系统维护，不可编辑");
        sysPromptArea.setDisable(true);
        maxItersSpinner.getValueFactory().setValue(agent.maxIters());
        maxItersSpinner.setDisable(true);
        enabledCheck.setSelected(true);
        enabledCheck.setDisable(true);
        if (optimizePromptBtn != null) {
            optimizePromptBtn.setDisable(true);
        }

        showEditor(true);
        clearStatus();
    }

    private void showEditor(boolean readOnly) {
        emptyPanel.setVisible(false);
        emptyPanel.setManaged(false);
        editorPanel.setVisible(true);
        editorPanel.setManaged(true);

        // 内置智能体隐藏保存/删除按钮
        if (saveBtn != null) {
            saveBtn.setVisible(!readOnly);
            saveBtn.setManaged(!readOnly);
        }
        if (deleteBtn != null) {
            deleteBtn.setVisible(!readOnly);
            deleteBtn.setManaged(!readOnly);
        }
    }

    private void hideEditor() {
        editorPanel.setVisible(false);
        editorPanel.setManaged(false);
        emptyPanel.setVisible(true);
        emptyPanel.setManaged(true);
    }

    // ==================== CRUD ====================

    private void onCreateAgent() {
        CustomAgentDef def = customConfig.create("新智能体");
        selectedAgentId = def.id;
        isBuiltIn = false;
        refreshAgentList();
        showCustomEditor(def.id);
        Platform.runLater(() -> {
            nameField.requestFocus();
            nameField.selectAll();
        });
    }

    private void onSaveAgent() {
        if (isBuiltIn || selectedAgentId == null) return;

        CustomAgentDef def = customConfig.get(selectedAgentId);
        if (def == null) return;

        // 校验
        String name = nameField.getText().trim();
        if (name.isEmpty()) {
            setStatus("名称不能为空", true);
            return;
        }

        String toolName = toolNameField.getText().trim();
        if (toolName.isEmpty() || !toolName.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
            setStatus("工具名须为英文字母/数字/下划线，且以字母或下划线开头", true);
            return;
        }

        def.name = name;
        def.toolName = toolName;
        def.description = descriptionArea.getText().trim();
        def.sysPrompt = sysPromptArea.getText();
        def.maxIters = maxItersSpinner.getValue();
        def.enabled = enabledCheck.isSelected();

        customConfig.update(def);
        // 刷新头部摘要
        headerNameLabel.setText(name);
        headerSubLabel.setText("工具名 " + toolName);
        refreshAgentList();
        setStatus("已保存", false);

        if (onConfigChanged != null) {
            onConfigChanged.run();
        }
    }

    /**
     * 调用模型基于当前名称、描述和草稿生成/优化系统提示词，结果写回 sysPromptArea。
     * 异步执行，避免阻塞 UI 线程；优化期间按钮禁用并显示进度提示。
     */
    private void onOptimizePrompt() {
        if (isBuiltIn || modelFactory == null) return;

        String name = nameField.getText() == null ? "" : nameField.getText().trim();
        if (name.isEmpty()) {
            setStatus("请先填写智能体名称", true);
            return;
        }

        String description = descriptionArea.getText();
        String draft = sysPromptArea.getText();

        optimizePromptBtn.setDisable(true);
        setStatus("正在调用模型生成提示词...", false);

        ModelFactory mf = modelFactory;
        com.javaclaw.agent.TokenTracker tt = tokenTracker;
        CompletableFuture
                .supplyAsync(() -> new AgentPromptOptimizer(mf.createChatModel(), tt)
                        .optimize(name, description, draft))
                .whenComplete((result, ex) -> Platform.runLater(() -> {
                    optimizePromptBtn.setDisable(false);
                    if (ex != null) {
                        setStatus("优化失败：" + ex.getMessage(), true);
                        return;
                    }
                    if (result == null || result.isBlank()) {
                        setStatus("模型未返回有效内容，请稍后重试", true);
                        return;
                    }
                    sysPromptArea.setText(result);
                    setStatus("已生成提示词，记得点击保存", false);
                }));
    }

    private void onDeleteAgent() {
        if (isBuiltIn || selectedAgentId == null) return;

        CustomAgentDef def = customConfig.get(selectedAgentId);
        if (def == null) return;

        Alert alert = UIHelper.createConfirmAlert("确认删除",
                "删除智能体「" + def.name + "」后不可恢复，确认继续？", null);
        alert.showAndWait().ifPresent(result -> {
            if (result == ButtonType.OK) {
                customConfig.delete(selectedAgentId);
                selectedAgentId = null;
                refreshAgentList();
                hideEditor();

                if (onConfigChanged != null) {
                    onConfigChanged.run();
                }
            }
        });
    }

    // ==================== 内置智能体列表 ====================

    private List<BuiltInAgent> getBuiltInAgents() {
        AgentConfig config = AgentConfig.getInstance();
        List<BuiltInAgent> list = new ArrayList<>();

        list.add(new BuiltInAgent(
                AgentConfig.CODING_AGENT_NAME, "coding_expert",
                AgentConfig.CODING_AGENT_DESCRIPTION, 1));
        list.add(new BuiltInAgent(
                AgentConfig.EVALUATOR_AGENT_NAME, "task_evaluator",
                AgentConfig.EVALUATOR_AGENT_DESCRIPTION, 1));
        list.add(new BuiltInAgent(
                AgentConfig.KNOWLEDGE_AGENT_NAME, "knowledge_expert",
                AgentConfig.KNOWLEDGE_AGENT_DESCRIPTION, 1));
        list.add(new BuiltInAgent(
                AgentConfig.WEB_AGENT_NAME, "web_expert",
                AgentConfig.WEB_AGENT_DESCRIPTION, config.getWebAgentMaxIters()));
        list.add(new BuiltInAgent(
                AgentConfig.EMAIL_AGENT_NAME, "email_expert",
                AgentConfig.EMAIL_AGENT_DESCRIPTION, config.getEmailAgentMaxIters()));
        list.add(new BuiltInAgent(
                AgentConfig.SYSTEM_AGENT_NAME, "system_expert",
                AgentConfig.SYSTEM_AGENT_DESCRIPTION, config.getSystemAgentMaxIters()));
        list.add(new BuiltInAgent(
                AgentConfig.NOTIFICATION_AGENT_NAME, "notification_expert",
                AgentConfig.NOTIFICATION_AGENT_DESCRIPTION, config.getNotificationAgentMaxIters()));

        return list;
    }

    // ==================== 辅助 ====================

    /**
     * 构建一个标签在上、字段在下、可选 hint 在最下的字段块。
     * 字段块横向铺满，hint 强制换行避免被截断。
     */
    private VBox buildFieldBlock(String label, Node field, String hint) {
        Label labelNode = new Label(label);
        labelNode.getStyleClass().add("agent-field-label");

        VBox box = new VBox(6);
        box.getChildren().add(labelNode);
        box.getChildren().add(field);
        if (hint != null && !hint.isBlank()) {
            Label hintNode = new Label(hint);
            hintNode.getStyleClass().add("settings-hint");
            hintNode.setWrapText(true);
            box.getChildren().add(hintNode);
        }
        return box;
    }

    private void setStatus(String text, boolean error) {
        statusLabel.setText(text);
        statusLabel.getStyleClass().removeAll("status-success", "status-error");
        statusLabel.getStyleClass().add(error ? "status-error" : "status-success");
    }

    private void clearStatus() {
        statusLabel.setText("");
        statusLabel.getStyleClass().removeAll("status-success", "status-error");
    }
}
