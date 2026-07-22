package com.javaclaw.devtools;

import com.javaclaw.agent.AgentRuntime;
import com.javaclaw.agent.ChatService;
import com.javaclaw.agent.ToolConfirmationManager;
import com.javaclaw.api.interaction.ConfirmRequest;
import com.javaclaw.api.interaction.ToastRequest;
import com.javaclaw.api.interaction.UserInteractionPort;
import com.javaclaw.browser.PlaywrightBrowserManager;
import com.javaclaw.chat.ChatViewController;
import com.javaclaw.config.DataManager;
import com.javaclaw.config.SettingsView;
import com.javaclaw.config.WorkspaceManager;
import com.javaclaw.runtime.ApplicationKernel;
import com.javaclaw.ui.javafx.knowledge.KnowledgeCenterView;
import com.javaclaw.ui.javafx.memory.MemoryCenterView;
import com.javaclaw.ui.javafx.mcp.McpSettingsView;
import com.javaclaw.ui.javafx.plugin.PluginCenterView;
import com.javaclaw.ui.javafx.schedule.ScheduleView;
import com.javaclaw.ui.javafx.skill.SkillCenterView;
import com.javaclaw.ui.javafx.task.SddTaskView;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 本地文档截图导出器：启动 JavaClaw 的真实 JavaFX 界面，逐个打开主要功能窗口，
 * 使用 JavaFX snapshot 输出 PNG。用于 README / 功能文档素材生成，不参与正式产品逻辑。
 */
public final class UiScreenshotExporter {

    private static Path outDir;

    public static void main(String[] args) throws Exception {
        outDir = Path.of(args.length > 0 ? args[0] : "docs/images/screenshots");
        Files.createDirectories(outDir);
        Application.launch(ExporterApp.class, args);
    }

    public static final class ExporterApp extends Application {
        private final List<Shot> shots = new ArrayList<>();
        private AgentRuntime runtime;
        private ChatService chatService;
        private ApplicationKernel applicationKernel;

        @Override
        public void start(Stage primaryStage) {
            try {
                bootstrap(primaryStage);
                runNext(0);
            } catch (Throwable t) {
                t.printStackTrace();
                cleanupAndExit(1);
            }
        }

        private void bootstrap(Stage primaryStage) {
            WorkspaceManager.getInstance().init();
            com.javaclaw.ui.javafx.theme.FontManager.loadBundledFonts();

            UserInteractionPort port = new UserInteractionPort() {
                @Override public boolean confirm(ConfirmRequest request) { return true; }
                @Override public void notify(ToastRequest request) {
                    System.out.println("[toast] " + request.message());
                }
            };
            ToolConfirmationManager.setPort(port);

            PlaywrightBrowserManager browserManager = new PlaywrightBrowserManager(true,
                    WorkspaceManager.getInstance().getCurrentBrowserDir(),
                    DataManager.getInstance().getScreenshotsDir());
            applicationKernel = new ApplicationKernel(
                    browserManager, port, () -> new SddTaskView(primaryStage).show());
            var workspaceRuntime = applicationKernel.initialize();
            runtime = workspaceRuntime.agentRuntime();
            chatService = workspaceRuntime.chatService();

            ChatViewController chatView = new ChatViewController(applicationKernel);
            Scene scene = new Scene(chatView.getOuterRoot(), 1200, 700);
            addStyles(scene);
            com.javaclaw.ui.javafx.theme.ThemeManager.init();
            com.javaclaw.ui.javafx.theme.FontManager.init();
            primaryStage.setTitle("JavaClaw 智能助手");
            primaryStage.setScene(scene);
            primaryStage.show();

            shots.add(new Shot("01-main-chat.png", "JavaClaw 智能助手", null));
            shots.add(new Shot("02-settings.png", "设置", () -> showInternalStage(
                    new SettingsView(primaryStage, runtime.getMcpClientManager(),
                            runtime.getModelFactory(), runtime.getTokenTracker()))));
            shots.add(new Shot("03-knowledge-center.png", "知识库中心", () ->
                    new KnowledgeCenterView(primaryStage, runtime.getKnowledgeExpert(), port,
                            () -> {}, () -> {}).show()));
            shots.add(new Shot("04-memory-center.png", "记忆中心", () ->
                    new MemoryCenterView(primaryStage, chatService.getMemoryService(),
                            runtime.getKnowledgeExpert()).show()));
            shots.add(new Shot("05-skill-center.png", "技能中心", () ->
                    showInternalStage(new SkillCenterView(primaryStage))));
            shots.add(new Shot("06-task-center.png", "托管任务", () ->
                    new SddTaskView(primaryStage).show()));
            shots.add(new Shot("07-mcp-servers.png", "MCP 服务器", () -> {
                McpSettingsView view = new McpSettingsView();
                view.setMcpClientManager(runtime.getMcpClientManager());
                view.showAsWindow(primaryStage);
            }));
            shots.add(new Shot("08-schedule-center.png", "定时任务", () ->
                    showInternalStage(new ScheduleView(primaryStage))));
            shots.add(new Shot("09-plugin-center.png", "插件中心", () ->
                    showInternalStage(new PluginCenterView(primaryStage))));
        }

        private void runNext(int index) {
            if (index >= shots.size()) {
                cleanupAndExit(0);
                return;
            }
            Shot shot = shots.get(index);
            try {
                if (shot.opener != null) shot.opener.run();
            } catch (Throwable t) {
                System.err.println("打开窗口失败: " + shot.title + " — " + t.getMessage());
                t.printStackTrace();
            }

            PauseTransition wait = new PauseTransition(Duration.millis(index == 0 ? 900 : 1300));
            wait.setOnFinished(e -> {
                try {
                    Stage stage = findStage(shot.title);
                    if (stage == null) {
                        System.err.println("未找到窗口: " + shot.title);
                    } else {
                        snapshot(stage, shot.fileName);
                        if (index > 0) stage.close();
                    }
                } catch (Throwable t) {
                    System.err.println("截图失败: " + shot.title + " — " + t.getMessage());
                    t.printStackTrace();
                }
                runNext(index + 1);
            });
            wait.play();
        }

        private static Stage findStage(String title) {
            for (Window window : Window.getWindows()) {
                if (window instanceof Stage stage && title.equals(stage.getTitle())) {
                    return stage;
                }
            }
            return null;
        }

        private static void addStyles(Scene scene) {
            var chatCss = UiScreenshotExporter.class.getResource("/css/chat.css");
            if (chatCss != null) scene.getStylesheets().add(chatCss.toExternalForm());
        }

        private static void snapshot(Stage stage, String name) throws Exception {
            Scene scene = stage.getScene();
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            WritableImage img = scene.getRoot().snapshot(new SnapshotParameters(), null);
            File file = outDir.resolve(name).toFile();
            ImageIO.write(SwingFXUtils.fromFXImage(img, null), "png", file);
            System.out.println("截图: " + file.getAbsolutePath()
                    + " (" + (int) img.getWidth() + "x" + (int) img.getHeight() + ")");
        }

        private static void showInternalStage(Object view) {
            try {
                Field field = view.getClass().getDeclaredField("stage");
                field.setAccessible(true);
                Stage stage = (Stage) field.get(view);
                stage.show();
                stage.toFront();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("无法打开截图窗口: " + view.getClass().getName(), e);
            }
        }

        private void cleanupAndExit(int code) {
            try {
                if (applicationKernel != null) applicationKernel.close();
            } catch (Throwable ignore) {
            }
            Platform.exit();
            System.exit(code);
        }
    }

    private record Shot(String fileName, String title, Runnable opener) {
    }
}
