package com.javaclaw.chat;

import com.javaclaw.agent.expert.KnowledgeExpert;
import com.javaclaw.agent.expert.KnowledgeExpert.Scope;
import com.javaclaw.agent.model.ToolResponse;
import com.javaclaw.app.UIHelper;
import com.javaclaw.config.AgentConfig;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.List;

/**
 * 知识库管理界面（模态对话框）
 *
 * <p>支持全局知识库和工作区知识库的文档导入、查看、删除管理。
 * 全局知识库在所有工作区共享，工作区知识库仅当前工作区可用。</p>
 *
 * @author JavaClaw
 */
public class KnowledgeBaseView {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseView.class);

    private final Stage stage;
    private final KnowledgeExpert knowledgeExpert;

    /** 配置变更回调（用于重建智能体服务） */
    private Runnable onConfigChanged;

    /** 文档列表 */
    private ListView<String> docListView;
    private ObservableList<String> docItems;

    /** 文本导入区域 */
    private TextArea textImportArea;
    private TextField textTitleField;

    /** 导入目标选择 */
    private ComboBox<String> scopeCombo;

    /** 状态提示 */
    private Label statusLabel;
    private Label statsLabel;

    /** 进度条 */
    private ProgressBar progressBar;
    private Label progressDetailLabel;

    /** 按钮 */
    private Button importFileBtn;
    private Button importFolderBtn;
    private Button importTextBtn;
    private Button deleteBtn;

    public KnowledgeBaseView(Stage owner, KnowledgeExpert knowledgeExpert) {
        this.knowledgeExpert = knowledgeExpert;
        this.stage = new Stage();
        stage.initModality(Modality.WINDOW_MODAL);
        stage.initOwner(owner);
        stage.setTitle("知识库管理");
        stage.setResizable(true);
        buildUI();
    }

    private void buildUI() {
        boolean ragEnabled = knowledgeExpert.isRagEnabled();
        boolean configEnabled = AgentConfig.getInstance().isRagEnabled();

        // ==================== 顶部头栏 ====================

        Label headerIcon = new Label("\uD83D\uDCDA");
        headerIcon.setStyle("-fx-font-size: 22px;");

        Label titleLabel = new Label("知识库管理");
        titleLabel.getStyleClass().add("kb-header-title");

        // 状态徽标
        Label ragBadge = new Label();
        ragBadge.getStyleClass().add("kb-status-badge");
        if (ragEnabled) {
            ragBadge.setText("RAG 已启用");
            ragBadge.getStyleClass().add("kb-badge-success");
        } else if (configEnabled) {
            ragBadge.setText("初始化失败");
            ragBadge.getStyleClass().add("kb-badge-warning");
        } else {
            ragBadge.setText("未启用");
            ragBadge.getStyleClass().add("kb-badge-error");
        }

        HBox titleRow = new HBox(10, headerIcon, titleLabel, ragBadge);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        // 状态描述
        Label ragDescLabel = null;
        if (!ragEnabled) {
            String desc = configEnabled
                    ? "RAG 配置已开启但初始化失败，请检查设置 → 知识库中的嵌入模型配置"
                    : "在设置 → 知识库中开启 RAG 并配置嵌入模型，即可使用知识库功能";
            ragDescLabel = new Label(desc);
            ragDescLabel.getStyleClass().add("kb-header-desc");
            ragDescLabel.setWrapText(true);
        }

        statusLabel = new Label();
        statusLabel.getStyleClass().add("settings-status");

        VBox headerBox = new VBox(8);
        headerBox.getChildren().add(titleRow);
        if (ragDescLabel != null) headerBox.getChildren().add(ragDescLabel);
        headerBox.getStyleClass().add("kb-header");

        // ==================== 一键配置引导面板（RAG 未启用时显示） ====================

        VBox setupGuideBox = null;
        if (!ragEnabled && !configEnabled) {
            Label guideIcon = new Label("\u26A1");
            guideIcon.setStyle("-fx-font-size: 18px;");
            Label guideTitle = new Label("快速开启知识库");
            guideTitle.getStyleClass().add("kb-guide-card-title");
            HBox guideTitleRow = new HBox(8, guideIcon, guideTitle);
            guideTitleRow.setAlignment(Pos.CENTER_LEFT);

            Label guideDesc = new Label(
                    "知识库（RAG）可让智能体在对话中自动检索你导入的文档内容，提高回答的准确性和专业性。\n" +
                    "点击下方按钮即可根据当前模型提供商（" + AgentConfig.getInstance().getProviderType() +
                    "）自动配置嵌入模型。");
            guideDesc.setWrapText(true);
            guideDesc.getStyleClass().add("kb-guide-card-desc");

            Button quickSetupBtn = new Button("一键配置");
            quickSetupBtn.getStyleClass().add("kb-guide-setup-btn");
            quickSetupBtn.setOnAction(e -> onQuickSetup());

            Label quickSetupHint = new Label("配置完成后将自动重建智能体服务，届时请重新打开知识库管理");
            quickSetupHint.getStyleClass().add("settings-hint");

            setupGuideBox = new VBox(12, guideTitleRow, guideDesc, quickSetupBtn, quickSetupHint);
            setupGuideBox.getStyleClass().add("kb-guide-card");
        }

        // ==================== 进度条（初始隐藏） ====================

        progressBar = new ProgressBar(0);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setPrefHeight(6);
        progressBar.setVisible(false);
        progressBar.setManaged(false);
        progressBar.getStyleClass().add("kb-progress-bar");
        HBox.setHgrow(progressBar, Priority.ALWAYS);

        progressDetailLabel = new Label();
        progressDetailLabel.getStyleClass().add("settings-hint");
        progressDetailLabel.setVisible(false);
        progressDetailLabel.setManaged(false);

        VBox progressBox = new VBox(6, progressBar, progressDetailLabel);
        progressBox.setMaxWidth(Double.MAX_VALUE);

        // ==================== 导入目标选择 ====================

        Label scopeLabel = new Label("导入到");
        scopeLabel.getStyleClass().add("kb-field-label");
        scopeCombo = new ComboBox<>();
        scopeCombo.getItems().addAll("工作区知识库", "全局知识库");
        scopeCombo.getSelectionModel().selectFirst();
        scopeCombo.getStyleClass().add("settings-combo");
        scopeCombo.setDisable(!ragEnabled);

        Label scopeHint = new Label("全局知识库在所有工作区共享，工作区知识库仅当前工作区可用");
        scopeHint.getStyleClass().add("settings-hint");
        scopeHint.setWrapText(true);

        HBox scopeRow = new HBox(10, scopeLabel, scopeCombo);
        scopeRow.setAlignment(Pos.CENTER_LEFT);

        VBox scopeCard = new VBox(8, scopeRow, scopeHint);
        scopeCard.getStyleClass().add("kb-card");

        // ==================== 文件导入卡片 ====================

        Label fileIcon = new Label("\uD83D\uDCC1");
        fileIcon.setStyle("-fx-font-size: 15px;");
        Label fileImportTitle = new Label("导入文件");
        fileImportTitle.getStyleClass().add("kb-card-title");
        HBox fileTitleRow = new HBox(8, fileIcon, fileImportTitle);
        fileTitleRow.setAlignment(Pos.CENTER_LEFT);

        Label fileHint = new Label("支持 TXT、MD、PDF 格式，文件将自动分块并生成向量嵌入");
        fileHint.getStyleClass().add("settings-hint");
        fileHint.setWrapText(true);

        importFileBtn = new Button("选择文件");
        importFileBtn.getStyleClass().add("kb-import-btn");
        importFileBtn.setDisable(!ragEnabled);
        importFileBtn.setOnAction(e -> onImportFiles());

        importFolderBtn = new Button("批量导入目录");
        importFolderBtn.getStyleClass().add("kb-import-btn-secondary");
        importFolderBtn.setDisable(!ragEnabled);
        importFolderBtn.setOnAction(e -> onImportFolder());

        HBox fileButtonRow = new HBox(10, importFileBtn, importFolderBtn);
        fileButtonRow.setAlignment(Pos.CENTER_LEFT);

        VBox fileCard = new VBox(10, fileTitleRow, fileHint, fileButtonRow, progressBox);
        fileCard.getStyleClass().add("kb-card");

        // ==================== 文本导入卡片 ====================

        Label textIcon = new Label("\u270F\uFE0F");
        textIcon.setStyle("-fx-font-size: 15px;");
        Label textImportTitle = new Label("导入文本");
        textImportTitle.getStyleClass().add("kb-card-title");
        HBox textTitleRow = new HBox(8, textIcon, textImportTitle);
        textTitleRow.setAlignment(Pos.CENTER_LEFT);

        textTitleField = new TextField();
        textTitleField.setPromptText("文档标题（可选）");
        textTitleField.getStyleClass().add("kb-text-field");
        textTitleField.setDisable(!ragEnabled);

        textImportArea = new TextArea();
        textImportArea.setPromptText("在此粘贴要导入的文本内容...");
        textImportArea.setPrefHeight(100);
        textImportArea.setWrapText(true);
        textImportArea.getStyleClass().add("kb-text-area");
        textImportArea.setDisable(!ragEnabled);

        importTextBtn = new Button("导入文本");
        importTextBtn.getStyleClass().add("kb-import-btn");
        importTextBtn.setDisable(!ragEnabled);
        importTextBtn.setOnAction(e -> onImportText());

        VBox textCard = new VBox(10, textTitleRow, textTitleField, textImportArea, importTextBtn);
        textCard.getStyleClass().add("kb-card");

        // ==================== 文档列表卡片 ====================

        Label docIcon = new Label("\uD83D\uDCCB");
        docIcon.setStyle("-fx-font-size: 15px;");
        Label docListTitle = new Label("已导入文档");
        docListTitle.getStyleClass().add("kb-card-title");
        HBox docTitleRow = new HBox(8, docIcon, docListTitle);
        docTitleRow.setAlignment(Pos.CENTER_LEFT);

        statsLabel = new Label();
        statsLabel.getStyleClass().add("kb-stats-label");

        docItems = FXCollections.observableArrayList();
        docListView = new ListView<>(docItems);
        docListView.setPrefHeight(200);
        docListView.getStyleClass().add("kb-doc-list");
        docListView.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);

        Label emptyPlaceholder = new Label("暂无已导入的文档");
        emptyPlaceholder.getStyleClass().add("kb-empty-hint");
        docListView.setPlaceholder(emptyPlaceholder);

        docListView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    setStyle("");
                } else {
                    int chunks = knowledgeExpert.getDocumentChunkCount(item);
                    String time = knowledgeExpert.getDocumentImportTime(item);
                    Scope scope = knowledgeExpert.getDocumentScope(item);

                    // 作用域标签
                    Label scopeTag = new Label(scope == Scope.GLOBAL ? "全局" : "工作区");
                    scopeTag.getStyleClass().add(scope == Scope.GLOBAL
                            ? "kb-scope-tag-global" : "kb-scope-tag-workspace");

                    // 文件名
                    Label nameLabel = new Label(item);
                    nameLabel.getStyleClass().add("kb-doc-name");
                    HBox.setHgrow(nameLabel, Priority.ALWAYS);
                    nameLabel.setMaxWidth(Double.MAX_VALUE);

                    // 片段数
                    Label chunkLabel = new Label(chunks + " 片段");
                    chunkLabel.getStyleClass().add("kb-doc-chunk");

                    HBox topRow = new HBox(8, scopeTag, nameLabel, chunkLabel);
                    topRow.setAlignment(Pos.CENTER_LEFT);

                    if (time != null) {
                        Label timeLabel = new Label(time);
                        timeLabel.getStyleClass().add("kb-doc-time");
                        VBox cellBox = new VBox(2, topRow, timeLabel);
                        cellBox.setPadding(new Insets(4, 0, 4, 0));
                        setGraphic(cellBox);
                    } else {
                        topRow.setPadding(new Insets(4, 0, 4, 0));
                        setGraphic(topRow);
                    }
                    setText(null);
                }
            }
        });

        deleteBtn = new Button("删除选中");
        deleteBtn.getStyleClass().add("danger-btn");
        deleteBtn.setDisable(true);
        deleteBtn.setOnAction(e -> onDeleteSelected());

        docListView.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldVal, newVal) -> deleteBtn.setDisable(newVal == null));

        Button refreshBtn = new Button("刷新");
        refreshBtn.getStyleClass().add("kb-action-btn");
        refreshBtn.setDisable(!ragEnabled);
        refreshBtn.setOnAction(e -> onRefreshList());

        Button clearBtn = new Button("清空知识库");
        clearBtn.getStyleClass().add("danger-btn-critical");
        clearBtn.setDisable(!ragEnabled);
        clearBtn.setOnAction(e -> onClearKnowledge());

        HBox docButtonRow = new HBox(10, refreshBtn, deleteBtn, new Region() {{
            HBox.setHgrow(this, Priority.ALWAYS);
        }}, clearBtn);
        docButtonRow.setAlignment(Pos.CENTER_LEFT);

        VBox docCard = new VBox(10, docTitleRow, statsLabel, docListView, docButtonRow);
        docCard.getStyleClass().add("kb-card");

        // ==================== 底部状态栏 ====================

        Button closeButton = new Button("关闭");
        closeButton.getStyleClass().add("settings-close-button");
        closeButton.setOnAction(e -> stage.close());

        HBox bottomBar = new HBox(10, statusLabel, new Region() {{
            HBox.setHgrow(this, Priority.ALWAYS);
        }}, closeButton);
        bottomBar.setAlignment(Pos.CENTER_LEFT);
        bottomBar.setPadding(new Insets(4, 0, 0, 0));

        // ==================== 组装 ====================

        VBox panel = new VBox(14);
        panel.getChildren().add(headerBox);
        if (setupGuideBox != null) {
            panel.getChildren().add(setupGuideBox);
        }
        panel.getChildren().addAll(scopeCard, fileCard, textCard, docCard, bottomBar);
        panel.setPadding(new Insets(24, 28, 20, 28));
        panel.getStyleClass().add("kb-root");

        ScrollPane scrollPane = new ScrollPane(panel);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.getStyleClass().add("kb-scroll");

        Scene scene = new Scene(scrollPane, 660, 780);

        String cssPath = getClass().getResource("/css/chat.css") != null
                ? getClass().getResource("/css/chat.css").toExternalForm()
                : null;
        if (cssPath != null) {
            scene.getStylesheets().add(cssPath);
        }

        stage.setScene(scene);

        if (ragEnabled) {
            onRefreshList();
        }
    }

    private Scope getSelectedScope() {
        return scopeCombo.getSelectionModel().getSelectedIndex() == 1 ? Scope.GLOBAL : Scope.WORKSPACE;
    }

    // ==================== 进度控制 ====================

    private void showProgress(int total) {
        progressBar.setProgress(0);
        progressBar.setVisible(true);
        progressBar.setManaged(true);
        progressDetailLabel.setText("准备导入...");
        progressDetailLabel.setVisible(true);
        progressDetailLabel.setManaged(true);
        importFileBtn.setDisable(true);
        importFolderBtn.setDisable(true);
        importTextBtn.setDisable(true);
    }

    private void updateProgress(int current, int total, String fileName, boolean success) {
        progressBar.setProgress((double) current / total);
        String status = success ? "成功" : "失败";
        progressDetailLabel.setText(String.format("(%d/%d) %s — %s", current, total, fileName, status));
    }

    private void hideProgress() {
        progressBar.setVisible(false);
        progressBar.setManaged(false);
        progressDetailLabel.setVisible(false);
        progressDetailLabel.setManaged(false);
        importFileBtn.setDisable(false);
        importFolderBtn.setDisable(false);
        importTextBtn.setDisable(false);
    }

    // ==================== 导入操作 ====================

    private void onImportFiles() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("选择要导入到知识库的文件");
        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("支持的文档格式",
                        "*.txt", "*.text", "*.md", "*.markdown", "*.log", "*.csv", "*.json", "*.xml", "*.html", "*.htm", "*.pdf"),
                new FileChooser.ExtensionFilter("Markdown / 文本", "*.txt", "*.text", "*.md", "*.markdown"),
                new FileChooser.ExtensionFilter("PDF 文件", "*.pdf"),
                new FileChooser.ExtensionFilter("所有文件", "*.*")
        );

        List<File> files = fileChooser.showOpenMultipleDialog(stage);
        if (files == null || files.isEmpty()) return;

        importFiles(files.toArray(new File[0]));
    }

    private void onImportFolder() {
        javafx.stage.DirectoryChooser dirChooser = new javafx.stage.DirectoryChooser();
        dirChooser.setTitle("选择要导入的目录");

        File dir = dirChooser.showDialog(stage);
        if (dir == null) return;

        File[] files = dir.listFiles(f -> f.isFile() && isSupportedImportFile(f.getName()));

        if (files == null || files.length == 0) {
            setStatus("目录中没有找到支持的文件（PDF / TXT / Markdown 等文本文件）", "#e67e22");
            return;
        }

        importFiles(files);
    }

    /** 单个文件最大导入大小（50MB） */
    private static final long MAX_IMPORT_FILE_SIZE = 50 * 1024 * 1024;

    /** 目录导入时识别的受支持文件类型（与 {@code KnowledgeExpert.importFile} 保持一致）。 */
    private boolean isSupportedImportFile(String name) {
        String lower = name.toLowerCase();
        return lower.endsWith(".pdf") || lower.endsWith(".txt") || lower.endsWith(".text")
                || lower.endsWith(".md") || lower.endsWith(".markdown") || lower.endsWith(".log")
                || lower.endsWith(".csv") || lower.endsWith(".json") || lower.endsWith(".xml")
                || lower.endsWith(".html") || lower.endsWith(".htm");
    }

    private void importFiles(File[] files) {
        int total = files.length;
        Scope scope = getSelectedScope();
        Platform.runLater(() -> {
            showProgress(total);
            String scopeLabel = scope == Scope.GLOBAL ? "全局知识库" : "工作区知识库";
            setStatus("正在导入 " + total + " 个文件到" + scopeLabel + "...", "#2980b9");
        });

        Thread importThread = new Thread(() -> {
            int successCount = 0;
            int failCount = 0;
            // 收集每个失败文件的原因，导入结束后弹窗展示，避免用户只看到"X 失败"却不知缘由
            List<String> failures = new java.util.ArrayList<>();

            for (int i = 0; i < files.length; i++) {
                File file = files[i];
                String result;
                // 文件大小校验，防止超大文件导致 OOM
                if (file.length() > MAX_IMPORT_FILE_SIZE) {
                    result = String.format("[knowledge_import_file][失败] 文件过大（%.1f MB），最大支持 50MB",
                            file.length() / (1024.0 * 1024));
                } else {
                    result = knowledgeExpert.importFile(file.getAbsolutePath(), scope);
                }
                boolean ok = ToolResponse.isSuccess(result);
                if (ok) {
                    successCount++;
                } else {
                    failCount++;
                    failures.add(file.getName() + " — " + cleanReason(result));
                }

                int idx = i + 1;
                boolean success = ok;
                String failMsg = ok ? null : result;
                Platform.runLater(() -> {
                    updateProgress(idx, total, file.getName(), success);
                    if (!success) {
                        log.warn("文件导入失败: {} — {}", file.getName(), failMsg);
                    }
                });
            }

            int finalSuccess = successCount;
            int finalFail = failCount;
            List<String> finalFailures = failures;
            Platform.runLater(() -> {
                hideProgress();
                if (finalFail == 0) {
                    setStatus("全部导入成功（共 " + finalSuccess + " 个文件）", "#27ae60");
                } else {
                    setStatus(String.format("导入完成：%d 成功，%d 失败（详见弹窗）", finalSuccess, finalFail), "#e67e22");
                    showImportFailures(finalFailures);
                }
                onRefreshList();
            });
        });
        importThread.setDaemon(true);
        importThread.start();
    }

    private void onImportText() {
        String text = textImportArea.getText();
        if (text == null || text.isBlank()) {
            setStatus("请输入要导入的文本内容", "#e74c3c");
            return;
        }

        String title = textTitleField.getText();
        if (title == null || title.isBlank()) {
            title = "手动导入文本";
        }

        Scope scope = getSelectedScope();
        setStatus("正在导入文本...", "#2980b9");

        // 显示不定进度指示
        progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        progressBar.setVisible(true);
        progressBar.setManaged(true);
        progressDetailLabel.setText("正在导入文本...");
        progressDetailLabel.setVisible(true);
        progressDetailLabel.setManaged(true);
        importFileBtn.setDisable(true);
        importFolderBtn.setDisable(true);
        importTextBtn.setDisable(true);

        String finalTitle = title;
        Thread importThread = new Thread(() -> {
            String result = knowledgeExpert.importText(text, finalTitle, scope);
            Platform.runLater(() -> {
                hideProgress();
                if (result.contains("[成功]")) {
                    setStatus("文本导入成功", "#27ae60");
                    textImportArea.clear();
                    textTitleField.clear();
                    onRefreshList();
                } else {
                    setStatus("导入失败: " + result, "#e74c3c");
                }
            });
        });
        importThread.setDaemon(true);
        importThread.start();
    }

    // ==================== 文档管理 ====================

    private void onRefreshList() {
        docItems.clear();
        // 先全局，后工作区
        List<String> globalDocs = knowledgeExpert.getDocumentNames(Scope.GLOBAL);
        List<String> workspaceDocs = knowledgeExpert.getDocumentNames(Scope.WORKSPACE);
        docItems.addAll(globalDocs);
        docItems.addAll(workspaceDocs);

        int docCount = knowledgeExpert.getDocumentCount();
        int chunkCount = knowledgeExpert.getTotalChunkCount();
        if (docCount > 0) {
            statsLabel.setText(String.format("总计 %d 个文档（全局 %d / 工作区 %d），%d 个片段",
                    docCount, globalDocs.size(), workspaceDocs.size(), chunkCount));
        } else {
            statsLabel.setText("");
        }

        deleteBtn.setDisable(true);
    }

    private void onDeleteSelected() {
        String selected = docListView.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        int chunks = knowledgeExpert.getDocumentChunkCount(selected);
        Scope scope = knowledgeExpert.getDocumentScope(selected);
        String scopeTag = scope == Scope.GLOBAL ? "全局" : "工作区";
        Alert confirm = UIHelper.createConfirmAlert("确认删除",
                String.format("确定要删除%s文档 [%s] 吗？\n该文档包含 %d 个片段，删除后不可撤销。",
                        scopeTag, selected, chunks), stage);
        confirm.showAndWait().ifPresent(btn -> {
            if (btn == ButtonType.OK) {
                setStatus("正在删除...", "#2980b9");
                Thread deleteThread = new Thread(() -> {
                    String result = knowledgeExpert.knowledge_delete(selected);
                    Platform.runLater(() -> {
                        if (result.contains("[成功]")) {
                            setStatus("已删除文档: " + selected, "#27ae60");
                        } else {
                            setStatus("删除失败: " + result, "#e74c3c");
                        }
                        onRefreshList();
                    });
                });
                deleteThread.setDaemon(true);
                deleteThread.start();
            }
        });
    }

    private void onClearKnowledge() {
        int docCount = knowledgeExpert.getDocumentCount();
        int chunkCount = knowledgeExpert.getTotalChunkCount();
        Alert confirm = UIHelper.createConfirmAlert("确认清空",
                String.format("确定要清空所有知识库（全局 + 工作区）吗？\n当前共 %d 个文档，%d 个片段。\n此操作不可撤销。",
                        docCount, chunkCount), stage);
        confirm.showAndWait().ifPresent(btn -> {
            if (btn == ButtonType.OK) {
                String result = knowledgeExpert.knowledge_clear();
                if (result.contains("[成功]")) {
                    setStatus("知识库已清空", "#27ae60");
                } else {
                    setStatus("清空失败", "#e74c3c");
                }
                onRefreshList();
            }
        });
    }

    /**
     * 从 {@link ToolResponse} 格式字符串（{@code [工具名][状态] 原因}）中提取可读的失败原因，
     * 去掉前缀的两段方括号标记。
     */
    private String cleanReason(String toolResponse) {
        if (toolResponse == null || toolResponse.isBlank()) return "未知错误";
        int firstClose = toolResponse.indexOf(']');
        int secondClose = firstClose >= 0 ? toolResponse.indexOf(']', firstClose + 1) : -1;
        if (secondClose >= 0 && secondClose + 1 < toolResponse.length()) {
            return toolResponse.substring(secondClose + 1).trim();
        }
        return toolResponse.trim();
    }

    /**
     * 弹窗展示导入失败明细：每个文件一行「文件名 — 失败原因」，置于可展开区域便于查看与复制。
     */
    private void showImportFailures(List<String> failures) {
        if (failures == null || failures.isEmpty()) return;
        Alert alert = UIHelper.createWarningAlert(
                "有 " + failures.size() + " 个文件导入失败，下方列出具体原因。", stage);
        alert.setTitle("知识库导入失败");

        TextArea detail = new TextArea(String.join("\n", failures));
        detail.setEditable(false);
        detail.setWrapText(true);
        detail.setPrefRowCount(Math.min(12, failures.size() + 1));
        detail.setMaxWidth(Double.MAX_VALUE);
        detail.setMaxHeight(Double.MAX_VALUE);
        GridPane.setVgrow(detail, Priority.ALWAYS);
        GridPane.setHgrow(detail, Priority.ALWAYS);

        alert.getDialogPane().setExpandableContent(detail);
        alert.getDialogPane().setExpanded(true);
        alert.show();
    }

    private void setStatus(String text, String color) {
        statusLabel.setText(text);
        statusLabel.getStyleClass().removeAll("status-success", "status-error", "status-info", "status-warning");
        String cssClass = switch (color) {
            case "#27ae60" -> "status-success";
            case "#e74c3c" -> "status-error";
            case "#2980b9" -> "status-info";
            case "#e67e22" -> "status-warning";
            default -> "";
        };
        if (!cssClass.isEmpty()) {
            statusLabel.getStyleClass().add(cssClass);
        }
    }

    /**
     * 设置配置变更回调（重建智能体服务）
     */
    public void setOnConfigChanged(Runnable callback) {
        this.onConfigChanged = callback;
    }

    // ==================== 一键配置 ====================

    /**
     * 根据当前主模型提供商自动配置 RAG 嵌入模型参数
     */
    private void onQuickSetup() {
        AgentConfig config = AgentConfig.getInstance();
        config.setRagEnabled(true);
        // 复用主模型的提供商配置
        String provider = config.getProviderType();
        switch (provider) {
            case "OpenAI" -> {
                config.setRagEmbeddingProvider("OpenAI");
                config.setRagEmbeddingBaseUrl(config.getBaseUrl());
                config.setRagEmbeddingApiKey(config.getApiKey());
                config.setRagEmbeddingModelName("text-embedding-3-small");
                config.setRagEmbeddingDimensions(1024);
            }
            case "DashScope" -> {
                config.setRagEmbeddingProvider("DashScope");
                config.setRagEmbeddingBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
                config.setRagEmbeddingApiKey(config.getApiKey());
                config.setRagEmbeddingModelName("text-embedding-v3");
                config.setRagEmbeddingDimensions(1024);
            }
            case "Ollama" -> {
                config.setRagEmbeddingProvider("Ollama");
                config.setRagEmbeddingBaseUrl("http://localhost:11434/v1");
                config.setRagEmbeddingApiKey("");
                config.setRagEmbeddingModelName("nomic-embed-text");
                config.setRagEmbeddingDimensions(768);
            }
            default -> {
                // Anthropic/Gemini 不提供嵌入模型，使用 OpenAI 兼容默认值
                config.setRagEmbeddingProvider("OpenAI");
                config.setRagEmbeddingBaseUrl(config.getBaseUrl());
                config.setRagEmbeddingApiKey(config.getApiKey());
                config.setRagEmbeddingModelName("text-embedding-3-small");
                config.setRagEmbeddingDimensions(1024);
            }
        }
        config.save();

        setStatus("知识库已配置完成，正在重建服务...", "#27ae60");
        log.info("一键配置 RAG 完成，提供商: {}，正在触发服务重建", provider);

        if (onConfigChanged != null) {
            onConfigChanged.run();
        }

        // 关闭当前窗口（服务重建后需重新打开才能使用）
        stage.close();
    }

    public void show() {
        stage.show();
    }
}
