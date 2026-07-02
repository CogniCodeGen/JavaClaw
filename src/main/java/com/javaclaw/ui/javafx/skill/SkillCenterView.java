package com.javaclaw.ui.javafx.skill;

import com.javaclaw.skill.*;

import com.javaclaw.app.UIHelper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.io.File;
import java.util.List;

/**
 * 技能中心界面（模态对话框）
 *
 * <p>左侧显示技能列表（支持新建、删除），
 * 右侧显示选中技能的 SKILL.md 编辑面板和目录结构信息。</p>
 *
 * @author JavaClaw
 */
public class SkillCenterView {

    private static final Logger log = LoggerFactory.getLogger(SkillCenterView.class);

    private final Stage stage;
    private final SkillManager skillManager;

    /** 左侧技能列表容器 */
    private VBox skillListBox;

    /** 右侧内容区域 */
    private StackPane contentArea;

    /** 当前选中的技能 ID */
    private String selectedSkillId;

    // 右侧编辑面板控件
    private TextField nameField;
    private TextField descriptionField;
    private TextField categoryField;
    private TextField tagsField;
    private TextArea contentEditor;
    private com.javaclaw.ui.javafx.control.ToggleSwitch enabledToggle;
    private Label versionLabel;
    private Label sourceLabel;
    private Label usageLabel;
    private ComboBox<String> historyCombo;
    private Label dirStructureLabel;
    private VBox editorPanel;
    private VBox emptyPanel;
    private Label statusLabel;

    // 脚本检查与测试区控件
    private TitledPane scriptPane;
    private ComboBox<String> scriptCombo;
    private TextArea scriptEditor;
    private TextField scriptArgsField;
    private TextArea scriptOutputArea;
    private Label scriptStatusLabel;
    private Button scriptRunBtn;

    // 待审提案面板
    private VBox proposalsPanel;
    private VBox proposalListBox;
    private Button proposalsBtn;

    // 技能包管理面板
    private VBox bundlesPanel;
    private VBox bundleListBox;
    private TextField bundleNameField;
    private TextField bundleDescField;
    private TextField bundleSkillsField;
    private TextArea bundleInstructionsArea;
    private CheckBox bundleEnabledCheck;
    private Label bundleStatusLabel;
    private String selectedBundleName;

    public SkillCenterView(Stage owner) {
        this.skillManager = SkillManager.getInstance();
        this.stage = new Stage();
        stage.initModality(Modality.WINDOW_MODAL);
        stage.initOwner(owner);
        stage.setTitle("技能中心");
        stage.setResizable(true);
        buildUI();
    }

    private void buildUI() {
        // ==================== 左面板：技能列表 ====================

        Label listTitle = new Label("技能中心");
        listTitle.getStyleClass().add("modal-left-title");
        listTitle.setPadding(new Insets(18, 16, 10, 16));

        // 「＋ 新建 / 导入」一行小按钮
        Button addBtn = new Button("＋ 新建");
        addBtn.getStyleClass().addAll("jc-btn", "jc-btn-soft", "jc-btn-sm");
        addBtn.setMaxWidth(Double.MAX_VALUE);
        addBtn.setOnAction(e -> onCreateSkill());

        // 导入技能按钮（从目录或 zip 安装）
        Button importBtn = new Button("导入");
        importBtn.getStyleClass().addAll("jc-btn", "jc-btn-ghost", "jc-btn-sm");
        importBtn.setMaxWidth(Double.MAX_VALUE);
        importBtn.setTooltip(new Tooltip("从本地目录或 zip 包导入技能（包含 SKILL.md）"));
        importBtn.setOnAction(e -> onImportSkill());

        HBox addRow = new HBox(6, addBtn, importBtn);
        addRow.setPadding(new Insets(0, 10, 8, 10));
        HBox.setHgrow(addBtn, Priority.ALWAYS);
        HBox.setHgrow(importBtn, Priority.ALWAYS);

        // 「待审提案 (N) / 技能包」一行切换小按钮
        proposalsBtn = new Button("待审提案");
        proposalsBtn.getStyleClass().addAll("jc-btn", "jc-btn-ghost", "jc-btn-sm");
        proposalsBtn.setMaxWidth(Double.MAX_VALUE);
        proposalsBtn.setTooltip(new Tooltip("审阅智能体自学习产生的技能创建/修补提案"));
        proposalsBtn.setOnAction(e -> showProposalsPanel());

        // 技能包管理入口
        Button bundlesBtn = new Button("技能包");
        bundlesBtn.getStyleClass().addAll("jc-btn", "jc-btn-ghost", "jc-btn-sm");
        bundlesBtn.setMaxWidth(Double.MAX_VALUE);
        bundlesBtn.setTooltip(new Tooltip("管理技能包（一组配合使用的技能 + 附加指令）"));
        bundlesBtn.setOnAction(e -> showBundlesPanel());

        HBox extraRow = new HBox(6, proposalsBtn, bundlesBtn);
        extraRow.setPadding(new Insets(0, 10, 8, 10));
        HBox.setHgrow(proposalsBtn, Priority.ALWAYS);
        HBox.setHgrow(bundlesBtn, Priority.ALWAYS);

        skillListBox = new VBox(2);
        skillListBox.setPadding(new Insets(2, 8, 2, 8));

        ScrollPane listScroll = new ScrollPane(skillListBox);
        listScroll.setFitToWidth(true);
        listScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        listScroll.getStyleClass().add("skill-left-scroll");
        VBox.setVgrow(listScroll, Priority.ALWAYS);

        // 底部：打开目录按钮 + 路径提示
        Button openDirBtn = new Button("打开技能目录");
        openDirBtn.getStyleClass().addAll("jc-btn", "jc-btn-ghost", "jc-btn-sm");
        openDirBtn.setMaxWidth(Double.MAX_VALUE);
        openDirBtn.setOnAction(e -> openSkillsDirectory());

        Label pathHint = new Label(skillManager.getSkillsDir().toAbsolutePath().toString());
        pathHint.getStyleClass().add("skill-path-hint");
        pathHint.setWrapText(true);

        VBox footBox = new VBox(6, openDirBtn, pathHint);
        footBox.setPadding(new Insets(10, 10, 12, 10));

        Region leftSep = new Region();
        leftSep.setMinHeight(1);
        leftSep.setPrefHeight(1);
        leftSep.setStyle("-fx-background-color: -jc-border;");

        VBox leftPane = new VBox();
        leftPane.getStyleClass().add("modal-left-pane");
        leftPane.setPrefWidth(232);
        leftPane.setMinWidth(200);
        leftPane.getChildren().addAll(
                listTitle, addRow, extraRow, listScroll, leftSep, footBox);

        // ==================== 右内容区：三视图切换 ====================

        contentArea = new StackPane();
        contentArea.getStyleClass().add("modal-content-area");
        contentArea.setPadding(new Insets(22, 26, 16, 26));

        emptyPanel = buildEmptyPanel();
        editorPanel = wrapScrollable(buildEditorPanel());
        editorPanel.setVisible(false);
        editorPanel.setManaged(false);
        proposalsPanel = wrapScrollable(buildProposalsPanel());
        proposalsPanel.setVisible(false);
        proposalsPanel.setManaged(false);
        bundlesPanel = wrapScrollable(buildBundlesPanel());
        bundlesPanel.setVisible(false);
        bundlesPanel.setManaged(false);

        contentArea.getChildren().addAll(emptyPanel, editorPanel, proposalsPanel, bundlesPanel);

        // 待审提案变化时刷新角标（回调可能来自非 FX 线程）
        com.javaclaw.skill.curation.SkillProposalQueue.getInstance().setOnPendingChanged(
                () -> javafx.application.Platform.runLater(this::refreshProposalBadge));
        refreshProposalBadge();

        // ==================== 页脚：右对齐关闭按钮 ====================

        Button closeButton = new Button("关闭");
        closeButton.getStyleClass().addAll("jc-btn", "jc-btn-ghost");
        closeButton.setOnAction(e -> stage.close());

        Region footSpacer = new Region();
        HBox.setHgrow(footSpacer, Priority.ALWAYS);

        HBox bottomBar = new HBox(footSpacer, closeButton);
        bottomBar.getStyleClass().add("modal-foot");
        bottomBar.setAlignment(Pos.CENTER_RIGHT);

        // ==================== 组装主布局 ====================

        VBox rightPane = new VBox();
        rightPane.getChildren().addAll(contentArea, bottomBar);
        VBox.setVgrow(contentArea, Priority.ALWAYS);
        HBox.setHgrow(rightPane, Priority.ALWAYS);

        HBox mainLayout = new HBox();
        mainLayout.getChildren().addAll(leftPane, rightPane);

        Scene scene = new Scene(mainLayout, 920, 650);

        String chatCss = getClass().getResource("/css/chat.css") != null
                ? getClass().getResource("/css/chat.css").toExternalForm()
                : null;
        if (chatCss != null) {
            scene.getStylesheets().add(chatCss);
        }
        // 通用自定义控件样式（ToggleSwitch 等）追加在 chat.css 之后（依赖其 .root 上的 -jc-* 令牌）
        String controlsCss = getClass().getResource("/css/controls.css") != null
                ? getClass().getResource("/css/controls.css").toExternalForm()
                : null;
        if (controlsCss != null) {
            scene.getStylesheets().add(controlsCss);
        }
        // 视图专属样式表追加在 chat.css 之后（依赖其 .root 上定义的 -jc-* 令牌）
        String skillCss = getClass().getResource("/css/skill-center.css") != null
                ? getClass().getResource("/css/skill-center.css").toExternalForm()
                : null;
        if (skillCss != null) {
            scene.getStylesheets().add(skillCss);
        }

        stage.setScene(scene);
        refreshSkillList();
    }

    /**
     * 把内容面板包进透明滚动容器，使长内容可滚动；返回 VBox 以兼容
     * 既有 {@link #showOnly(VBox)} 的可见性切换（滚动容器外再套一层 VBox）。
     */
    private VBox wrapScrollable(VBox content) {
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("skill-content-scroll");
        VBox wrapper = new VBox(scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        return wrapper;
    }

    /**
     * 构建空白提示面板
     */
    private VBox buildEmptyPanel() {
        Label emptyIcon = new Label("\u2699");
        emptyIcon.getStyleClass().add("empty-state-icon");

        Label emptyText = new Label("选择或新建一个技能");
        emptyText.getStyleClass().add("empty-state-text");

        Label emptyHint = new Label(
                "每个技能是一个独立目录，核心文件为 SKILL.md\n"
                + "可选子目录：scripts/  references/  assets/\n\n"
                + "启用后提示词将注入到智能体的系统指令中");
        emptyHint.getStyleClass().addAll("sec-hint", "empty-state-hint");
        emptyHint.setWrapText(true);
        emptyHint.setMaxWidth(320);

        VBox panel = new VBox(12, emptyIcon, emptyText, emptyHint);
        panel.setAlignment(Pos.CENTER);
        return panel;
    }

    /**
     * 构建技能编辑面板
     */
    private VBox buildEditorPanel() {
        Label sectionTitle = new Label("编辑技能");
        sectionTitle.getStyleClass().add("sec-title");

        Label sectionHint = new Label(
                "每个技能是一个独立目录，核心文件为 SKILL.md。启用后提示词注入到智能体系统指令。");
        sectionHint.getStyleClass().add("sec-hint");
        sectionHint.setWrapText(true);

        // ---- 基本信息 ----
        Label basicTitle = new Label("基本信息（SKILL.md Front Matter）");
        basicTitle.getStyleClass().add("grp-title");

        // name / description 全宽（标签在上、输入框在下，与设计稿 Field 一致）
        nameField = new TextField();
        nameField.setPromptText("技能名称");
        nameField.getStyleClass().add("skill-field");
        nameField.setMaxWidth(Double.MAX_VALUE);
        VBox nameCol = labeledField("name", nameField);

        descriptionField = new TextField();
        descriptionField.setPromptText("技能描述（简要说明用途）");
        descriptionField.getStyleClass().add("skill-field");
        descriptionField.setMaxWidth(Double.MAX_VALUE);
        VBox descCol = labeledField("description", descriptionField);

        // category / tags 并排半宽
        categoryField = new TextField();
        categoryField.setPromptText("分类（如：编码/浏览器/系统/办公）");
        categoryField.getStyleClass().add("skill-field");
        categoryField.setMaxWidth(Double.MAX_VALUE);
        VBox catCol = labeledField("category", categoryField);

        tagsField = new TextField();
        tagsField.setPromptText("标签，逗号分隔");
        tagsField.getStyleClass().add("skill-field");
        tagsField.setMaxWidth(Double.MAX_VALUE);
        VBox tagsCol = labeledField("tags", tagsField);

        HBox catTagsRow = new HBox(16, catCol, tagsCol);
        HBox.setHgrow(catCol, Priority.ALWAYS);
        HBox.setHgrow(tagsCol, Priority.ALWAYS);

        // 启用开关行：左侧主文案 + 副文案「路由命中时注入正文」，右侧滑块开关（设计稿 RowToggle）
        Label enabledMain = new Label("启用此技能");
        enabledMain.getStyleClass().add("settings-checkbox");
        Label enabledSub = new Label("路由命中时注入正文");
        enabledSub.getStyleClass().add("skill-toggle-sub");
        VBox enabledTexts = new VBox(2, enabledMain, enabledSub);
        Region enabledSpacer = new Region();
        HBox.setHgrow(enabledSpacer, Priority.ALWAYS);
        enabledToggle = new com.javaclaw.ui.javafx.control.ToggleSwitch();
        HBox enabledRow = new HBox(12, enabledTexts, enabledSpacer, enabledToggle);
        enabledRow.setAlignment(Pos.CENTER_LEFT);
        enabledRow.setPadding(new Insets(10, 0, 0, 0));

        // 版本/来源/使用统计（徽标行：版本 jc-badge-soft、来源 / 统计 jc-badge-stopped）
        versionLabel = new Label();
        versionLabel.getStyleClass().addAll("jc-badge", "jc-badge-soft");
        sourceLabel = new Label();
        sourceLabel.getStyleClass().addAll("jc-badge", "jc-badge-stopped");
        usageLabel = new Label();
        // 统计徽标用等宽字体（设计稿 font-mono）
        usageLabel.getStyleClass().addAll("jc-badge", "jc-badge-stopped", "skill-usage-badge");
        HBox metaRow = new HBox(8, versionLabel, sourceLabel, usageLabel);
        metaRow.setAlignment(Pos.CENTER_LEFT);
        metaRow.setPadding(new Insets(10, 0, 0, 0));

        // 版本历史回滚（内联标签 + 下拉 + 回滚按钮，与设计稿一致）
        Label historyLabel = new Label("版本历史");
        historyLabel.getStyleClass().add("skill-field-label");
        historyCombo = new ComboBox<>();
        historyCombo.setPromptText("历史版本");
        historyCombo.setPrefWidth(140);
        Button rollbackBtn = new Button("回滚到此版本");
        rollbackBtn.getStyleClass().addAll("jc-btn", "jc-btn-ghost", "jc-btn-sm");
        rollbackBtn.setOnAction(e -> onRollback());
        HBox historyRow = new HBox(8, historyLabel, historyCombo, rollbackBtn);
        historyRow.setAlignment(Pos.CENTER_LEFT);
        historyRow.setPadding(new Insets(2, 0, 0, 0));

        VBox basicGrid = new VBox(8,
                nameCol, descCol, catTagsRow,
                enabledRow, metaRow, historyRow);

        // ---- 提示词正文 ----
        Label contentTitle = new Label("提示词正文（SKILL.md 正文部分）");
        contentTitle.getStyleClass().add("grp-title");

        contentEditor = new TextArea();
        contentEditor.setPromptText(
                "在此编写提示词指令...\n\n"
                + "例如：\n"
                + "回答问题时，请始终使用 Markdown 格式。\n"
                + "代码块需要标注语言类型。");
        contentEditor.getStyleClass().add("skill-content-editor");
        contentEditor.setWrapText(true);
        contentEditor.setPrefRowCount(10);
        VBox.setVgrow(contentEditor, Priority.ALWAYS);

        // ---- 脚本检查与测试（scripts/，经 JShellRunner 执行） ----
        scriptPane = buildScriptPane();

        // ---- 目录结构信息 ----
        Label structTitle = new Label("目录结构");
        structTitle.getStyleClass().add("grp-title");

        dirStructureLabel = new Label();
        dirStructureLabel.getStyleClass().add("skill-dir-block");
        dirStructureLabel.setWrapText(true);
        dirStructureLabel.setMaxWidth(Double.MAX_VALUE);

        // ---- 按钮区域 ----
        statusLabel = new Label();
        statusLabel.getStyleClass().add("settings-status");

        Button saveBtn = new Button("保存");
        saveBtn.getStyleClass().addAll("jc-btn", "jc-btn-save");
        saveBtn.setOnAction(e -> onSaveSkill());

        Button openBtn = new Button("打开目录");
        openBtn.getStyleClass().addAll("jc-btn", "jc-btn-ghost");
        openBtn.setOnAction(e -> openSelectedSkillDir());

        Button deleteBtn = new Button("删除");
        deleteBtn.getStyleClass().addAll("jc-btn", "jc-btn-danger");
        deleteBtn.setOnAction(e -> onDeleteSkill());

        Region btnSpacer = new Region();
        HBox.setHgrow(btnSpacer, Priority.ALWAYS);

        HBox buttonBar = new HBox(10, saveBtn, openBtn, btnSpacer, statusLabel, deleteBtn);
        buttonBar.setAlignment(Pos.CENTER_LEFT);

        // ---- 组装 ----
        VBox panel = new VBox(10,
                sectionTitle, sectionHint,
                basicTitle, basicGrid,
                new Separator(),
                contentTitle, contentEditor,
                new Separator(),
                scriptPane,
                new Separator(),
                structTitle, dirStructureLabel,
                new Separator(),
                buttonBar);
        panel.setPadding(new Insets(4, 2, 4, 2));

        return panel;
    }

    // ==================== 脚本检查与测试区 ====================

    /**
     * 构建「脚本检查与测试」折叠区：查看/编辑技能 scripts/ 下的 .jsh/.java 脚本，
     * 经 {@link com.javaclaw.system.JShellRunner} 做结构检查（不执行）或运行测试（独立 JVM 求值）。
     * UI 内点击运行即是用户授权，不再经 ToolConfirmationManager。
     */
    private TitledPane buildScriptPane() {
        scriptCombo = new ComboBox<>();
        scriptCombo.setPromptText("选择脚本");
        scriptCombo.setPrefWidth(200);
        scriptCombo.valueProperty().addListener((obs, o, n) -> loadScriptContent(n));

        Button newScriptBtn = new Button("＋ 新建脚本");
        newScriptBtn.getStyleClass().addAll("jc-btn", "jc-btn-soft", "jc-btn-sm");
        newScriptBtn.setOnAction(e -> onNewScript());

        Button saveScriptBtn = new Button("保存脚本");
        saveScriptBtn.getStyleClass().addAll("jc-btn", "jc-btn-save", "jc-btn-sm");
        saveScriptBtn.setOnAction(e -> onSaveScript());

        Button deleteScriptBtn = new Button("删除脚本");
        deleteScriptBtn.getStyleClass().addAll("jc-btn", "jc-btn-danger", "jc-btn-sm");
        deleteScriptBtn.setOnAction(e -> onDeleteScript());

        HBox fileBar = new HBox(8, scriptCombo, newScriptBtn, saveScriptBtn, deleteScriptBtn);
        fileBar.setAlignment(Pos.CENTER_LEFT);

        scriptEditor = new TextArea();
        scriptEditor.setPromptText(
                "脚本内容（.jsh / .java，JShell 片段语法）\n\n"
                + "可直接使用预绑定变量：\n"
                + "  String SKILL_DIR — 技能目录绝对路径\n"
                + "  String[] ARGS   — 测试参数（下方逗号分隔输入）");
        scriptEditor.getStyleClass().add("skill-content-editor");
        scriptEditor.setWrapText(false);
        scriptEditor.setPrefRowCount(8);

        scriptArgsField = new TextField();
        scriptArgsField.setPromptText("测试参数（ARGS，逗号分隔，可空）");
        scriptArgsField.getStyleClass().add("skill-field");
        HBox.setHgrow(scriptArgsField, Priority.ALWAYS);

        Button checkBtn = new Button("检查");
        checkBtn.getStyleClass().addAll("jc-btn", "jc-btn-ghost", "jc-btn-sm");
        checkBtn.setTooltip(new Tooltip("结构检查（片段切分 + 完整性），不执行任何代码"));
        checkBtn.setOnAction(e -> onCheckScript());

        scriptRunBtn = new Button("▶ 运行测试");
        scriptRunBtn.getStyleClass().addAll("jc-btn", "jc-btn-save", "jc-btn-sm");
        scriptRunBtn.setTooltip(new Tooltip("在独立 JVM 中运行编辑器内的脚本内容（含未保存修改）"));
        scriptRunBtn.setOnAction(e -> onRunScript());

        scriptStatusLabel = new Label();
        scriptStatusLabel.getStyleClass().add("settings-status");

        Label scriptInlineHint = new Label("独立 JVM 求值 · 超时见设置");
        scriptInlineHint.getStyleClass().add("skill-inline-hint");

        HBox testBar = new HBox(8, scriptArgsField, checkBtn, scriptRunBtn, scriptStatusLabel, scriptInlineHint);
        testBar.setAlignment(Pos.CENTER_LEFT);

        scriptOutputArea = new TextArea();
        scriptOutputArea.setEditable(false);
        scriptOutputArea.setWrapText(true);
        scriptOutputArea.setPrefRowCount(6);
        scriptOutputArea.setPromptText("检查 / 运行结果将显示在这里");
        scriptOutputArea.getStyleClass().add("skill-content-editor");

        VBox box = new VBox(8, fileBar, scriptEditor, testBar, scriptOutputArea);
        box.setPadding(new Insets(8, 4, 4, 4));

        TitledPane pane = new TitledPane("脚本检查与测试（scripts/）", box);
        pane.setExpanded(false);
        return pane;
    }

    /** 刷新脚本下拉（选中技能变化时调用） */
    private void refreshScriptList(Skill skill) {
        java.util.List<String> scripts = com.javaclaw.system.JShellTools.listScripts(skill);
        scriptCombo.getItems().setAll(scripts);
        scriptEditor.clear();
        scriptOutputArea.clear();
        scriptStatusLabel.setText("");
        scriptPane.setText("脚本检查与测试（scripts/" + (scripts.isEmpty() ? "，暂无脚本" : "，" + scripts.size() + " 个") + "）");
        if (!scripts.isEmpty()) {
            scriptCombo.getSelectionModel().selectFirst();
        }
    }

    /** 把选中脚本内容载入编辑器 */
    private void loadScriptContent(String scriptName) {
        scriptEditor.clear();
        if (scriptName == null || selectedSkillId == null) return;
        Skill skill = skillManager.getSkill(selectedSkillId);
        if (skill == null) return;
        java.nio.file.Path file = com.javaclaw.system.JShellTools.resolveScript(skill, scriptName);
        if (file == null) return;
        try {
            scriptEditor.setText(java.nio.file.Files.readString(file, java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            scriptStatus("读取脚本失败：" + e.getMessage(), false);
        }
    }

    private void onNewScript() {
        if (selectedSkillId == null) return;
        Skill skill = skillManager.getSkill(selectedSkillId);
        if (skill == null) return;

        TextInputDialog dialog = new TextInputDialog("script.jsh");
        dialog.setTitle("新建脚本");
        dialog.setHeaderText("输入脚本文件名（.jsh 或 .java）");
        dialog.initOwner(stage);
        dialog.showAndWait().ifPresent(name -> {
            String fileName = name.strip();
            String lower = fileName.toLowerCase();
            if (fileName.isEmpty() || (!lower.endsWith(".jsh") && !lower.endsWith(".java"))
                    || fileName.contains("/") || fileName.contains("\\")) {
                scriptStatus("文件名非法：须为 .jsh/.java 且不含路径分隔符", false);
                return;
            }
            try {
                java.nio.file.Path scriptsDir = skill.getDirectory().resolve(Skill.SCRIPTS_DIR);
                java.nio.file.Files.createDirectories(scriptsDir);
                java.nio.file.Path file = scriptsDir.resolve(fileName);
                if (java.nio.file.Files.exists(file)) {
                    scriptStatus("同名脚本已存在", false);
                    return;
                }
                java.nio.file.Files.writeString(file,
                        "// " + fileName + " — 可用预绑定变量：SKILL_DIR（技能目录）、ARGS（参数数组）\n",
                        java.nio.charset.StandardCharsets.UTF_8);
                refreshScriptList(skill);
                scriptCombo.getSelectionModel().select(fileName);
                updateDirStructure(skill);
                scriptStatus("已创建 " + fileName, true);
            } catch (Exception e) {
                scriptStatus("创建失败：" + e.getMessage(), false);
            }
        });
    }

    private void onSaveScript() {
        if (selectedSkillId == null) return;
        Skill skill = skillManager.getSkill(selectedSkillId);
        String scriptName = scriptCombo.getValue();
        if (skill == null || scriptName == null) {
            scriptStatus("请先选择脚本", false);
            return;
        }
        java.nio.file.Path file = com.javaclaw.system.JShellTools.resolveScript(skill, scriptName);
        if (file == null) {
            scriptStatus("脚本不存在", false);
            return;
        }
        try {
            java.nio.file.Files.writeString(file, scriptEditor.getText(),
                    java.nio.charset.StandardCharsets.UTF_8);
            scriptStatus("已保存", true);
        } catch (Exception e) {
            scriptStatus("保存失败：" + e.getMessage(), false);
        }
    }

    private void onDeleteScript() {
        if (selectedSkillId == null) return;
        Skill skill = skillManager.getSkill(selectedSkillId);
        String scriptName = scriptCombo.getValue();
        if (skill == null || scriptName == null) return;

        Alert alert = UIHelper.createConfirmAlert("确认删除",
                "确定删除脚本「" + scriptName + "」吗？此操作不可撤销。", stage);
        alert.showAndWait().ifPresent(result -> {
            if (result == ButtonType.OK) {
                java.nio.file.Path file = com.javaclaw.system.JShellTools.resolveScript(skill, scriptName);
                try {
                    if (file != null) {
                        java.nio.file.Files.delete(file);
                    }
                    refreshScriptList(skill);
                    updateDirStructure(skill);
                    scriptStatus("已删除 " + scriptName, true);
                } catch (Exception e) {
                    scriptStatus("删除失败：" + e.getMessage(), false);
                }
            }
        });
    }

    /** 结构检查（不执行代码），结果显示到输出区 */
    private void onCheckScript() {
        String code = scriptEditor.getText();
        if (code == null || code.isBlank()) {
            scriptStatus("脚本内容为空", false);
            return;
        }
        scriptOutputArea.setText(String.join("\n", com.javaclaw.system.JShellRunner.check(code)));
        scriptStatus("检查完成", true);
    }

    /** 在后台线程运行编辑器内的脚本内容（独立 JVM 求值），结果回填输出区 */
    private void onRunScript() {
        if (selectedSkillId == null) return;
        Skill skill = skillManager.getSkill(selectedSkillId);
        String code = scriptEditor.getText();
        if (skill == null || code == null || code.isBlank()) {
            scriptStatus("脚本内容为空", false);
            return;
        }

        scriptRunBtn.setDisable(true);
        scriptStatus("运行中…", true);
        scriptOutputArea.clear();
        String args = scriptArgsField.getText();
        int timeout = com.javaclaw.config.AgentConfig.getInstance().getJshellExecTimeoutSeconds();

        Thread runner = new Thread(() -> {
            com.javaclaw.system.JShellRunner.ExecResult result =
                    com.javaclaw.system.JShellRunner.run(code,
                            com.javaclaw.system.JShellTools.buildPreamble(skill, args), timeout);
            javafx.application.Platform.runLater(() -> {
                scriptRunBtn.setDisable(false);
                StringBuilder sb = new StringBuilder();
                if (result.timedOut()) {
                    sb.append("⏱ 执行超时（").append(timeout).append("s）已中止\n");
                }
                if (!result.output().isBlank()) {
                    sb.append("[输出]\n").append(result.output().stripTrailing()).append("\n");
                }
                if (!result.lastValue().isBlank()) {
                    sb.append("[最后表达式值] ").append(result.lastValue()).append("\n");
                }
                if (!result.problems().isEmpty()) {
                    sb.append("[诊断]\n");
                    for (String problem : result.problems()) {
                        sb.append("- ").append(problem).append("\n");
                    }
                }
                if (sb.isEmpty()) {
                    sb.append("（执行完成，无输出）");
                }
                scriptOutputArea.setText(sb.toString());
                scriptStatus(result.success() ? "运行成功" : (result.timedOut() ? "超时" : "运行有错误"),
                        result.success());
            });
        }, "skill-script-test");
        runner.setDaemon(true);
        runner.start();
    }

    private void scriptStatus(String text, boolean success) {
        scriptStatusLabel.setText(text);
        scriptStatusLabel.getStyleClass().removeAll("status-success", "status-error");
        scriptStatusLabel.getStyleClass().add(success ? "status-success" : "status-error");
    }

    // ==================== 技能列表 ====================

    private void refreshSkillList() {
        skillListBox.getChildren().clear();
        List<Skill> allSkills = skillManager.getAllSkills();

        if (allSkills.isEmpty()) {
            Label empty = new Label("暂无技能");
            empty.getStyleClass().add("skill-path-hint");
            empty.setPadding(new Insets(20, 0, 0, 12));
            skillListBox.getChildren().add(empty);
            return;
        }

        for (Skill skill : allSkills) {
            skillListBox.getChildren().add(createSkillRow(skill));
        }
    }

    private HBox createSkillRow(Skill skill) {
        Label statusDot = new Label(skill.isEnabled() ? "\u25CF" : "\u25CB");
        statusDot.getStyleClass().add(skill.isEnabled() ? "skill-dot-on" : "skill-dot-off");

        // \u81EA\u5B66\u4E60\u5FBD\u6807\uFF1A\u667A\u80FD\u4F53\u521B\u5EFA\u7684\u6280\u80FD\u5728\u5217\u8868\u4E2D\u53EF\u8FA8\u8BC6
        Label nameLabel = new Label(
                (skill.getSource() == SkillSource.AGENT ? "\uD83E\uDD16 " : "") + skill.getName());
        nameLabel.getStyleClass().add("skill-nav-name");
        nameLabel.setMaxWidth(150);
        StringBuilder tip = new StringBuilder();
        if (skill.getDescription() != null && !skill.getDescription().isEmpty()) {
            tip.append(skill.getDescription());
        }
        tip.append(tip.isEmpty() ? "" : "\n").append("v").append(skill.getVersion())
                .append(" \u00B7 ").append(skill.getSource().getDisplayName());
        if (!skill.getTags().isEmpty()) {
            tip.append(" \u00B7 ").append(String.join("/", skill.getTags()));
        }
        nameLabel.setTooltip(new Tooltip(tip.toString()));

        // \u590D\u7528\u901A\u7528\u5DE6\u5BFC\u822A\u6761\u76EE\u6837\u5F0F\uFF08\u7559\u767D/\u5706\u89D2/\u624B\u578B/\u9009\u4E2D\u9AD8\u4EAE\uFF09
        HBox row = new HBox(8, statusDot, nameLabel);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().addAll("modal-nav-btn", "skill-nav-row");
        row.setUserData(skill.getId());

        if (skill.getId().equals(selectedSkillId)) {
            row.getStyleClass().add("modal-nav-btn-selected");
        }

        row.setOnMouseClicked(e -> {
            selectedSkillId = skill.getId();
            refreshSkillList();
            showSkillEditor(skill);
        });

        return row;
    }

    // ==================== 编辑面板 ====================

    /** 在 contentArea 的四个面板间切换，只显示指定面板 */
    private void showOnly(VBox target) {
        for (VBox panel : new VBox[]{emptyPanel, editorPanel, proposalsPanel, bundlesPanel}) {
            boolean show = panel == target;
            panel.setVisible(show);
            panel.setManaged(show);
        }
    }

    private void showSkillEditor(Skill skill) {
        showOnly(editorPanel);

        nameField.setText(skill.getName());
        descriptionField.setText(skill.getDescription() != null ? skill.getDescription() : "");
        categoryField.setText(skill.getCategory());
        tagsField.setText(String.join(", ", skill.getTags()));
        contentEditor.setText(skill.getContent() != null ? skill.getContent() : "");
        enabledToggle.setSelected(skill.isEnabled());
        statusLabel.setText("");

        // 只读元数据：版本 / 来源（自学习徽标）/ 使用统计
        versionLabel.setText("v" + skill.getVersion());
        sourceLabel.setText("来源：" + skill.getSource().getDisplayName()
                + (skill.getSource() == SkillSource.AGENT ? " 🤖" : "")
                + (skill.isUserModified() ? "（已被用户修改）" : ""));
        var stat = com.javaclaw.skill.SkillUsageTracker.getInstance().peek(skill.getName());
        if (stat == null || (stat.routeHits.get() == 0 && stat.reads.get() == 0 && stat.samples() == 0)) {
            usageLabel.setText("尚无使用统计");
        } else {
            String rate = stat.successRate() < 0 ? "—"
                    : Math.round(stat.successRate() * 100) + "%";
            usageLabel.setText("命中 " + stat.routeHits.get() + " · 读取 " + stat.reads.get() + " · 成功率 " + rate);
        }

        // 版本历史下拉
        historyCombo.getItems().setAll(skillManager.listHistory(skill.getId()));
        historyCombo.getSelectionModel().clearSelection();

        // 脚本检查与测试区
        refreshScriptList(skill);

        // 更新目录结构显示
        updateDirStructure(skill);
    }

    /** 回滚选中技能到历史版本 */
    private void onRollback() {
        if (selectedSkillId == null) return;
        String version = historyCombo.getValue();
        if (version == null || version.isBlank()) {
            statusLabel.setText("请先选择历史版本");
            statusLabel.getStyleClass().removeAll("status-success", "status-error");
            statusLabel.getStyleClass().add("status-error");
            return;
        }
        Skill skill = skillManager.getSkill(selectedSkillId);
        if (skill == null) return;

        Alert alert = UIHelper.createConfirmAlert("确认回滚",
                "确定把技能「" + skill.getName() + "」回滚到 v" + version + " 吗？\n当前版本会先归档，可再次回滚。", stage);
        alert.showAndWait().ifPresent(result -> {
            if (result == ButtonType.OK) {
                boolean ok = skillManager.rollback(selectedSkillId, version);
                statusLabel.setText(ok ? "已回滚到 v" + version : "回滚失败");
                statusLabel.getStyleClass().removeAll("status-success", "status-error");
                statusLabel.getStyleClass().add(ok ? "status-success" : "status-error");
                if (ok) {
                    Skill restored = skillManager.getSkill(selectedSkillId);
                    if (restored != null) showSkillEditor(restored);
                    refreshSkillList();
                }
            }
        });
    }

    private void updateDirStructure(Skill skill) {
        StringBuilder sb = new StringBuilder();
        sb.append(skill.getId()).append("/\n");
        sb.append("  \u251C\u2500 SKILL.md");
        if (skill.hasScripts()) {
            sb.append("\n  \u251C\u2500 scripts/");
        }
        if (skill.hasReferences()) {
            sb.append("\n  \u251C\u2500 references/");
        }
        if (skill.hasAssets()) {
            sb.append("\n  \u251C\u2500 assets/");
        }
        sb.append("\n\n可在技能目录中手动添加 scripts/、references/、assets/ 子目录");
        dirStructureLabel.setText(sb.toString());
    }

    private void showEmptyPanel() {
        showOnly(emptyPanel);
        selectedSkillId = null;
    }

    // ==================== 待审提案面板 ====================

    private VBox buildProposalsPanel() {
        Label title = new Label("待审提案");
        title.getStyleClass().add("sec-title");

        Label hint = new Label("智能体自学习产生的技能变更提案（skill.evolution.mode=suggest 时入队）。"
                + "采纳后真正落盘；拒绝后同样的变更进入冷却期不再提案。");
        hint.getStyleClass().add("sec-hint");
        hint.setWrapText(true);

        proposalListBox = new VBox(12);
        proposalListBox.setPadding(new Insets(2, 2, 8, 2));

        VBox panel = new VBox(8, title, hint, proposalListBox);
        panel.setPadding(new Insets(4, 2, 4, 2));
        return panel;
    }

    private void showProposalsPanel() {
        selectedSkillId = null;
        refreshSkillList();
        refreshProposalList();
        showOnly(proposalsPanel);
    }

    private void refreshProposalList() {
        proposalListBox.getChildren().clear();
        var queue = com.javaclaw.skill.curation.SkillProposalQueue.getInstance();
        var pending = queue.pending();
        if (pending.isEmpty()) {
            Label empty = new Label("暂无待审提案");
            empty.getStyleClass().add("sec-hint");
            empty.setPadding(new Insets(16, 0, 0, 4));
            proposalListBox.getChildren().add(empty);
            return;
        }
        for (var proposal : pending) {
            proposalListBox.getChildren().add(createProposalCard(proposal));
        }
    }

    private VBox createProposalCard(com.javaclaw.skill.curation.SkillProposal proposal) {
        var req = proposal.request;

        // 卡片头：动作徽标 + 🤖（提案均来自智能体自学习）+ 技能名 + 右对齐时间戳
        boolean isCreate = "create".equalsIgnoreCase(req.action);
        Label actionBadge = new Label("[" + req.action + "]");
        actionBadge.getStyleClass().addAll("jc-badge", isCreate ? "jc-badge-running" : "jc-badge-indigo");

        Label skillTitle = new Label("🤖 " + req.skillName);
        skillTitle.getStyleClass().add("skill-card-title");

        Region headSpacer = new Region();
        HBox.setHgrow(headSpacer, Priority.ALWAYS);

        Label time = new Label(new java.text.SimpleDateFormat("MM-dd HH:mm")
                .format(new java.util.Date(proposal.createdAt)));
        time.getStyleClass().add("skill-card-time");

        HBox header = new HBox(8, actionBadge, skillTitle, headSpacer, time);
        header.setAlignment(Pos.CENTER_LEFT);

        Label reason = new Label("理由：" + (req.reason == null ? "（无）" : req.reason));
        reason.getStyleClass().add("sec-hint");
        reason.setWrapText(true);

        TextArea preview = new TextArea(req.previewText());
        preview.setEditable(false);
        preview.setWrapText(true);
        preview.setPrefRowCount(6);
        preview.getStyleClass().add("skill-preview-block");

        Button approveBtn = new Button("采纳");
        approveBtn.getStyleClass().addAll("jc-btn", "jc-btn-save", "jc-btn-sm");
        Button rejectBtn = new Button("拒绝");
        rejectBtn.getStyleClass().addAll("jc-btn", "jc-btn-danger", "jc-btn-sm");
        Label cardStatus = new Label();
        cardStatus.getStyleClass().add("settings-status");

        approveBtn.setOnAction(e -> {
            String error = com.javaclaw.skill.curation.SkillProposalQueue.getInstance().approve(proposal.id);
            if (error != null) {
                cardStatus.setText("采纳失败：" + error);
                cardStatus.getStyleClass().removeAll("status-success", "status-error");
                cardStatus.getStyleClass().add("status-error");
            } else {
                refreshProposalList();
                refreshSkillList();
            }
        });
        rejectBtn.setOnAction(e -> {
            com.javaclaw.skill.curation.SkillProposalQueue.getInstance().reject(proposal.id);
            refreshProposalList();
        });

        HBox actions = new HBox(10, approveBtn, rejectBtn, cardStatus);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(8, header, reason, preview, actions);
        // 覆盖用户修改过的技能：高亮警示
        if (req.userModifiedWarning) {
            Label warning = new Label("⚠ 该提案将覆盖你修改过的技能，请仔细核对变更内容");
            warning.getStyleClass().add("skill-warn");
            warning.setWrapText(true);
            card.getChildren().add(1, warning);
        }
        card.getStyleClass().add("skill-card");
        return card;
    }

    /** 刷新左侧「待审提案」按钮角标 */
    private void refreshProposalBadge() {
        int count = com.javaclaw.skill.curation.SkillProposalQueue.getInstance().pendingCount();
        proposalsBtn.setText(count > 0 ? "待审提案 (" + count + ")" : "待审提案");
        // 面板正在展示时同步刷新列表
        if (proposalsPanel.isVisible()) {
            refreshProposalList();
        }
    }

    // ==================== 技能包管理面板 ====================

    private VBox buildBundlesPanel() {
        Label title = new Label("技能包管理");
        title.getStyleClass().add("sec-title");

        Label hint = new Label("技能包是一组配合使用的技能（按技能名引用）+ 可选附加指令；"
                + "路由命中包时整包注入，包内缺失的技能自动跳过。");
        hint.getStyleClass().add("sec-hint");
        hint.setWrapText(true);

        Button newBundleBtn = new Button("＋ 新建包");
        newBundleBtn.getStyleClass().addAll("jc-btn", "jc-btn-soft", "jc-btn-sm");
        newBundleBtn.setOnAction(e -> {
            selectedBundleName = null;
            bundleNameField.setText("");
            bundleDescField.setText("");
            bundleSkillsField.setText("");
            bundleInstructionsArea.setText("");
            bundleEnabledCheck.setSelected(true);
            bundleStatusLabel.setText("");
        });
        HBox newBundleRow = new HBox(newBundleBtn);
        newBundleRow.setAlignment(Pos.CENTER_LEFT);

        // 包列表：圆角描边卡片容器
        bundleListBox = new VBox(2);
        bundleListBox.getStyleClass().add("skill-card");
        bundleListBox.setPadding(new Insets(6));

        // 编辑表单分组标题
        Label editTitle = new Label("编辑包");
        editTitle.getStyleClass().add("grp-title");

        // 编辑表单
        bundleNameField = new TextField();
        bundleNameField.setPromptText("包名");
        bundleNameField.getStyleClass().add("skill-field");
        bundleDescField = new TextField();
        bundleDescField.setPromptText("包描述：一句话说明何时该用这个包");
        bundleDescField.getStyleClass().add("skill-field");
        bundleSkillsField = new TextField();
        bundleSkillsField.setPromptText("包内技能名，逗号分隔（按技能 name 引用）");
        bundleSkillsField.getStyleClass().add("skill-field");
        bundleInstructionsArea = new TextArea();
        bundleInstructionsArea.setPromptText("附加指令（可选）：加载包时拼在技能正文之后");
        bundleInstructionsArea.setPrefRowCount(3);
        bundleInstructionsArea.setWrapText(true);
        bundleInstructionsArea.getStyleClass().add("skill-content-editor");
        bundleEnabledCheck = new CheckBox("启用此包");
        bundleEnabledCheck.getStyleClass().add("settings-checkbox");

        bundleNameField.setMaxWidth(Double.MAX_VALUE);
        bundleDescField.setMaxWidth(Double.MAX_VALUE);
        bundleSkillsField.setMaxWidth(Double.MAX_VALUE);
        bundleInstructionsArea.setMaxWidth(Double.MAX_VALUE);

        // 标签在上、控件在下（与设计稿 Field 一致）；skills 字段附 hint
        VBox bundleNameCol = labeledField("name", bundleNameField);
        VBox bundleDescCol = labeledField("description", bundleDescField);
        VBox bundleSkillsCol = labeledField("skills", bundleSkillsField);
        Label skillsHint = new Label("按技能 name 引用，逗号分隔");
        skillsHint.getStyleClass().add("skill-inline-hint");
        bundleSkillsCol.getChildren().add(skillsHint);
        VBox bundleInstrCol = labeledField("附加指令", bundleInstructionsArea);

        VBox form = new VBox(8,
                bundleNameCol, bundleDescCol, bundleSkillsCol, bundleInstrCol, bundleEnabledCheck);

        bundleStatusLabel = new Label();
        bundleStatusLabel.getStyleClass().add("settings-status");

        Button saveBundleBtn = new Button("保存包");
        saveBundleBtn.getStyleClass().addAll("jc-btn", "jc-btn-save");
        saveBundleBtn.setOnAction(e -> onSaveBundle());
        Button deleteBundleBtn = new Button("删除包");
        deleteBundleBtn.getStyleClass().addAll("jc-btn", "jc-btn-danger");
        deleteBundleBtn.setOnAction(e -> onDeleteBundle());
        HBox bundleActions = new HBox(10, saveBundleBtn, deleteBundleBtn, bundleStatusLabel);
        bundleActions.setAlignment(Pos.CENTER_LEFT);

        VBox panel = new VBox(10, title, hint,
                newBundleRow, bundleListBox, editTitle, form, bundleActions);
        panel.setPadding(new Insets(4, 2, 4, 2));
        return panel;
    }

    private void showBundlesPanel() {
        selectedSkillId = null;
        refreshSkillList();
        refreshBundleList();
        showOnly(bundlesPanel);
    }

    private void refreshBundleList() {
        bundleListBox.getChildren().clear();
        List<SkillBundle> bundles = skillManager.getBundles();
        if (bundles.isEmpty()) {
            Label empty = new Label("暂无技能包");
            empty.getStyleClass().add("sec-hint");
            empty.setPadding(new Insets(8, 0, 8, 8));
            bundleListBox.getChildren().add(empty);
            return;
        }
        for (SkillBundle bundle : bundles) {
            Label dot = new Label(bundle.enabled ? "●" : "○");
            dot.getStyleClass().add(bundle.enabled ? "skill-dot-on" : "skill-dot-off");

            Label name = new Label(bundle.name);
            name.getStyleClass().add("skill-card-title");
            Label sub = new Label(bundle.skills.isEmpty()
                    ? "（暂无技能）" : String.join(" · ", bundle.skills));
            sub.getStyleClass().add("skill-bundle-sub");
            VBox texts = new VBox(2, name, sub);

            Region rowSpacer = new Region();
            HBox.setHgrow(rowSpacer, Priority.ALWAYS);

            Label stateBadge = new Label(bundle.enabled ? "启用" : "停用");
            stateBadge.getStyleClass().addAll("jc-badge", bundle.enabled ? "jc-badge-running" : "jc-badge-stopped");

            HBox row = new HBox(10, dot, texts, rowSpacer, stateBadge);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("modal-nav-btn");
            if (bundle.name.equals(selectedBundleName)) {
                row.getStyleClass().add("modal-nav-btn-selected");
            }
            row.setOnMouseClicked(e -> {
                selectedBundleName = bundle.name;
                bundleNameField.setText(bundle.name);
                bundleDescField.setText(bundle.description == null ? "" : bundle.description);
                bundleSkillsField.setText(String.join(", ", bundle.skills));
                bundleInstructionsArea.setText(bundle.extraInstructions == null ? "" : bundle.extraInstructions);
                bundleEnabledCheck.setSelected(bundle.enabled);
                bundleStatusLabel.setText("");
                refreshBundleList();
            });
            bundleListBox.getChildren().add(row);
        }
    }

    private void onSaveBundle() {
        String name = bundleNameField.getText().strip();
        if (name.isEmpty()) {
            bundleStatusLabel.setText("包名不能为空");
            bundleStatusLabel.getStyleClass().removeAll("status-success", "status-error");
            bundleStatusLabel.getStyleClass().add("status-error");
            return;
        }
        List<String> skillNames = java.util.Arrays.stream(bundleSkillsField.getText().split("[,，]"))
                .map(String::strip).filter(s -> !s.isEmpty()).toList();

        List<SkillBundle> bundles = new java.util.ArrayList<>(skillManager.getBundles());
        // 编辑既有包（按选中名定位）或新建
        bundles.removeIf(b -> b.name.equals(selectedBundleName) || b.name.equals(name));
        bundles.add(new SkillBundle(name, bundleDescField.getText().strip(), skillNames,
                bundleInstructionsArea.getText(), bundleEnabledCheck.isSelected()));
        skillManager.saveBundles(bundles);
        selectedBundleName = name;
        refreshBundleList();
        bundleStatusLabel.setText("已保存");
        bundleStatusLabel.getStyleClass().removeAll("status-success", "status-error");
        bundleStatusLabel.getStyleClass().add("status-success");
    }

    private void onDeleteBundle() {
        if (selectedBundleName == null) return;
        List<SkillBundle> bundles = new java.util.ArrayList<>(skillManager.getBundles());
        bundles.removeIf(b -> b.name.equals(selectedBundleName));
        skillManager.saveBundles(bundles);
        selectedBundleName = null;
        bundleNameField.setText("");
        bundleDescField.setText("");
        bundleSkillsField.setText("");
        bundleInstructionsArea.setText("");
        refreshBundleList();
        bundleStatusLabel.setText("已删除");
    }

    // ==================== 事件处理 ====================

    private void onCreateSkill() {
        Skill skill = skillManager.createSkill("新技能", "", "", true);
        selectedSkillId = skill.getId();
        refreshSkillList();
        showSkillEditor(skill);
        nameField.requestFocus();
        nameField.selectAll();
        log.info("创建新技能: {}", skill.getId());
    }

    /**
     * 导入技能 — 用户选择本地目录或 zip 文件；检测 scripts/ 并确认后安装
     */
    private void onImportSkill() {
        // 弹出选择框：目录 or zip
        javafx.scene.control.Alert choice = new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.CONFIRMATION,
                "选择导入来源：\n\n选择「目录」导入文件夹中的技能，\n选择「Zip」从 .zip 文件导入。",
                new javafx.scene.control.ButtonType("目录"),
                new javafx.scene.control.ButtonType("Zip"),
                new javafx.scene.control.ButtonType("取消", javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE));
        choice.setHeaderText("导入技能");
        com.javaclaw.app.UIHelper.styleAlert(choice);
        java.util.Optional<javafx.scene.control.ButtonType> r = choice.showAndWait();
        if (r.isEmpty() || "取消".equals(r.get().getText())) return;

        SkillInstaller.InstallResult result;
        if ("目录".equals(r.get().getText())) {
            javafx.stage.DirectoryChooser dc = new javafx.stage.DirectoryChooser();
            dc.setTitle("选择包含 SKILL.md 的目录");
            java.io.File dir = dc.showDialog(stage);
            if (dir == null) return;

            // 扫描脚本并请求确认
            java.util.List<String> scripts = SkillInstaller.listScripts(dir.toPath());
            if (!scripts.isEmpty()) {
                if (!confirmScriptsRisk(scripts)) return;
            }
            result = SkillInstaller.installFromDirectory(dir.toPath(), null);
        } else {
            javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
            fc.setTitle("选择技能 Zip 文件");
            fc.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("Zip (*.zip)", "*.zip"));
            java.io.File zip = fc.showOpenDialog(stage);
            if (zip == null) return;
            result = SkillInstaller.installFromZip(zip.toPath(), null);
        }

        // 显示结果
        javafx.scene.control.Alert done = new javafx.scene.control.Alert(
                result.ok() ? javafx.scene.control.Alert.AlertType.INFORMATION
                             : javafx.scene.control.Alert.AlertType.WARNING,
                result.message() + (result.installedDir() != null
                        ? "\n安装路径: " + result.installedDir() : ""),
                javafx.scene.control.ButtonType.OK);
        done.setHeaderText(result.ok() ? "导入成功" : "导入失败");
        com.javaclaw.app.UIHelper.styleAlert(done);
        done.showAndWait();

        if (result.ok()) {
            refreshSkillList();
        }
    }

    /** 当检测到脚本文件时，弹窗展示清单并要求用户明确确认风险 */
    private boolean confirmScriptsRisk(java.util.List<String> scripts) {
        String list = String.join("\n", scripts);
        javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.WARNING,
                "检测到 scripts/ 目录下的脚本文件：\n\n" + list
                        + "\n\n这些脚本在技能被使用时可能被执行。请确认你信任该技能来源。",
                new javafx.scene.control.ButtonType("我了解风险并继续"),
                new javafx.scene.control.ButtonType("取消", javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE));
        alert.setHeaderText("安全提示");
        com.javaclaw.app.UIHelper.styleAlert(alert);
        java.util.Optional<javafx.scene.control.ButtonType> r = alert.showAndWait();
        return r.isPresent() && r.get().getText().startsWith("我了解");
    }

    private void onSaveSkill() {
        if (selectedSkillId == null) return;

        Skill skill = skillManager.getSkill(selectedSkillId);
        if (skill == null) return;

        String name = nameField.getText().trim();
        if (name.isEmpty()) {
            statusLabel.setText("名称不能为空");
            statusLabel.getStyleClass().removeAll("status-success", "status-error");
            statusLabel.getStyleClass().add("status-error");
            return;
        }

        // 变更前归档旧版本（保证可回滚），保存后修订位 +1
        boolean contentChanged = !contentEditor.getText().equals(skill.getContent() == null ? "" : skill.getContent());
        if (contentChanged) {
            skillManager.archiveVersion(skill);
            skill.setVersion(SkillManager.bumpVersion(skill.getVersion(), SkillManager.BumpLevel.PATCH));
            // 用户手动改过正文：置 user-modified 保护位，agent 此后不得静默覆盖
            skill.setUserModified(true);
        }
        skill.setName(name);
        skill.setDescription(descriptionField.getText().trim());
        skill.setCategory(categoryField.getText().trim());
        skill.setTags(java.util.Arrays.stream(tagsField.getText().split("[,，]"))
                .map(String::strip).filter(s -> !s.isEmpty()).toList());
        skill.setContent(contentEditor.getText());
        skill.setEnabled(enabledToggle.isSelected());

        skillManager.updateSkill(skill);
        refreshSkillList();
        showSkillEditor(skill);

        statusLabel.setText("已保存");
        statusLabel.getStyleClass().removeAll("status-success", "status-error");
        statusLabel.getStyleClass().add("status-success");
        log.info("保存技能: {} ({})", skill.getName(), skill.getId());
    }

    private void onDeleteSkill() {
        if (selectedSkillId == null) return;

        Skill skill = skillManager.getSkill(selectedSkillId);
        if (skill == null) return;

        Alert alert = UIHelper.createConfirmAlert("确认删除",
                "确定要删除技能「" + skill.getName() + "」吗？\n将删除整个 "
                + skill.getId() + "/ 目录，此操作不可撤销。", stage);

        alert.showAndWait().ifPresent(result -> {
            if (result == ButtonType.OK) {
                skillManager.deleteSkill(selectedSkillId);
                showEmptyPanel();
                refreshSkillList();
                log.info("删除技能: {}", selectedSkillId);
            }
        });
    }

    /**
     * 在系统文件管理器中打开 skills 根目录
     */
    private void openSkillsDirectory() {
        openDirectory(skillManager.getSkillsDir().toFile());
    }

    /**
     * 在系统文件管理器中打开选中技能的目录
     */
    private void openSelectedSkillDir() {
        if (selectedSkillId == null) return;
        Skill skill = skillManager.getSkill(selectedSkillId);
        if (skill != null && skill.getDirectory() != null) {
            openDirectory(skill.getDirectory().toFile());
        }
    }

    private void openDirectory(File dir) {
        try {
            if (Desktop.isDesktopSupported() && dir.exists()) {
                Desktop.getDesktop().open(dir);
            }
        } catch (Exception e) {
            log.warn("打开目录失败: {}", dir, e);
        }
    }

    public void show() {
        skillManager.reload();
        refreshSkillList();
        if (selectedSkillId != null) {
            Skill skill = skillManager.getSkill(selectedSkillId);
            if (skill != null) {
                showSkillEditor(skill);
            } else {
                showEmptyPanel();
            }
        }
        stage.showAndWait();
    }

    // ==================== UI 辅助 ====================

    /** 标签在上、控件在下的字段列（对应设计稿 Field 组件） */
    private VBox labeledField(String label, javafx.scene.control.Control control) {
        Label lab = new Label(label);
        lab.getStyleClass().add("skill-field-label");
        VBox col = new VBox(6, lab, control);
        col.setFillWidth(true);
        return col;
    }
}
