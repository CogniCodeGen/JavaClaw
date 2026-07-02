package com.javaclaw.app;

import com.javaclaw.agent.AgentRuntime;
import com.javaclaw.agent.ChatService;
import com.javaclaw.agent.PlanModeService;
import com.javaclaw.agent.ToolConfirmationManager;
import com.javaclaw.api.conversation.ModeRegistry;
import com.javaclaw.browser.PlaywrightBrowserManager;
import com.javaclaw.chat.ChatViewController;
import com.javaclaw.config.DataManager;
import com.javaclaw.config.WorkspaceManager;
import com.javaclaw.mode.ChatMode;
import com.javaclaw.mode.PlanMode;
import com.javaclaw.mode.TaskMode;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.onboarding.OnboardingWizard;
import com.javaclaw.schedule.ScheduleManager;
import com.javaclaw.skill.SkillManager;
import com.javaclaw.task.sdd.run.SddTaskManager;
import com.javaclaw.ui.javafx.task.SddTaskView;
import com.javaclaw.ui.javafx.JfxUserInteractionPort;
import com.javaclaw.ui.javafx.SystemTrayManager;

import java.util.Set;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JavaClaw 主应用类
 *
 * <p>继承 JavaFX {@link Application}，负责：
 * <ul>
 *   <li>初始化工作区管理器</li>
 *   <li>启动 Playwright 浏览器实例</li>
 *   <li>初始化智能体服务</li>
 *   <li>构建并显示聊天窗口</li>
 *   <li>应用关闭时清理资源</li>
 * </ul>
 * </p>
 *
 * <p>注意：非模块化 JavaFX 项目中，不能直接以 Application 子类作为启动入口，
 * 需通过 {@link Launcher} 类间接启动。</p>
 *
 * @author JavaClaw
 * @see Launcher
 */
public class JavaClawApp extends Application {

    private static final Logger log = LoggerFactory.getLogger(JavaClawApp.class);

    /** 聊天界面控制器（持有三模式服务引用） */
    private ChatViewController chatView;

    /** Playwright 浏览器管理器 */
    private PlaywrightBrowserManager browserManager;

    /** 共享基础设施容器（启动时创建，应用退出时关闭） */
    private AgentRuntime runtime;

    /** 普通聊天模式服务 */
    private ChatService chatService;

    /** 规划模式服务 */
    private PlanModeService planModeService;

    /** 模式注册表（对话 / 动作模式统一入口，支持运行期扩展或禁用） */
    private ModeRegistry modeRegistry;

    /** 主窗口引用（托盘恢复/隐藏时使用） */
    private Stage primaryStage;

    /** 系统托盘管理器（后台常驻）；平台不支持时为 null。退出 worker 线程会读取，故 volatile */
    private volatile SystemTrayManager trayManager;

    /** 是否已弹出过"最小化到托盘"提示气泡（每次运行只提示一次） */
    private boolean trayHintShown;

    /** 退出只触发一次（托盘退出与窗口关闭可能并发） */
    private final java.util.concurrent.atomic.AtomicBoolean exitInitiated =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** 资源清理只执行一次（worker 路径与 JavaFX stop() 路径共用） */
    private final java.util.concurrent.atomic.AtomicBoolean resourcesReleased =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * JavaFX 应用启动方法
     *
     * <p>在 JavaFX Application Thread 上执行，依次完成：
     * 初始化工作区 → 启动浏览器 → 初始化服务 → 构建 UI → 加载样式 → 显示窗口</p>
     *
     * @param primaryStage 主窗口舞台
     */
    @Override
    public void start(Stage primaryStage) {
        log.info("========== JavaClaw 应用启动 ==========");

        try {
            // 0. 初始化工作区管理器（必须在所有配置加载前完成）
            log.info("正在初始化工作区管理器...");
            WorkspaceManager.getInstance().init();

            // 0.1 注册打包字体（须在创建任何 Scene 之前；下方首启向导即会构建 Scene）
            com.javaclaw.ui.javafx.theme.FontManager.loadBundledFonts();

            // 0.5 注入 UI 交互端口（让 ToolConfirmationManager 等领域层能请求确认/通知，
            //     而不直接依赖 JavaFX）。未来接入 Web 前端时替换成对应的 Port 实现即可。
            log.info("正在装配 UI 交互端口（JavaFX）...");
            JfxUserInteractionPort interactionPort = new JfxUserInteractionPort();
            ToolConfirmationManager.setPort(interactionPort);

            // 1. 创建 Playwright 浏览器管理器（懒加载，首次使用浏览器工具时才启动）
            log.info("正在创建 Playwright 浏览器管理器（懒加载模式）...");
            browserManager = new PlaywrightBrowserManager(true,
                    WorkspaceManager.getInstance().getCurrentBrowserDir(),
                    DataManager.getInstance().getScreenshotsDir()
            );

            // 1.5. 首次使用向导（仅未完成时弹出，阻塞直到用户关闭）
            OnboardingWizard.showIfNeeded(primaryStage);

            // 2. 初始化共享基础设施容器（模型工厂、专家库、记忆、MCP 等）
            log.info("正在初始化 AgentRuntime 基础设施...");
            runtime = new AgentRuntime(browserManager);

            // 2.0 装配风险评估智能体：托管任务中影响范围限于任务目录的高风险工具可经其判定自动放行
            ToolConfirmationManager.setScopeAssessor(
                    new com.javaclaw.agent.risk.LlmToolScopeAssessor(
                            runtime.getModelFactory(), runtime.getTokenTracker()));

            // 2a. 创建三条路径的独立服务（平行关系，互不持有）
            log.info("正在创建普通聊天模式服务...");
            chatService = new ChatService(runtime);
            log.info("正在创建规划模式服务...");
            planModeService = new PlanModeService(runtime);

            // 2b. 创建模式注册表并注册三种内置模式
            //     未来扩展新模式：实现 ConversationMode/ActionMode 并在此注册即可
            //     禁用内置模式：扩展配置项后把 id 加入 disabledIds 集合
            Set<String> disabledModes = Set.of();
            modeRegistry = new ModeRegistry(disabledModes);
            modeRegistry.register(new ChatMode(chatService));
            modeRegistry.register(new PlanMode(planModeService));
            // 命令模式：确定性命令管理长任务/智能体/定时工作（与对话内的同名工具共用 Manager）
            modeRegistry.register(new com.javaclaw.mode.ShellMode(
                    new com.javaclaw.agent.ShellCommandService(chatService)));
            // TaskMode 接收一个"如何打开任务视图"的动作，JavaFX 实现为：创建 SddTaskView 并 show
            modeRegistry.register(new TaskMode(() -> new SddTaskView(primaryStage).show()));

            // 3. 初始化定时任务管理器（注入定时任务专用编排器：与交互聊天完全隔离，可并行不互扰）
            log.info("正在初始化定时任务调度...");
            ScheduleManager.getInstance().init(new com.javaclaw.agent.ScheduledTaskAgent(runtime));

            // 3a. 初始化插件系统（发现 plugins/ 插件、后台自动恢复上次启用项；能力授权经交互端口确认）
            log.info("正在初始化插件系统...");
            com.javaclaw.plugin.PluginManager.getInstance().init(runtime, interactionPort);

            // 3b. 初始化 SDD 托管任务管理器（从 runtime 取模型工厂和能力工具以创建任务智能体；
            //     注入交互端口供 PortReviewGate 评审闸门弹确认）
            log.info("正在初始化 SDD 托管任务管理器...");
            SddTaskManager.getInstance().configure(
                    DataManager.getInstance().getDataRoot(),
                    runtime.getModelFactory(),
                    runtime.getCapabilityTools(),
                    SkillManager.getInstance(),
                    interactionPort);

            // 4. 构建聊天界面
            log.info("正在构建聊天界面...");
            chatView = new ChatViewController(runtime, chatService, planModeService,
                    modeRegistry, browserManager);

            // 5. 创建场景并加载 CSS 样式
            Scene scene = new Scene(chatView.getOuterRoot(), 1200, 700);

            // 加载样式表（从 classpath 中读取）
            String cssPath = getClass().getResource("/css/chat.css") != null
                    ? getClass().getResource("/css/chat.css").toExternalForm()
                    : null;

            if (cssPath != null) {
                scene.getStylesheets().add(cssPath);
                log.info("CSS 样式表加载成功");
            } else {
                log.warn("未找到 CSS 样式表 /css/chat.css，将使用默认样式");
            }

            // 5.5 初始化主题管理器：读取工作区记忆的界面风格并对所有窗口（含后续弹窗）生效
            com.javaclaw.ui.javafx.theme.ThemeManager.init();

            // 5.6 初始化字体管理器：挂全局窗口监听 + 应用工作区记忆的字体（默认系统原生时不注入覆盖）
            com.javaclaw.ui.javafx.theme.FontManager.init();

            // 6. 配置并显示主窗口
            this.primaryStage = primaryStage;
            primaryStage.setTitle("JavaClaw 智能助手");
            primaryStage.setMinWidth(600);   // 最小宽度
            primaryStage.setMinHeight(500);  // 最小高度
            primaryStage.setScene(scene);

            // 6.5 安装系统托盘（后台常驻）：安装成功则关闭窗口最小化到托盘，
            //     应用继续在后台运行（定时任务/托管任务不中断），仅托盘"退出"才真正关闭。
            //     平台不支持或安装失败时回退为"关闭即退出"。
            setupSystemTray();
            boolean trayReady = trayManager != null && trayManager.isInstalled();
            if (trayReady) {
                // 隐藏所有窗口后 JavaFX 运行时不自动退出，保证后台常驻
                Platform.setImplicitExit(false);
            }
            primaryStage.setOnCloseRequest(event -> {
                if (trayReady && AgentConfig.getInstance().isTrayMinimizeOnClose()) {
                    event.consume();
                    hideToTray();
                } else {
                    requestFullExit();
                }
            });
            primaryStage.show();

            log.info("主窗口已显示，大小: {}x{}", 1200, 700);
            log.info("========== JavaClaw 应用启动完成 ==========");

        } catch (Exception e) {
            log.error("应用启动失败", e);
            throw new RuntimeException("JavaClaw 启动失败: " + e.getMessage(), e);
        }
    }

    /**
     * 安装系统托盘并接好菜单动作。托盘菜单动作由 {@link SystemTrayManager} 统一切回
     * JavaFX 线程执行，这里传入的 Runnable 已运行在 FX 线程上。
     */
    private void setupSystemTray() {
        try {
            trayManager = new SystemTrayManager(
                    "JavaClaw 智能助手",
                    this::showMainWindow,
                    () -> { showMainWindow(); new SddTaskView(primaryStage).showCreate(); },
                    () -> { showMainWindow(); if (chatView != null) chatView.openSettings(); },
                    this::requestFullExit);
            if (!trayManager.install()) {
                trayManager = null;
            }
        } catch (Throwable t) {
            log.warn("系统托盘初始化异常，将使用关闭即退出模式: {}", t.getMessage());
            trayManager = null;
        }
    }

    /** 从托盘恢复主窗口：显示、取消最小化并置顶。 */
    private void showMainWindow() {
        if (primaryStage == null) return;
        if (!primaryStage.isShowing()) primaryStage.show();
        if (primaryStage.isIconified()) primaryStage.setIconified(false);
        primaryStage.toFront();
        primaryStage.requestFocus();
    }

    /** 隐藏主窗口到托盘后台常驻，首次提示一次气泡。 */
    private void hideToTray() {
        if (primaryStage != null) primaryStage.hide();
        if (trayManager != null && !trayHintShown) {
            trayManager.displayInfo("JavaClaw 仍在后台运行",
                    "已最小化到系统托盘，可从托盘菜单恢复窗口或退出应用");
            trayHintShown = true;
        }
        log.info("主窗口已最小化到系统托盘，应用继续后台运行");
    }

    /**
     * 真正退出应用。
     *
     * <p>不走 {@code Platform.exit()} → {@link #stop()} 路径，而是后台线程清理后直接
     * {@link Runtime#halt(int)}，原因有二：</p>
     * <ul>
     *   <li>规避 macOS 上 AWT 托盘与 JavaFX 同时关闭时争用原生主线程导致的死锁
     *       （表现为点退出后卡住，直到看门狗强杀）；</li>
     *   <li>{@code halt} 跳过 JVM 关闭钩子（如 Playwright 驱动进程的清理钩子可能阻塞数秒），
     *       而 {@code System.exit} 会同步等待这些钩子。</li>
     * </ul>
     */
    private void requestFullExit() {
        if (!exitInitiated.compareAndSet(false, true)) return;
        log.info("收到退出请求，开始关闭应用...");

        SystemTrayManager tray = trayManager;
        boolean awtActive = tray != null;   // 托盘已安装即说明 AWT 子系统已初始化
        trayManager = null;
        if (tray != null) {
            // 在 AWT 事件线程上非阻塞地移除托盘图标（AWT 调用应在 EDT 执行；invokeLater 不阻塞）。
            // 即使来不及执行，后续 halt 也会随进程结束清掉图标。
            try {
                java.awt.EventQueue.invokeLater(tray::remove);
            } catch (Throwable ignored) {
            }
        }

        // 看门狗兜底：无论哪条路径卡住，宽限期后强制终止 JVM。
        startExitWatchdog(5000);

        if (awtActive) {
            // 托盘(AWT)已激活：后台线程清理后直接 halt，规避 macOS 上 AWT 与 JavaFX
            // 同时关闭争用原生主线程导致的死锁，并跳过可能阻塞的 JVM 关闭钩子。
            Thread worker = new Thread(() -> {
                shutdownResources();
                log.info("资源清理完成，退出进程");
                Runtime.getRuntime().halt(0);
            }, "exit-worker");
            worker.setDaemon(true);
            worker.start();
        } else {
            // 无托盘：沿用 JavaFX 优雅退出（stop() 完成清理后进程自然结束）。
            Platform.exit();
        }
    }

    /**
     * 启动退出看门狗：守护线程在宽限期后调用 {@link Runtime#halt(int)} 强制终止 JVM。
     *
     * <p>是清理逻辑卡死时的最终保障。正常情况下清理 worker 会先 {@code halt} 在看门狗触发前退出。</p>
     */
    private void startExitWatchdog(long graceMs) {
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(graceMs);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return;
            }
            log.warn("退出清理超过 {}ms 未完成，强制终止 JVM", graceMs);
            Runtime.getRuntime().halt(0);
        }, "exit-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    /**
     * JavaFX 生命周期关闭回调。仅在非托盘路径或外部触发 {@code Platform.exit()} 时进入；
     * 托盘「退出」走 {@link #requestFullExit()} 的 worker 路径，不经此处。与之共用幂等清理。
     */
    @Override
    public void stop() {
        shutdownResources();
    }

    /**
     * 释放所有资源（幂等）。各步骤独立 try/catch + 计时，任一步骤卡住或抛错都不影响其余步骤；
     * 单步耗时超过 200ms 会打日志，便于定位退出慢的根因。
     */
    private void shutdownResources() {
        if (!resourcesReleased.compareAndSet(false, true)) return;
        log.info("JavaClaw 应用正在关闭...");

        // 先排空各防抖/异步持久化队列，再关各子系统，避免退出丢最后一段数据
        if (chatView != null) safeShutdown("聊天持久化线程", chatView::shutdownPersistence);
        safeShutdown("技能使用统计", () -> com.javaclaw.skill.SkillUsageTracker.getInstance().shutdown());
        safeShutdown("技能提案队列", () -> com.javaclaw.skill.curation.SkillProposalQueue.getInstance().shutdown());
        safeShutdown("任务管理器", () -> SddTaskManager.getInstance().shutdown());
        safeShutdown("插件系统", () -> com.javaclaw.plugin.PluginManager.getInstance().shutdown());
        safeShutdown("定时任务调度器", () -> ScheduleManager.getInstance().shutdown());
        if (modeRegistry != null) safeShutdown("模式注册表", modeRegistry::shutdownAll);
        if (chatService != null) safeShutdown("普通模式服务", chatService::shutdown);
        if (planModeService != null) safeShutdown("规划模式服务", planModeService::shutdown);
        if (runtime != null) safeShutdown("AgentRuntime（MCP）", runtime::shutdown);
        if (browserManager != null) safeShutdown("Playwright 浏览器", browserManager::shutdown);

        log.info("JavaClaw 应用已关闭");
    }

    /** 执行单个清理步骤：吞异常 + 计时，单步 >200ms 记日志（定位退出慢的步骤）。 */
    private void safeShutdown(String name, Runnable action) {
        long t0 = System.currentTimeMillis();
        try {
            action.run();
        } catch (Throwable t) {
            log.warn("关闭 {} 时出错（忽略，继续退出）: {}", name, t.getMessage());
        } finally {
            long cost = System.currentTimeMillis() - t0;
            if (cost > 200) log.info("关闭 {} 耗时 {}ms", name, cost);
        }
    }

    /**
     * 应用入口方法
     *
     * <p>此方法由 {@link Launcher#main(String[])} 间接调用，
     * 不建议直接运行此类的 main 方法。</p>
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        launch(args);
    }
}
