package com.javaclaw.onboarding;

import com.javaclaw.config.AgentConfig;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * 三步上手向导
 *
 * <p>首次启动时引导用户完成 Provider 选择、API Key 配置，
 * 完成后标记 {@code ui.first.use.guidance.done} 避免再次弹出。</p>
 *
 * <p>流程：</p>
 * <ol>
 *   <li>Step1 — 从 5 个 Provider 模板中选择</li>
 *   <li>Step2 — 填写 baseUrl / 模型 / API Key（Ollama 无需 Key）</li>
 *   <li>Step3 — 完成提示，"开始使用"关闭向导</li>
 * </ol>
 *
 * <p>任何步骤都可点击"跳过"直接关闭并标记完成。</p>
 */
public class OnboardingWizard {

    private static final Logger log = LoggerFactory.getLogger(OnboardingWizard.class);

    private final Stage stage;
    private final AgentConfig config;

    private ProviderTemplate selectedTemplate;

    // UI 节点（各步骤共享引用以便跨步骤读写）
    private final StackPane contentPane = new StackPane();
    private final Label stepIndicator = new Label();
    private Button prevButton;
    private Button nextButton;

    // Step2 输入
    private TextField baseUrlField;
    private TextField modelNameField;
    private PasswordField apiKeyField;
    private Label testConnectionStatus;

    private int currentStep = 1;
    private static final int TOTAL_STEPS = 3;

    private OnboardingWizard(Stage owner) {
        this.config = AgentConfig.getInstance();
        this.stage = new Stage(StageStyle.DECORATED);
        this.stage.initModality(Modality.APPLICATION_MODAL);
        if (owner != null) this.stage.initOwner(owner);
        this.stage.setTitle("欢迎使用 JavaClaw");
        this.stage.setResizable(false);
    }

    /**
     * 若首次使用未完成，则阻塞显示向导，直到用户完成或跳过
     */
    public static void showIfNeeded(Stage owner) {
        if (AgentConfig.getInstance().isFirstUseGuidanceDone()) return;
        new OnboardingWizard(owner).showAndWait();
    }

    private void showAndWait() {
        BorderPane root = new BorderPane();
        root.setPadding(new Insets(24));
        root.setPrefSize(720, 520);

        // 顶部：标题 + 步骤指示 + 跳过
        Label title = new Label("欢迎使用 JavaClaw");
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");

        stepIndicator.setStyle("-fx-text-fill: -jc-text-hint; -fx-font-size: 12px;");
        updateStepIndicator();

        Region topSpacer = new Region();
        HBox.setHgrow(topSpacer, Priority.ALWAYS);

        Hyperlink skip = new Hyperlink("跳过配置");
        skip.setOnAction(e -> { markDoneAndClose(); });

        HBox header = new HBox(12, title, stepIndicator, topSpacer, skip);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(0, 0, 16, 0));
        root.setTop(header);

        // 中部：动态内容
        root.setCenter(contentPane);
        contentPane.getChildren().add(buildStep1());

        // 底部：上一步 / 下一步
        prevButton = new Button("上一步");
        prevButton.setOnAction(e -> goPrev());
        nextButton = new Button("下一步");
        nextButton.setOnAction(e -> goNext());

        Region bottomSpacer = new Region();
        HBox.setHgrow(bottomSpacer, Priority.ALWAYS);

        HBox footer = new HBox(12, bottomSpacer, prevButton, nextButton);
        footer.setAlignment(Pos.CENTER_RIGHT);
        footer.setPadding(new Insets(16, 0, 0, 0));
        root.setBottom(footer);

        updateButtonsForCurrentStep();

        Scene scene = new Scene(root);
        var cssUrl = OnboardingWizard.class.getResource("/css/chat.css");
        if (cssUrl != null) scene.getStylesheets().add(cssUrl.toExternalForm());
        stage.setScene(scene);
        stage.showAndWait();
    }

    private void markDoneAndClose() {
        config.setFirstUseGuidanceDone(true);
        config.save();
        stage.close();
    }

    private void updateStepIndicator() {
        stepIndicator.setText(String.format("· 步骤 %d / %d", currentStep, TOTAL_STEPS));
    }

    private void updateButtonsForCurrentStep() {
        prevButton.setDisable(currentStep == 1);
        if (currentStep == TOTAL_STEPS) {
            nextButton.setText("开始使用");
        } else {
            nextButton.setText("下一步");
        }
        // Step1 必须选中才能下一步
        nextButton.setDisable(currentStep == 1 && selectedTemplate == null);
    }

    private void goPrev() {
        if (currentStep <= 1) return;
        currentStep--;
        contentPane.getChildren().setAll(renderStep(currentStep));
        updateStepIndicator();
        updateButtonsForCurrentStep();
    }

    private void goNext() {
        if (currentStep == 2) {
            if (!saveStep2()) return;  // 校验失败，不前进
        }
        if (currentStep == TOTAL_STEPS) {
            markDoneAndClose();
            return;
        }
        currentStep++;
        contentPane.getChildren().setAll(renderStep(currentStep));
        updateStepIndicator();
        updateButtonsForCurrentStep();
    }

    private Region renderStep(int step) {
        return switch (step) {
            case 1 -> buildStep1();
            case 2 -> buildStep2();
            case 3 -> buildStep3();
            default -> new VBox();
        };
    }

    // ==================== Step1 Provider 选择 ====================

    private Region buildStep1() {
        VBox box = new VBox(12);
        Label prompt = new Label("选择你的模型提供商");
        prompt.setStyle("-fx-font-size: 16px; -fx-font-weight: 600;");
        Label hint = new Label("推荐：中文场景选择通义千问，隐私场景选择 Ollama（本地）");
        hint.setStyle("-fx-text-fill: -jc-text-muted; -fx-font-size: 12px;");

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(12);
        grid.setPadding(new Insets(16, 0, 0, 0));

        int col = 0, row = 0;
        for (ProviderTemplate tpl : ProviderTemplate.ALL) {
            grid.add(buildProviderCard(tpl), col, row);
            col++;
            if (col == 3) { col = 0; row++; }
        }

        box.getChildren().addAll(prompt, hint, grid);
        return box;
    }

    private Region buildProviderCard(ProviderTemplate tpl) {
        VBox card = new VBox(6);
        card.setPadding(new Insets(14));
        card.setPrefSize(200, 110);
        String baseStyle = "-fx-background-color: white; -fx-border-color: #E2E8F0; " +
                "-fx-border-radius: 8; -fx-background-radius: 8; -fx-cursor: hand;";
        String selectedStyle = "-fx-background-color: #EEF2FF; -fx-border-color: #6366F1; " +
                "-fx-border-width: 2; -fx-border-radius: 8; -fx-background-radius: 8; -fx-cursor: hand;";
        card.setStyle(baseStyle);

        Label name = new Label(tpl.displayName());
        name.setStyle("-fx-font-weight: 600; -fx-font-size: 14px;");

        Label desc = new Label(tpl.description());
        desc.setStyle("-fx-text-fill: -jc-text-muted; -fx-font-size: 11px;");
        desc.setWrapText(true);

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        HBox tags = new HBox(6);
        if (tpl.recommended()) {
            Label tag = new Label("推荐");
            tag.setStyle("-fx-background-color: #10B981; -fx-text-fill: white; " +
                    "-fx-padding: 2 8; -fx-background-radius: 4; -fx-font-size: 10px;");
            tags.getChildren().add(tag);
        }
        if (tpl.local()) {
            Label tag = new Label("本地");
            tag.setStyle("-fx-background-color: #6366F1; -fx-text-fill: white; " +
                    "-fx-padding: 2 8; -fx-background-radius: 4; -fx-font-size: 10px;");
            tags.getChildren().add(tag);
        }

        card.getChildren().addAll(name, desc, spacer, tags);

        card.setOnMouseClicked(e -> {
            selectedTemplate = tpl;
            // 重新渲染 Step1 以刷新选中样式
            contentPane.getChildren().setAll(buildStep1());
            updateButtonsForCurrentStep();
        });

        if (selectedTemplate != null && selectedTemplate.id().equals(tpl.id())) {
            card.setStyle(selectedStyle);
        }
        return card;
    }

    // ==================== Step2 API Key 配置 ====================

    private Region buildStep2() {
        VBox box = new VBox(12);
        Label prompt = new Label("配置 " + selectedTemplate.displayName());
        prompt.setStyle("-fx-font-size: 16px; -fx-font-weight: 600;");

        Label hint = new Label(selectedTemplate.local()
                ? "本地 Ollama 无需 API Key，确认 baseUrl 指向本机服务即可。"
                : "请填写 API Key；模型名与 baseUrl 已预填默认值，可按需修改。");
        hint.setStyle("-fx-text-fill: -jc-text-muted; -fx-font-size: 12px;");
        hint.setWrapText(true);

        baseUrlField = new TextField(selectedTemplate.baseUrl());
        modelNameField = new TextField(selectedTemplate.defaultModel());
        apiKeyField = new PasswordField();
        apiKeyField.setPromptText(selectedTemplate.local() ? "无需填写" : "粘贴你的 API Key");
        apiKeyField.setDisable(selectedTemplate.local());

        GridPane form = new GridPane();
        form.setHgap(12);
        form.setVgap(10);
        form.addRow(0, new Label("Base URL"), baseUrlField);
        form.addRow(1, new Label("模型名称"), modelNameField);
        form.addRow(2, new Label("API Key"), apiKeyField);
        GridPane.setHgrow(baseUrlField, Priority.ALWAYS);
        GridPane.setHgrow(modelNameField, Priority.ALWAYS);
        GridPane.setHgrow(apiKeyField, Priority.ALWAYS);
        baseUrlField.setPrefWidth(460);

        Button testBtn = new Button("测试连接");
        testConnectionStatus = new Label();
        testConnectionStatus.setStyle("-fx-font-size: 12px; -fx-text-fill: -jc-text-muted;");
        testBtn.setOnAction(e -> runConnectionTest(testBtn));

        HBox testRow = new HBox(10, testBtn, testConnectionStatus);
        testRow.setAlignment(Pos.CENTER_LEFT);
        testRow.setPadding(new Insets(8, 0, 0, 0));

        box.getChildren().addAll(prompt, hint, form, testRow);
        return box;
    }

    /**
     * 校验并保存 Step2 配置；校验失败返回 false 阻止前进
     */
    private boolean saveStep2() {
        String baseUrl = baseUrlField.getText().trim();
        String model = modelNameField.getText().trim();
        String apiKey = selectedTemplate.local() ? "not-needed" : apiKeyField.getText().trim();

        if (baseUrl.isEmpty() || model.isEmpty()) {
            setTestStatus("baseUrl 和模型名称不能为空", "#DC2626");
            return false;
        }
        if (!selectedTemplate.local() && apiKey.isEmpty()) {
            setTestStatus("云端模型需要填写 API Key", "#DC2626");
            return false;
        }

        config.setProviderType(selectedTemplate.id());
        config.setBaseUrl(baseUrl);
        config.setModelName(model);
        config.setApiKey(apiKey);
        config.save();
        log.info("首次向导已保存配置：provider={}, model={}", selectedTemplate.id(), model);
        return true;
    }

    /**
     * 测试连接：对 baseUrl 做轻量 HTTP 探测
     *
     * <p>不直接调用模型（避免 Provider 特定的协议差异），退化为对 baseUrl 做 GET 探活。
     * 只要能收到响应（无论 HTTP 状态码）即认为网络可达。</p>
     */
    private void runConnectionTest(Button testBtn) {
        testBtn.setDisable(true);
        setTestStatus("测试中...", "#64748B");
        String baseUrl = baseUrlField.getText().trim();
        if (baseUrl.isEmpty()) {
            setTestStatus("请先填写 Base URL", "#DC2626");
            testBtn.setDisable(false);
            return;
        }
        String probeUrl = selectedTemplate.local()
                ? baseUrl.replaceAll("/v1/?$", "") + "/api/tags"
                : baseUrl.replaceAll("/+$", "") + "/models";

        CompletableFuture.supplyAsync(() -> {
            try {
                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5)).build();
                HttpRequest req = HttpRequest.newBuilder(URI.create(probeUrl))
                        .timeout(Duration.ofSeconds(8))
                        .GET().build();
                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                return resp.statusCode();
            } catch (Exception ex) {
                return -1;
            }
        }).whenComplete((code, ex) -> javafx.application.Platform.runLater(() -> {
            if (code != null && code > 0) {
                setTestStatus("连接成功（HTTP " + code + "）", "#10B981");
            } else {
                setTestStatus("连接失败，请检查 Base URL 是否正确", "#DC2626");
            }
            testBtn.setDisable(false);
        }));
    }

    private void setTestStatus(String text, String color) {
        testConnectionStatus.setText(text);
        testConnectionStatus.setStyle("-fx-font-size: 12px; -fx-text-fill: " + color + ";");
    }

    // ==================== Step3 完成 ====================

    private Region buildStep3() {
        VBox box = new VBox(14);
        box.setAlignment(Pos.CENTER_LEFT);

        Label prompt = new Label("配置完成 ✓");
        prompt.setStyle("-fx-font-size: 18px; -fx-font-weight: 600; -fx-text-fill: #10B981;");

        Label summary = new Label(
                "提供商：" + selectedTemplate.displayName() + "\n" +
                "模型：" + config.getModelName() + "\n" +
                "Base URL：" + config.getBaseUrl());
        summary.setStyle("-fx-font-family: " + com.javaclaw.ui.javafx.theme.FontManager.MONO_FONT_STACK + "; -fx-font-size: 12px;");

        Label tips = new Label(
                "小贴士：\n" +
                "• 随时可在顶部「设置」中修改 API、模型、超时等参数\n" +
                "• 长时任务请使用「创建任务」，支持规划 → 执行 → 验收全流程\n" +
                "• 高风险操作（删文件、发邮件）会弹窗二次确认");
        tips.setStyle("-fx-text-fill: -jc-text-muted; -fx-font-size: 12px;");
        tips.setWrapText(true);

        box.getChildren().addAll(prompt, summary, tips);
        return box;
    }
}
