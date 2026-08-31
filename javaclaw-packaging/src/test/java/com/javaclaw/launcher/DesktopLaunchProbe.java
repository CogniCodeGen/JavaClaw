package com.javaclaw.launcher;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;

import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextInputControl;
import javafx.scene.image.PixelFormat;
import javafx.stage.Stage;
import javafx.stage.Window;

/** 在独立 JVM 中运行真实产品入口，等待主窗口就绪后关闭并检查所属进程；不替换任何生产组件。 */
public final class DesktopLaunchProbe {
    private static final PseudoClass HOVER = PseudoClass.getPseudoClass("hover");
    private static final PseudoClass PRESSED = PseudoClass.getPseudoClass("pressed");
    private static final String UI_PROVIDER_MODEL = "desktop-ui-saved-model";
    private static final String UI_PROVIDER_EMBEDDING = "desktop-ui-embedding";
    private static final String UI_PROVIDER_BASE_URL = "https://fixture.invalid/v1";
    private static final String UI_PROVIDER_SECRET = "desktop-ui-fixture-secret";
    private static final List<DesktopFeatureCatalog.Page> PAGES = DesktopFeatureCatalog.pages();
    private static final List<String> THEMES =
            List.of("emerald", "midnight", "carbon", "sapphire", "ocean", "plum", "terracotta", "honey", "graphite");
    private static final Map<String, String> MANAGEMENT_MENU_LABELS = Map.ofEntries(
            Map.entry("Agent Studio", "Agent Studio"),
            Map.entry("记忆中心", "🧠  记忆"),
            Map.entry("知识中心", "📚  知识"),
            Map.entry("技能中心", "⚙  技能"),
            Map.entry("自动化与工作流", "⎇  自动化"),
            Map.entry("定时任务", "⏰  定时任务"),
            Map.entry("插件", "🧩  插件"),
            Map.entry("MCP 连接", "🔌  MCP"),
            Map.entry("站点管理", "站点管理"),
            Map.entry("项目约定", "项目约定"),
            Map.entry("协作与工作树恢复", "协作与工作树恢复"),
            Map.entry("设置", "⚙  设置"));
    private static int page = -1;
    private static int theme;
    private static boolean themeApplied;
    private static boolean themeRestoreRequested;
    private static boolean conversationSelected;
    private static boolean selected;
    private static int settingsSection;
    private static int providerScenarioStep;
    private static boolean diagnosticsRefreshed;
    private static boolean applicationReady;
    private static boolean dangerDialogRequested;
    private static boolean dangerDialogCaptured;
    private static boolean managementMenuRequested;
    private static boolean managementMenuVerified;
    private static long openedAt;
    private static CaptureSequence captureSequence;

    private DesktopLaunchProbe() {}

    /** 启动产品主类并执行窗口验证；args 原样传给产品，失败以非零进程状态返回。 */
    public static void main(String[] args) throws Exception {
        Runtime.getRuntime()
                .addShutdownHook(Thread.ofPlatform()
                        .unstarted(
                                () -> ProcessHandle.current().descendants().forEach(ProcessHandle::destroyForcibly)));
        seedThroughSdk();
        AtomicReference<List<ProcessHandle>> owned = new AtomicReference<>(List.of());
        Thread observer = Thread.ofPlatform().daemon().start(() -> observe(owned));
        try {
            JavaClawLauncher.main(args);
        } finally {
            observer.interrupt();
        }
        if (owned.get().isEmpty()) {
            throw new IllegalStateException("窗口尚未完成真实 App Server 握手与初始数据加载");
        }
        for (ProcessHandle process : owned.get()) {
            process.onExit().get(5, TimeUnit.SECONDS);
            if (process.isAlive()) {
                throw new IllegalStateException("桌面退出后仍有所属进程：" + process.pid());
            }
        }
        verifyProviderSettingsThroughSdk();
        System.out.println("JAVACLAW_DESKTOP_SMOKE_OK");
    }

    /** 重新连接真实 App Server，核验 UI 写入、幂等、乐观锁、免密端点和凭据脱敏，而不是只读取控件文本。 */
    private static void verifyProviderSettingsThroughSdk() throws Exception {
        var layout = RuntimeLayout.discover();
        try (var server =
                new com.javaclaw.sdk.AppServerProcess(layout.appServerCommand(), layout.infrastructureEnvironment())) {
            var sdk = server.client();
            sdk.initialize("desktop-smoke-provider-verify", "1").get(15, TimeUnit.SECONDS);
            var provider = sdk.models().listProviders().get(5, TimeUnit.SECONDS).stream()
                    .filter(value -> "openai".equals(value.id()))
                    .findFirst()
                    .orElseThrow();
            if (!provider.configured()
                    || !UI_PROVIDER_MODEL.equals(provider.model())
                    || !UI_PROVIDER_EMBEDDING.equals(provider.embeddingModel())
                    || !UI_PROVIDER_BASE_URL.equals(provider.baseUrl())) {
                throw new IllegalStateException("模型设置 UI 未持久化到 App Server：" + provider);
            }
            var chatProfile = sdk.models().listProfiles().get(5, TimeUnit.SECONDS).stream()
                    .filter(value -> "profile_chat".equals(value.id()))
                    .findFirst()
                    .orElseThrow();
            if (!UI_PROVIDER_MODEL.equals(chatProfile.model())) {
                throw new IllegalStateException("模型设置 UI 未同步输入区执行 Profile：" + chatProfile.model());
            }
            String diagnostics = sdk.administration()
                    .readDiagnostics(200)
                    .get(5, TimeUnit.SECONDS)
                    .canonicalJson();
            if (diagnostics.contains(UI_PROVIDER_SECRET)) {
                throw new IllegalStateException("诊断接口泄漏模型凭据");
            }

            Map<String, String> verified = Map.of(
                    "model", UI_PROVIDER_MODEL,
                    "embeddingModel", UI_PROVIDER_EMBEDDING,
                    "baseUrl", "https://verified.invalid/v1");
            String replayKey = "desktop-provider-idempotency";
            var updated = sdk.models()
                    .configureProvider("openai", verified, provider.configRevision(), replayKey)
                    .get(5, TimeUnit.SECONDS);
            var replayed = sdk.models()
                    .configureProvider("openai", verified, provider.configRevision(), replayKey)
                    .get(5, TimeUnit.SECONDS);
            if (updated.configRevision() != replayed.configRevision()) {
                throw new IllegalStateException("Provider 幂等重放产生了新的 revision");
            }
            try {
                sdk.models()
                        .configureProvider(
                                "openai", verified, provider.configRevision(), "desktop-provider-stale-revision")
                        .get(5, TimeUnit.SECONDS);
                throw new IllegalStateException("Provider stale revision 未被拒绝");
            } catch (ExecutionException expected) {
                if (expected.getCause() == null
                        || !String.valueOf(expected.getCause().getMessage()).contains("revision conflict")) {
                    throw expected;
                }
            }
            sdk.models()
                    .clearCredential("openai", provider.credentialRevision(), "desktop-provider-clear")
                    .get(5, TimeUnit.SECONDS);
            var withoutCredential = sdk.models().listProviders().get(5, TimeUnit.SECONDS).stream()
                    .filter(value -> "openai".equals(value.id()))
                    .findFirst()
                    .orElseThrow();
            if (withoutCredential.credentialRevision() != 0 || !withoutCredential.configured()) {
                throw new IllegalStateException("清除凭据后，自定义免密端点或凭据元数据状态不正确：" + withoutCredential);
            }
        }
        System.out.println("JAVACLAW_DESKTOP_PROVIDER_PERSISTENCE_OK");
    }

    private static void seedThroughSdk() throws Exception {
        var layout = RuntimeLayout.discover();
        try (var fixture = new DesktopModelFixture();
                var server = new com.javaclaw.sdk.AppServerProcess(
                        layout.appServerCommand(), layout.infrastructureEnvironment())) {
            var sdk = server.client();
            sdk.onConnectionState(state -> {
                if (state.state() == com.javaclaw.sdk.ConnectionStatus.State.RECONNECTING) {
                    // 此进程只有合成资料；保留协议重连原因以定位发行依赖的 stdout 污染。
                    System.err.println("DESKTOP_TEST_RECONNECT " + state.detail());
                }
            });
            sdk.initialize("desktop-smoke-seed", "1").get(15, TimeUnit.SECONDS);
            Path workspaceRoot =
                    Path.of(System.getenv("JAVACLAW_DATA_DIR")).getParent().resolve("示例工作区 workspace");
            java.nio.file.Files.createDirectories(workspaceRoot);
            var workspace =
                    sdk.workspaces().create("工作区验收", workspaceRoot, "workspace").get(5, TimeUnit.SECONDS);
            sdk.knowledge()
                    .saveLearningSettings(workspace.id(), "OFF", false, 0, "learning-off")
                    .get(5, TimeUnit.SECONDS);
            java.nio.file.Files.writeString(workspaceRoot.resolve("AGENTS.md"), "保留原桌面主题；代码变更遵循中文契约注释和格式门禁。");
            var instructions =
                    sdk.workspaces().resolveInstructions(workspace.id()).get(5, TimeUnit.SECONDS);
            if (instructions.sources().isEmpty()) {
                throw new IllegalStateException("桌面验收工作区未解析 AGENTS.md");
            }
            sdk.extensions()
                    .saveSite(
                            new com.javaclaw.sdk.model.BrowserSiteInfo(
                                    null,
                                    workspace.id(),
                                    "文档站点",
                                    java.net.URI.create("https://example.test"),
                                    java.util.Set.of(java.net.URI.create("https://example.test")),
                                    true,
                                    0,
                                    null),
                            true,
                            "site")
                    .get(5, TimeUnit.SECONDS);
            sdk.knowledge()
                    .saveMemory(
                            new com.javaclaw.sdk.model.MemoryDetailInfo(
                                    null,
                                    workspace.id(),
                                    "FACT",
                                    "用户",
                                    "language",
                                    "默认使用简体中文回答",
                                    true,
                                    List.of(),
                                    0,
                                    null,
                                    null),
                            0,
                            "memory")
                    .get(5, TimeUnit.SECONDS);
            sdk.knowledge()
                    .installSkill(
                            "style",
                            "原界面规范",
                            "1.0",
                            new com.javaclaw.sdk.model.JsonDocument("{\"instructions\":\"沿用已有主题、布局与交互；先核验测试。\"}"),
                            true,
                            0,
                            "skill")
                    .get(5, TimeUnit.SECONDS);
            Path document = workspaceRoot.resolve("架构约定.md");
            java.nio.file.Files.writeString(document, "# JavaClaw\n沿用原 UI 风格；所有客户端通过 SDK 调用 App Server。");
            var attachment = sdk.attachments()
                    .upload(document, "text/markdown", "document")
                    .get(10, TimeUnit.SECONDS);
            sdk.knowledge()
                    .importSource(workspace.id(), attachment, "架构约定.md", "knowledge")
                    .get(15, TimeUnit.SECONDS);
            sdk.models()
                    .configureProvider(
                            "openai",
                            java.util.Map.of("model", "desktop-fixture", "baseUrl", fixture.baseUrl()),
                            0,
                            "fixture-provider")
                    .get(5, TimeUnit.SECONDS);
            char[] syntheticKey = "desktop-fixture-not-a-real-key".toCharArray();
            var credential = sdk.models()
                    .setCredential("openai", syntheticKey, "fixture-key")
                    .get(15, TimeUnit.SECONDS);
            java.util.Arrays.fill(syntheticKey, '\0');
            var profile = sdk.models()
                    .putProfile(
                            new com.javaclaw.sdk.model.ProfileInfo(
                                    "desktop-fixture",
                                    "桌面视觉验收",
                                    "CHAT",
                                    "openai",
                                    "desktop-fixture",
                                    "仅用于本机合成验收。",
                                    java.util.Set.of(),
                                    "READ_ONLY",
                                    1,
                                    1,
                                    java.util.Map.of(),
                                    0,
                                    null),
                            0,
                            "fixture-profile")
                    .get(5, TimeUnit.SECONDS);
            var loopProfile = sdk.models()
                    .putProfile(
                            new com.javaclaw.sdk.model.ProfileInfo(
                                    "desktop-loop",
                                    "Loop 验收",
                                    "LOOP",
                                    "openai",
                                    "desktop-fixture",
                                    "仅用于本机自动化表单验收。",
                                    java.util.Set.of(),
                                    "READ_ONLY",
                                    25,
                                    100,
                                    java.util.Map.of(),
                                    0,
                                    null),
                            0,
                            "fixture-loop-profile")
                    .get(5, TimeUnit.SECONDS);
            var scheduleProfile = sdk.models()
                    .putProfile(
                            new com.javaclaw.sdk.model.ProfileInfo(
                                    "desktop-schedule",
                                    "Schedule 验收",
                                    "SCHEDULE",
                                    "openai",
                                    "desktop-fixture",
                                    "仅用于本机定时表单验收。",
                                    java.util.Set.of(),
                                    "READ_ONLY",
                                    1,
                                    1,
                                    java.util.Map.of(),
                                    0,
                                    null),
                            0,
                            "fixture-schedule-profile")
                    .get(5, TimeUnit.SECONDS);
            sdk.automations()
                    .put(
                            new com.javaclaw.sdk.model.AutomationInfo(
                                    null,
                                    "LOOP",
                                    "循环任务验收",
                                    workspace.id(),
                                    loopProfile.id(),
                                    "在有限预算内完成验收目标。",
                                    new com.javaclaw.sdk.model.JsonDocument(
                                            "{\"maxIterations\":25,\"maxModelCalls\":100,\"maxTokens\":200000,"
                                                    + "\"maxDurationSeconds\":3600,\"noProgressLimit\":3,"
                                                    + "\"specification\":\"\",\"successCriteria\":[]}"),
                                    "DRAFT",
                                    null,
                                    null,
                                    0,
                                    null,
                                    null),
                            0,
                            "fixture-automation")
                    .get(5, TimeUnit.SECONDS);
            sdk.automations()
                    .putSchedule(
                            new com.javaclaw.sdk.model.ScheduleInfo(
                                    null,
                                    "每日验收任务",
                                    workspace.id(),
                                    null,
                                    scheduleProfile.id(),
                                    "生成当日检查摘要。",
                                    "0 0 9 * * ?",
                                    "Asia/Shanghai",
                                    false,
                                    null,
                                    null,
                                    "",
                                    0,
                                    null,
                                    null),
                            0,
                            "fixture-schedule")
                    .get(5, TimeUnit.SECONDS);
            sdk.extensions()
                    .saveMcpSettings(
                            "desktop-mcp",
                            "HTTPS MCP 验收",
                            new com.javaclaw.sdk.model.McpSettingsInfo(
                                    "http",
                                    "https://example.test/mcp",
                                    java.util.Set.of("example.test"),
                                    "none",
                                    "",
                                    "",
                                    "",
                                    java.util.Set.of(),
                                    30_000,
                                    4_194_304,
                                    workspace.id()),
                            false,
                            0,
                            "fixture-mcp")
                    .get(5, TimeUnit.SECONDS);
            sdk.threads()
                    .start(workspace.id(), "你好\n你好", "fixture-sidebar-single-line")
                    .get(5, TimeUnit.SECONDS);
            var thread = sdk.threads()
                    .start(workspace.id(), "原界面 · 消息验收", "fixture-thread")
                    .get(5, TimeUnit.SECONDS);
            var turn = sdk.threads()
                    .startTurn(new com.javaclaw.sdk.model.TurnStartRequest(
                            thread.id(),
                            profile.id(),
                            List.of(new com.javaclaw.sdk.model.TurnInput.Text("展示当前桌面的原风格与架构验收说明。")),
                            com.javaclaw.sdk.model.TurnStartRequest.ApprovalMode.DENY_ALL,
                            com.javaclaw.sdk.model.TurnStartRequest.ReasoningMode.DISABLED,
                            "fixture-turn"))
                    .get(5, TimeUnit.SECONDS);
            var transcript = sdk.threads()
                    .awaitTurn(thread.id(), turn.id(), Duration.ofSeconds(15))
                    .get(18, TimeUnit.SECONDS);
            if (transcript.turns().stream().noneMatch(value -> "COMPLETED".equals(value.status()))
                    || transcript.items().stream()
                            .noneMatch(item -> item.content() instanceof com.javaclaw.sdk.model.TextItemContent text
                                    && text.kind().equals("agentMessage")
                                    && DesktopModelFixture.ANSWER
                                            .strip()
                                            .equals(text.text().strip()))) {
                throw new IllegalStateException("真实流式 Provider 链路未产生预期的持久 Markdown 消息："
                        + transcript.turns().stream()
                                .map(value -> value.status() + ":" + value.error())
                                .toList()
                        + transcript.items().stream()
                                .filter(value -> value.content() instanceof com.javaclaw.sdk.model.ErrorItemContent)
                                .map(value -> value.content().document().canonicalJson())
                                .toList());
            }
            fixture.verify();
            sdk.models()
                    .clearCredential("openai", credential.revision(), "fixture-key-clear")
                    .get(5, TimeUnit.SECONDS);
        }
    }

    private static void observe(AtomicReference<List<ProcessHandle>> owned) {
        long deadline = System.nanoTime() + Duration.ofSeconds(75).toNanos();
        try {
            while (System.nanoTime() < deadline) {
                CompletableFuture<Boolean> ready = new CompletableFuture<>();
                try {
                    Platform.runLater(() -> {
                        try {
                            ready.complete(checkWindow(owned));
                        } catch (Exception failure) {
                            ready.completeExceptionally(failure);
                        }
                    });
                } catch (IllegalStateException starting) {
                    Thread.sleep(50);
                    continue;
                }
                // Retina 截图和复杂管理页的一次 JavaFX pulse 可能超过两秒；总流程仍受 75 秒硬截止约束。
                if (ready.get(10, TimeUnit.SECONDS)) {
                    return;
                }
                Thread.sleep(50);
            }
            throw new IllegalStateException("等待桌面窗口就绪超时");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception failure) {
            failure.printStackTrace(System.err);
            System.exit(2);
        }
    }

    private static boolean checkWindow(AtomicReference<List<ProcessHandle>> owned) throws Exception {
        for (Window window : Window.getWindows()) {
            if (!(window instanceof Stage stage) || !stage.isShowing() || !"JavaClaw 4.0".equals(stage.getTitle())) {
                continue;
            }
            var root = stage.getScene().getRoot();
            Label connection = (Label) root.lookup("#connectionLabel");
            Label error = (Label) root.lookup("#errorLabel");
            ComboBox<?> profiles = (ComboBox<?>) root.lookup("#profileBox");
            if (error != null && error.getText() != null && !error.getText().isBlank()) {
                throw new IllegalStateException(error.getText());
            }
            if (!applicationReady) {
                if (connection == null
                        || !"已连接".equals(connection.getText())
                        || profiles == null
                        || profiles.getItems().isEmpty()) {
                    return false;
                }
                applicationReady = true;
            }
            verifyComposerStructure(root, profiles);
            owned.set(ProcessHandle.current().children().toList());
            if (page < 0) {
                if (!verifyManagementMenu(root)) {
                    return false;
                }
                var threads = (ListView<?>) root.lookup("#threadList");
                var transcript = (ListView<?>) root.lookup("#transcriptList");
                if (!conversationSelected) {
                    if (threads.getItems().isEmpty()) {
                        return false;
                    }
                    boolean compactMultilineTitle = threads.lookupAll(".sidebar-conv-title").stream()
                            .filter(Label.class::isInstance)
                            .map(Label.class::cast)
                            .anyMatch(label -> "你好 你好".equals(label.getText()));
                    if (!compactMultilineTitle) {
                        throw new IllegalStateException("会话标题未按旧侧栏约束为单行");
                    }
                    conversationSelected = true;
                    threads.getSelectionModel().selectFirst();
                    openedAt = System.nanoTime();
                    return false;
                }
                if (transcript.getItems().size() < 2
                        || System.nanoTime() - openedAt < TimeUnit.MILLISECONDS.toNanos(500)) {
                    return false;
                }
                if (transcript.lookupAll(".md-bubble-host").isEmpty()) {
                    throw new IllegalStateException("助手消息未使用 Markdown 渲染器");
                }
                if (!cycleThemes(stage)) {
                    return false;
                }
                if (captureSequence == null) {
                    validateSurface(root, Set.of("JavaClaw", "发送"));
                }
                if (!captureSizes(
                        stage,
                        List.of(
                                new CaptureTarget(1200, 700, ""),
                                new CaptureTarget(1100, 700, "-standard"),
                                new CaptureTarget(600, 500, "-min")))) {
                    return false;
                }
                System.out.println("JAVACLAW_DESKTOP_TRANSCRIPT_OK");
                page = 0;
                openPage(stage);
                return false;
            }
            Stage management = Window.getWindows().stream()
                    .filter(value -> value instanceof Stage other
                            && other.isShowing()
                            && other.getTitle().startsWith("JavaClaw · "))
                    .map(Stage.class::cast)
                    .findFirst()
                    .orElse(null);
            if (management == null || System.nanoTime() - openedAt < TimeUnit.MILLISECONDS.toNanos(150)) {
                return false;
            }
            var content = management.getScene().getRoot();
            if (dangerDialogRequested && !dangerDialogCaptured) {
                Stage confirmation = Window.getWindows().stream()
                        .filter(value ->
                                value instanceof Stage other && other.isShowing() && "删除知识源".equals(other.getTitle()))
                        .map(Stage.class::cast)
                        .findFirst()
                        .orElse(null);
                if (confirmation == null) {
                    return false;
                }
                validateDangerConfirmation(confirmation);
                capture(confirmation, "-state-danger-confirmation");
                Button cancel = (Button) confirmation.getScene().getRoot().lookup("#dialog-cancel_close");
                if (cancel == null) {
                    throw new IllegalStateException("危险确认框缺少可访问的取消操作");
                }
                dangerDialogCaptured = true;
                dangerDialogRequested = false;
                cancel.fire();
                openedAt = System.nanoTime();
                return false;
            }
            var progress = (ProgressIndicator) content.lookup("#busy");
            if (progress != null && progress.isVisible()) {
                return false;
            }
            var status = (Label) content.lookup("#status");
            if (status != null && status.getText().contains("失败")) {
                throw new IllegalStateException(status.getText());
            }
            if (!selected) {
                selected = true;
                var list = content.lookupAll(".list-view").stream()
                        .filter(ListView.class::isInstance)
                        .map(ListView.class::cast)
                        .filter(value -> value.getStyleClass().contains("management-list"))
                        .filter(value -> insideStyle(value, "management-rail"))
                        .filter(value -> !value.getItems().isEmpty())
                        .findFirst();
                if (list.isPresent()) {
                    list.get().getSelectionModel().selectFirst();
                }
                openedAt = System.nanoTime();
                return false;
            }
            DesktopFeatureCatalog.Page current = PAGES.get(page);
            if ("知识中心".equals(current.title()) && !dangerDialogCaptured) {
                if (!dangerDialogRequested) {
                    dangerDialogRequested = true;
                    Platform.runLater(() -> findButton(content, "删除").fire());
                }
                return false;
            }
            Set<String> required = current.requiredText();
            if ("设置".equals(current.title())) {
                required = switch (settingsSection) {
                    case 0 -> current.requiredText();
                    case 1 -> Set.of("对话模型", "保存模型配置");
                    default -> Set.of("App Server 连接", "刷新诊断", "导出诊断包");
                };
                if (settingsSection == 1 && !exerciseProviderSettings(management, content, status, profiles)) {
                    return false;
                }
                if (settingsSection == 2 && !diagnosticsRefreshed) {
                    diagnosticsRefreshed = true;
                    findButton(content, "刷新诊断").fire();
                    openedAt = System.nanoTime();
                    return false;
                }
            }
            validateSurface(content, required);
            String suffix = "-" + String.format("%02d", page + 1)
                    + ("设置".equals(current.title()) ? "-settings-" + settingsSection : "");
            if (!captureSizes(
                    management,
                    List.of(
                            new CaptureTarget(current.preferredWidth(), current.preferredHeight(), suffix),
                            new CaptureTarget(current.minimumWidth(), current.minimumHeight(), suffix + "-min")))) {
                return false;
            }
            if ("设置".equals(current.title()) && settingsSection < 2) {
                settingsSection++;
                settingsSections(content).getSelectionModel().select(settingsSection);
                openedAt = System.nanoTime();
                return false;
            }
            System.out.println("JAVACLAW_DESKTOP_PAGE_OK " + current.title());
            management.hide();
            if (++page == PAGES.size()) {
                Platform.exit();
                return true;
            }
            openPage(stage);
            return false;
        }
        return false;
    }

    private static boolean verifyManagementMenu(Parent root) throws Exception {
        if (managementMenuVerified) {
            return true;
        }
        MenuButton menu = (MenuButton) root.lookup("#managementMenu");
        if (menu == null) {
            throw new IllegalStateException("侧栏缺少设置与管理菜单");
        }
        List<String> actual = menu.getItems().stream()
                .filter(item -> !(item instanceof SeparatorMenuItem))
                .map(MenuItem::getText)
                .toList();
        List<String> expected = List.of(
                "⚙  设置",
                "📚  知识",
                "🧠  记忆",
                "⚙  技能",
                "⎇  自动化",
                "⏰  定时任务",
                "🧩  插件",
                "🔌  MCP",
                "Agent Studio",
                "站点管理",
                "项目约定",
                "协作与工作树恢复");
        if (!expected.equals(actual) || menu.getPopupSide() != Side.TOP) {
            throw new IllegalStateException("设置浮层菜单结构不正确：" + actual);
        }
        if (!managementMenuRequested) {
            managementMenuRequested = true;
            menu.show();
            return false;
        }
        if (!menu.isShowing()) {
            throw new IllegalStateException("设置浮层菜单未能打开");
        }
        capturePopup(menu, "-state-settings-menu");
        menu.hide();
        managementMenuVerified = true;
        System.out.println("JAVACLAW_DESKTOP_SETTINGS_MENU_OK");
        return true;
    }

    private static void verifyComposerStructure(Parent root, ComboBox<?> profiles) {
        Node card = root.lookup("#composerCard");
        if (!(card instanceof Parent)) {
            throw new IllegalStateException("主窗口缺少一体化输入卡片");
        }
        for (String id : List.of(
                "composer",
                "attachmentPreview",
                "composerToolbar",
                "attachButton",
                "profileBox",
                "interruptButton",
                "sendButton")) {
            Node control = root.lookup("#" + id);
            if (control == null || !insideStyle(control, "composer-card")) {
                throw new IllegalStateException(id + " 未被收进一体化输入卡片");
            }
        }
        if (!profiles.getStyleClass().contains("composer-profile-select")) {
            throw new IllegalStateException("运行模式选择没有使用输入卡片内的紧凑样式");
        }
        if (profiles.getPrefWidth() > 120 || profiles.getMaxWidth() > 120) {
            throw new IllegalStateException("运行模式选择宽度没有保持紧凑：" + profiles.getPrefWidth());
        }
        if (profiles.getValue() instanceof com.javaclaw.sdk.model.ProfileInfo selectedProfile) {
            String visibleMode = profiles.getButtonCell() == null
                    ? null
                    : profiles.getButtonCell().getText();
            String expectedMode = profileMode(selectedProfile);
            if (!expectedMode.equals(visibleMode) || visibleMode.contains(selectedProfile.model())) {
                throw new IllegalStateException("输入区应显示中文模式且不显示模型名称：" + visibleMode);
            }
        }
    }

    /** 将输入区支持的 Profile 类型转换为稳定的中文模式名称。 */
    private static String profileMode(com.javaclaw.sdk.model.ProfileInfo profile) {
        return switch (profile.kind()) {
            case "CHAT" -> "对话";
            case "PLAN" -> "规划";
            default -> throw new IllegalStateException("输入区出现不支持的运行模式：" + profile.kind());
        };
    }

    /** 通过真实可见控件完成模型配置校验、保存和凭据写入；每一步都等待 SDK 主链结束后再继续。 */
    private static boolean exerciseProviderSettings(
            Stage management, Parent content, Label status, ComboBox<?> profiles) throws Exception {
        TextInputControl model = findInput(content, "对话模型");
        TextInputControl embedding = findInput(content, "Embedding 模型（可选）");
        TextInputControl baseUrl = findInput(content, "留空使用 Provider 官方地址");
        TextInputControl secret = findInput(content, "仅输入新的 API Key；不会显示已保存的值");
        if (model == null || embedding == null || baseUrl == null || secret == null) {
            return false;
        }
        if (providerScenarioStep == 0) {
            model.clear();
            findButton(content, "保存模型配置").fire();
            providerScenarioStep = 1;
            return false;
        }
        if (providerScenarioStep == 1) {
            if (status == null || !status.getText().contains("对话模型不能为空")) {
                return false;
            }
            validateFeedback(status, "management-feedback-validation_error");
            capture(management, "-state-validation-error");
            model.setText(UI_PROVIDER_MODEL);
            embedding.setText(UI_PROVIDER_EMBEDDING);
            baseUrl.setText(UI_PROVIDER_BASE_URL);
            findButton(content, "保存模型配置").fire();
            validateFeedback(status, "management-feedback-running");
            ProgressIndicator progress = (ProgressIndicator) content.lookup("#busy");
            if (progress == null || !progress.isVisible()) {
                throw new IllegalStateException("异步保存没有显示进行中状态");
            }
            capture(management, "-state-loading");
            providerScenarioStep = 2;
            return false;
        }
        if (providerScenarioStep == 2) {
            if (!UI_PROVIDER_MODEL.equals(model.getText())
                    || !UI_PROVIDER_EMBEDDING.equals(embedding.getText())
                    || !UI_PROVIDER_BASE_URL.equals(baseUrl.getText())) {
                return false;
            }
            boolean profileSynchronized = profiles.getItems().stream()
                    .filter(com.javaclaw.sdk.model.ProfileInfo.class::isInstance)
                    .map(com.javaclaw.sdk.model.ProfileInfo.class::cast)
                    .filter(value -> "profile_chat".equals(value.id()))
                    .anyMatch(value -> UI_PROVIDER_MODEL.equals(value.model()));
            if (!profileSynchronized
                    || !(profiles.getValue() instanceof com.javaclaw.sdk.model.ProfileInfo selectedProfile)
                    || !UI_PROVIDER_MODEL.equals(selectedProfile.model())) {
                return false;
            }
            if (profiles.getButtonCell() == null
                    || !profileMode(selectedProfile)
                            .equals(profiles.getButtonCell().getText())) {
                throw new IllegalStateException("Provider 模型同步后，输入区应继续只显示运行模式");
            }
            secret.setText(UI_PROVIDER_SECRET);
            findButton(content, "保存新凭据").fire();
            providerScenarioStep = 3;
            return false;
        }
        if (providerScenarioStep == 3) {
            boolean configured = nodes(content).stream()
                    .filter(Labeled.class::isInstance)
                    .map(Labeled.class::cast)
                    .map(Labeled::getText)
                    .anyMatch(value -> value != null && value.contains("凭据已配置"));
            if (!configured) {
                return false;
            }
            validateFeedback(status, "management-feedback-success");
            capture(management, "-state-success");
            providerScenarioStep = 4;
            System.out.println("JAVACLAW_DESKTOP_PROVIDER_UI_OK");
        }
        return true;
    }

    private static void validateFeedback(Label status, String expectedStyle) {
        if (status == null || !status.getStyleClass().contains(expectedStyle)) {
            throw new IllegalStateException("管理反馈缺少严重级别样式：" + expectedStyle);
        }
    }

    private static void validateDangerConfirmation(Stage confirmation) {
        Parent root = confirmation.getScene().getRoot();
        root.applyCss();
        root.layout();
        Button accept = (Button) root.lookup("#dialog-ok_done");
        if (accept == null
                || !"删除知识源".equals(accept.getAccessibleText())
                || !accept.getStyleClass().contains("jc-btn-danger")) {
            throw new IllegalStateException("危险确认框没有保留危险操作文案、可访问名称或 danger 样式");
        }
    }

    private static TextInputControl findInput(Parent root, String accessibleOrPrompt) {
        return nodes(root).stream()
                .filter(TextInputControl.class::isInstance)
                .map(TextInputControl.class::cast)
                .filter(Node::isVisible)
                .filter(value -> accessibleOrPrompt.equals(value.getAccessibleText())
                        || accessibleOrPrompt.equals(value.getPromptText()))
                .findFirst()
                .orElse(null);
    }

    private static void openPage(Stage stage) {
        selected = false;
        settingsSection = 0;
        providerScenarioStep = 0;
        diagnosticsRefreshed = false;
        openedAt = System.nanoTime();
        var root = stage.getScene().getRoot();
        String title = PAGES.get(page).title();
        var direct = root.lookupAll(".button").stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(Node::isVisible)
                .filter(value -> title.equals(value.getText())
                        || (value.getAccessibleText() != null
                                && value.getAccessibleText().contains(title)))
                .findFirst();
        if (direct.isPresent()) {
            direct.get().fire();
        } else if (title.equals("设置")) {
            root.lookupAll(".button").stream()
                    .filter(Button.class::isInstance)
                    .map(Button.class::cast)
                    .filter(value -> value.getText().contains("设置"))
                    .findFirst()
                    .orElseThrow()
                    .fire();
        } else {
            String expectedMenuLabel = MANAGEMENT_MENU_LABELS.get(title);
            root.lookupAll(".menu-button").stream()
                    .filter(MenuButton.class::isInstance)
                    .map(MenuButton.class::cast)
                    .flatMap(value -> value.getItems().stream())
                    .filter(value -> expectedMenuLabel.equals(value.getText()))
                    .findFirst()
                    .orElseThrow()
                    .fire();
        }
    }

    private static boolean cycleThemes(Stage stage) throws Exception {
        var root = stage.getScene().getRoot();
        if (theme >= THEMES.size()) {
            if (!themeRestoreRequested) {
                MenuButton menu = (MenuButton) root.lookup("#themeMenu");
                menu.getItems().getFirst().fire();
                themeRestoreRequested = true;
                openedAt = System.nanoTime();
                return false;
            }
            return System.nanoTime() - openedAt >= TimeUnit.MILLISECONDS.toNanos(180)
                    && root.getStyleClass().contains("theme-" + THEMES.getFirst());
        }
        String id = THEMES.get(theme);
        if (!themeApplied) {
            MenuButton menu = (MenuButton) root.lookup("#themeMenu");
            if (menu == null || menu.getItems().size() != THEMES.size()) {
                throw new IllegalStateException("主题菜单未完整提供九个原版主题");
            }
            menu.getItems().get(theme).fire();
            themeApplied = true;
            openedAt = System.nanoTime();
            return false;
        }
        if (System.nanoTime() - openedAt < TimeUnit.MILLISECONDS.toNanos(180)
                || !root.getStyleClass().contains("theme-" + id)) {
            return false;
        }
        validateSurface(root, Set.of("JavaClaw", "发送"));
        capture(stage, "-theme-" + id);
        System.out.println("JAVACLAW_DESKTOP_THEME_OK " + id);
        theme++;
        themeApplied = false;
        return false;
    }

    private static Button findButton(Parent root, String text) {
        return nodes(root).stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(Node::isVisible)
                .filter(value -> text.equals(value.getText()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("未找到操作：" + text));
    }

    @SuppressWarnings("unchecked")
    private static ListView<Object> settingsSections(Parent root) {
        return (ListView<Object>) nodes(root).stream()
                .filter(ListView.class::isInstance)
                .map(ListView.class::cast)
                .filter(value -> !value.getItems().isEmpty())
                .filter(value ->
                        value.getItems().getFirst().getClass().getName().contains("SettingsPane$Section"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("设置导航未使用原版 Master/Detail 结构"));
    }

    private static void validateSurface(Parent root, Set<String> requiredText) {
        root.applyCss();
        root.layout();
        List<Node> nodes = nodes(root);
        List<String> visible = nodes.stream()
                .filter(Node::isVisible)
                .map(DesktopLaunchProbe::displayText)
                .filter(value -> !value.isBlank())
                .toList();
        String combined = String.join("\n", visible);
        for (String forbidden : List.of(
                "null",
                "DEGRADED_EMBEDDING_UNAVAILABLE",
                "HOST_FULL_ACCESS",
                "WORKSPACE_WRITE",
                "desktop-fixture-not-a-real-key")) {
            if (combined.contains(forbidden)) {
                throw new IllegalStateException("界面泄漏内部值或凭据：" + forbidden + "\n" + combined);
            }
        }
        for (String expected : requiredText) {
            if (!combined.contains(expected)) {
                throw new IllegalStateException("页面缺少功能证据“" + expected + "”：\n" + combined);
            }
        }
        for (Button button : nodes.stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(Node::isVisible)
                .toList()) {
            if (button.getAccessibleText() == null || button.getAccessibleText().isBlank()) {
                throw new IllegalStateException("按钮缺少 accessibleText：" + button.getText());
            }
            String text = button.getText() == null ? "" : button.getText();
            if (List.of("删除", "清除", "撤销", "卸载", "移除", "中断").stream().anyMatch(text::contains)
                    && !button.getStyleClass().contains("jc-btn-danger")) {
                throw new IllegalStateException("危险操作未使用原版 danger 语义：" + text);
            }
        }
    }

    private static String displayText(Node value) {
        if (value instanceof Labeled labeled) {
            return labeled.getText() == null ? "" : labeled.getText();
        }
        if (value instanceof TextInputControl input) {
            String text = input.getText() == null ? "" : input.getText();
            String prompt = input.getPromptText() == null ? "" : input.getPromptText();
            return text + "\n" + prompt;
        }
        return "";
    }

    private static List<Node> nodes(Parent root) {
        ArrayList<Node> result = new ArrayList<>();
        collect(root, result);
        return List.copyOf(result);
    }

    private static boolean insideStyle(Node value, String styleClass) {
        for (Node parent = value.getParent(); parent != null; parent = parent.getParent()) {
            if (parent.getStyleClass().contains(styleClass)) {
                return true;
            }
        }
        return false;
    }

    private static void collect(Node value, List<Node> result) {
        result.add(value);
        if (value instanceof Parent parent) {
            parent.getChildrenUnmodifiable().forEach(child -> collect(child, result));
        }
    }

    private static boolean captureSizes(Stage stage, List<CaptureTarget> targets) throws Exception {
        if (System.getenv("JAVACLAW_TEST_SCREENSHOT") == null) {
            return true;
        }
        if (captureSequence == null) {
            captureSequence =
                    new CaptureSequence(stage, targets, stage.isMaximized(), stage.getWidth(), stage.getHeight());
            hideTransientToast(stage);
            captureSequence.applyTarget();
            return false;
        }
        if (captureSequence.stage != stage || !captureSequence.targets.equals(targets)) {
            throw new IllegalStateException("截图序列尚未完成，不能切换目标窗口");
        }
        if (captureSequence.restoring) {
            if (Math.abs(stage.getWidth() - captureSequence.width) > 1
                    || Math.abs(stage.getHeight() - captureSequence.height) > 1) {
                captureSequence.restorePulses = 0;
                captureSequence.restore();
                return false;
            }
            if (++captureSequence.restorePulses < 2) {
                return false;
            }
            captureSequence = null;
            return true;
        }
        CaptureTarget target = captureSequence.current();
        if (Math.abs(stage.getWidth() - target.width()) > 1 || Math.abs(stage.getHeight() - target.height()) > 1) {
            captureSequence.stablePulses = 0;
            if (++captureSequence.resizeAttempts > 20) {
                throw new IllegalStateException("目标窗口尺寸未生效：target="
                        + target.width()
                        + "×"
                        + target.height()
                        + " actual="
                        + stage.getWidth()
                        + "×"
                        + stage.getHeight());
            }
            captureSequence.applyTarget();
            return false;
        }
        captureSequence.resizeAttempts = 0;
        if (!captureSequence.contentPositioned) {
            Parent root = stage.getScene().getRoot();
            root.applyCss();
            root.layout();
            Node transcript = root.lookup("#transcriptList");
            if (transcript instanceof ListView<?> list && !list.getItems().isEmpty()) {
                list.scrollTo(0);
            }
            // 截图时统一把焦点停在无样式的根节点，避免窗口激活顺序随机突出 Button 或 ComboBox。
            root.setFocusTraversable(true);
            root.requestFocus();
            captureSequence.contentPositioned = true;
            captureSequence.stablePulses = 0;
            return false;
        }
        if (++captureSequence.stablePulses < 2) {
            return false;
        }
        stage.getScene().getRoot().applyCss();
        stage.getScene().getRoot().layout();
        validateFixedActions(stage);
        validateComposerLayout(stage);
        capture(stage, target.suffix());
        captureSequence.index++;
        captureSequence.stablePulses = 0;
        if (captureSequence.index < captureSequence.targets.size()) {
            captureSequence.applyTarget();
            return false;
        }
        captureSequence.restoring = true;
        captureSequence.restore();
        return false;
    }

    private static void hideTransientToast(Stage stage) {
        Node toast = stage.getScene().getRoot().lookup("#windowToast");
        if (toast != null) {
            toast.setVisible(false);
            toast.setManaged(false);
            toast.setOpacity(1);
        }
    }

    private static void validateFixedActions(Stage stage) {
        for (Node footer : stage.getScene().getRoot().lookupAll(".management-fixed-actions")) {
            var bounds = footer.localToScene(footer.getBoundsInLocal());
            if (!footer.isVisible()
                    || bounds.getWidth() <= 0
                    || bounds.getHeight() <= 0
                    || bounds.getMinX() < -1
                    || bounds.getMaxX() > stage.getScene().getWidth() + 1
                    || bounds.getMinY() < -1
                    || bounds.getMaxY() > stage.getScene().getHeight() + 1) {
                throw new IllegalStateException("长表单固定操作栏在当前窗口不可达：" + bounds);
            }
        }
    }

    private static void validateComposerLayout(Stage stage) {
        Parent root = stage.getScene().getRoot();
        Node card = root.lookup("#composerCard");
        if (card == null) {
            return;
        }
        var cardBounds = card.localToScene(card.getBoundsInLocal());
        if (!card.isVisible() || cardBounds.getWidth() < 300 || cardBounds.getHeight() < 100) {
            throw new IllegalStateException("一体化输入卡片在当前窗口不可达：" + cardBounds);
        }
        for (String id : List.of("composer", "composerToolbar", "attachButton", "profileBox", "sendButton")) {
            Node control = root.lookup("#" + id);
            var bounds = control.localToScene(control.getBoundsInLocal());
            if (!control.isVisible()
                    || bounds.getMinX() < cardBounds.getMinX() - 1
                    || bounds.getMaxX() > cardBounds.getMaxX() + 1
                    || bounds.getMinY() < cardBounds.getMinY() - 1
                    || bounds.getMaxY() > cardBounds.getMaxY() + 1) {
                throw new IllegalStateException(id + " 超出一体化输入卡片：" + bounds + " card=" + cardBounds);
            }
        }
    }

    private static void capture(Stage stage, String suffix) throws Exception {
        String screenshot = System.getenv("JAVACLAW_TEST_SCREENSHOT");
        if (screenshot == null) {
            return;
        }
        normalizeCaptureFocus(stage);
        Path path = Path.of(screenshot);
        if (!suffix.isEmpty()) {
            path = path.resolveSibling(path.getFileName().toString().replaceFirst("\\.png$", "") + suffix + ".png");
        }
        if (path.getParent() != null) {
            java.nio.file.Files.createDirectories(path.getParent());
        }
        var snapshot = stage.getScene().snapshot(null);
        int width = (int) snapshot.getWidth();
        int height = (int) snapshot.getHeight();
        if (width != Math.round(stage.getScene().getWidth())
                || height != Math.round(stage.getScene().getHeight())) {
            throw new IllegalStateException("截图像素尺寸与已布局 Scene 不一致：" + width + "×" + height);
        }
        int[] pixels = new int[width * height];
        snapshot.getPixelReader().getPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), pixels, 0, width);
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, width, height, pixels, 0, width);
        ImageIO.write(image, "png", path.toFile());
    }

    private static void normalizeCaptureFocus(Stage stage) {
        Parent root = stage.getScene().getRoot();
        root.setFocusTraversable(true);
        root.requestFocus();
        clearTransientPointerState(root);
        root.applyCss();
        root.layout();
    }

    /** 清除实体鼠标位置造成的 hover/pressed 状态，保证无人值守截图只记录稳定控件状态。 */
    private static void clearTransientPointerState(Node node) {
        node.pseudoClassStateChanged(HOVER, false);
        node.pseudoClassStateChanged(PRESSED, false);
        if (node instanceof Parent parent) {
            parent.getChildrenUnmodifiable().forEach(DesktopLaunchProbe::clearTransientPointerState);
        }
    }

    private static void capturePopup(MenuButton menu, String suffix) throws Exception {
        String screenshot = System.getenv("JAVACLAW_TEST_SCREENSHOT");
        if (screenshot == null) {
            return;
        }
        var popup = menu.getItems().getFirst().getParentPopup();
        if (popup == null || !popup.isShowing() || popup.getScene() == null) {
            throw new IllegalStateException("设置浮层尚未完成可视布局");
        }
        clearTransientPointerState(popup.getScene().getRoot());
        popup.getScene().getRoot().applyCss();
        popup.getScene().getRoot().layout();
        Path path = Path.of(screenshot)
                .resolveSibling(
                        Path.of(screenshot).getFileName().toString().replaceFirst("\\.png$", "") + suffix + ".png");
        if (path.getParent() != null) {
            java.nio.file.Files.createDirectories(path.getParent());
        }
        var snapshot = popup.getScene().snapshot(null);
        int width = (int) snapshot.getWidth();
        int height = (int) snapshot.getHeight();
        if (width <= 0 || height <= 0) {
            throw new IllegalStateException("设置浮层截图尺寸无效：" + width + "×" + height);
        }
        int[] pixels = new int[width * height];
        snapshot.getPixelReader().getPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), pixels, 0, width);
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, width, height, pixels, 0, width);
        ImageIO.write(image, "png", path.toFile());
    }

    private record CaptureTarget(int width, int height, String suffix) {}

    private static final class CaptureSequence {
        private final Stage stage;
        private final List<CaptureTarget> targets;
        private final boolean maximized;
        private final double width;
        private final double height;
        private int index;
        private int stablePulses;
        private int resizeAttempts;
        private boolean restoring;
        private int restorePulses;
        private boolean contentPositioned;

        private CaptureSequence(
                Stage stage, List<CaptureTarget> targets, boolean maximized, double width, double height) {
            this.stage = stage;
            this.targets = List.copyOf(targets);
            this.maximized = maximized;
            this.width = width;
            this.height = height;
        }

        private CaptureTarget current() {
            return targets.get(index);
        }

        private void applyTarget() {
            CaptureTarget target = current();
            contentPositioned = false;
            stage.setMaximized(false);
            stage.setWidth(target.width());
            stage.setHeight(target.height());
        }

        private void restore() {
            stage.setWidth(width);
            stage.setHeight(height);
            stage.setMaximized(maximized);
        }
    }
}
