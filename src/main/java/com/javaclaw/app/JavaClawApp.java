package com.javaclaw.app;

import com.javaclaw.agent.ToolConfirmationManager;
import com.javaclaw.api.interaction.ToastRequest;
import com.javaclaw.browser.PlaywrightBrowserManager;
import com.javaclaw.chat.ChatViewController;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.runtime.ApplicationKernel;
import com.javaclaw.platform.data.DataRoot;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import com.javaclaw.platform.spring.ApplicationContexts;
import com.javaclaw.platform.spring.WorkspaceSpringContextFactory;
import com.javaclaw.ui.javafx.workflow.WorkflowView;
import com.javaclaw.ui.javafx.JfxUserInteractionPort;
import com.javaclaw.ui.javafx.SystemTrayManager;
import com.javaclaw.ui.javafx.onboarding.OnboardingViewFactory;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.awt.Desktop;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
    /** 主 FXML 及全部嵌套 Controller 的统一销毁句柄。 */
    private ViewHandle<BorderPane> chatViewHandle;

    /** 应用组合根：统一拥有浏览器、工作区运行时及依赖它们的全局管理器。 */
    private volatile ApplicationKernel applicationKernel;
    /** 进程级 Spring 组合根；必须晚于所有工作区 Context 关闭。 */
    private volatile AnnotationConfigApplicationContext springContext;
    private FxDispatcher fxDispatcher;
    /** 工作流中心为工作区级单实例，避免多窗口草稿互相覆盖。 */
    private volatile WorkflowView workflowCenterView;

    /** 主窗口引用（托盘恢复/隐藏时使用） */
    private Stage primaryStage;

    /** 系统托盘管理器（后台常驻）；平台不支持时为 null。退出 worker 线程会读取，故 volatile */
    private volatile SystemTrayManager trayManager;

    /** 是否已弹出过"最小化到托盘"提示气泡（每次运行只提示一次） */
    private boolean trayHintShown;

    /** 关闭事件异步确认托盘可达，失败时执行完整退出。 */
    private TrayCloseCoordinator trayCloseCoordinator;

    /** 退出只触发一次（托盘退出与窗口关闭可能并发） */
    private final java.util.concurrent.atomic.AtomicBoolean exitInitiated =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** 分离并排序 JavaFX 与后台资源清理；worker 和 stop() 共用幂等状态。 */
    private volatile ApplicationShutdownCoordinator shutdownCoordinator;

    /** SIGINT/SIGTERM must close Spring (and H2) even when JavaFX never calls stop(). */
    private volatile Thread jvmShutdownHook;

    /** 在 JavaFX 场景创建前建立数据、Spring 与工作区基础设施。 */
    @Override
    public void init() throws Exception {
        DataRoot dataRoot = DataRoot.resolve().prepare();
        AnnotationConfigApplicationContext rootContext = ApplicationContexts.createRoot(dataRoot);
        springContext = rootContext;
        Thread hook = new Thread(this::shutdownForJvmTermination, "javaclaw-shutdown-hook");
        try {
            Runtime.getRuntime().addShutdownHook(hook);
            jvmShutdownHook = hook;
            ApplicationContexts.registerDesktopInfrastructure(rootContext);
            fxDispatcher = rootContext.getBean(FxDispatcher.class);
            shutdownCoordinator = new ApplicationShutdownCoordinator(fxDispatcher);
            trayCloseCoordinator = new TrayCloseCoordinator(fxDispatcher);
        } catch (Exception | Error failure) {
            removeJvmShutdownHook();
            rootContext.close();
            springContext = null;
            throw failure;
        }
    }

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
            // 0.1 注册打包字体（须在创建任何 Scene 之前；下方首启向导即会构建 Scene）
            springContext.getBean(
                    com.javaclaw.ui.javafx.theme.FontManager.class).loadBundledFonts();

            // 0.5 注入 UI 交互端口（让 ToolConfirmationManager 等领域层能请求确认/通知，
            //     而不直接依赖 JavaFX）。未来接入 Web 前端时替换成对应的 Port 实现即可。
            log.info("正在装配 UI 交互端口（JavaFX）...");
            JfxUserInteractionPort interactionPort =
                    springContext.getBean(JfxUserInteractionPort.class);
            ToolConfirmationManager.setPort(interactionPort);

            // 1. 创建 Playwright 浏览器管理器（懒加载，首次使用浏览器工具时才启动）
            log.info("正在创建 Playwright 浏览器管理器（懒加载模式）...");
            PlaywrightBrowserManager browserManager =
                    springContext.getBean(PlaywrightBrowserManager.class);

            // 1.5. 首次使用向导（仅未完成时弹出，阻塞直到用户关闭）
            springContext.getBean(OnboardingViewFactory.class).showIfNeeded(primaryStage);

            // 2-3. 应用内核是唯一组合根：整体创建工作区运行时，并装配定时任务、插件与 SDD。
            log.info("正在初始化应用内核与工作区运行时...");
            applicationKernel = new ApplicationKernel(
                    browserManager,
                    () -> applicationKernel.current().sddTaskViews()
                            .create(primaryStage).show(),
                    this::openWorkflowCenter,
                    this::closeWorkflowCenter,
                    springContext.getBean(WorkspaceSpringContextFactory.class),
                    springContext.getBean(com.javaclaw.plugin.PluginManager.class),
                    springContext.getBean(com.javaclaw.config.WorkspaceManager.class),
                    springContext.getBean(com.javaclaw.config.DataManager.class),
                    springContext.getBean(com.javaclaw.diagnostics.TraceRecorder.class),
                    springContext.getBean(com.javaclaw.config.AgentConfig.class),
                    springContext.getBean(com.javaclaw.config.EmailConfig.class),
                    springContext.getBean(com.javaclaw.config.NotificationConfig.class),
                    springContext.getBean(com.javaclaw.system.CommandWhitelistManager.class));
            applicationKernel.initialize();
            ApplicationContexts.registerApplicationKernel(springContext, applicationKernel);

            // 4. 构建聊天界面
            log.info("正在构建聊天界面...");
            SpringFxmlLoader fxml = springContext.getBean(SpringFxmlLoader.class);
            var chatResource = java.util.Objects.requireNonNull(
                    getClass().getResource("/fxml/chat/chat-view.fxml"),
                    "缺少主聊天 FXML: /fxml/chat/chat-view.fxml");
            chatViewHandle = fxml.load(chatResource);
            chatView = chatViewHandle.controller(ChatViewController.class);

            // 5. 创建场景并加载 CSS 样式
            Scene scene = new Scene(chatViewHandle.root(), 1200, 700);

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
            springContext.getBean(
                    com.javaclaw.ui.javafx.theme.ThemeManager.class).init();

            // 5.6 初始化字体管理器：挂全局窗口监听 + 应用工作区记忆的字体（默认系统原生时不注入覆盖）
            springContext.getBean(
                    com.javaclaw.ui.javafx.theme.FontManager.class).init();

            // 6. 配置并显示主窗口
            this.primaryStage = primaryStage;
            primaryStage.setTitle("JavaClaw 智能助手");
            var appIcon = getClass().getResource("/images/javaclaw-app-icon-capabilities.png");
            if (appIcon != null) {
                primaryStage.getIcons().add(new Image(appIcon.toExternalForm()));
            } else {
                log.warn("未找到应用图标 /images/javaclaw-app-icon-capabilities.png");
            }
            primaryStage.setMinWidth(600);   // 最小宽度
            primaryStage.setMinHeight(500);  // 最小高度
            primaryStage.setScene(scene);

            // 窗口创建完成后才接管第二进程的唤起请求；早到请求由协调器缓存。
            SingleInstanceCoordinator.current().ifPresent(coordinator -> {
                coordinator.setShowHandler(() -> fxDispatcher.dispatch(this::showMainWindowFromSingleInstance));
                coordinator.setBuildMismatchHandler(() ->
                        fxDispatcher.dispatch(this::showBuildMismatchNotice));
            });

            // 6.5 安装系统托盘（后台常驻）：安装成功则关闭窗口最小化到托盘，
            //     应用继续在后台运行（定时任务/托管任务不中断），仅托盘"退出"才真正关闭。
            //     平台不支持或安装失败时回退为"关闭即退出"。
            if (Boolean.getBoolean(OnboardingViewFactory.UI_TEST_PROPERTY)) {
                log.info("UI 测试模式：不安装系统托盘");
            } else if (isMac() && !DesktopToolkitBootstrap.isPreparedForJavaFx()) {
                log.warn("macOS AWT 未在 JavaFX 前完成初始化，禁用关闭到托盘以避免窗口失联");
            } else {
                // 安装过程异步执行；关闭事件会等待实际注册结果后再决定隐藏或退出。
                Platform.setImplicitExit(false);
                setupSystemTray();
            }
            primaryStage.setOnCloseRequest(event -> {
                if (trayManager != null
                        && springContext.getBean(AgentConfig.class).isTrayMinimizeOnClose()) {
                    event.consume();
                    minimizeToTrayOrExit();
                } else {
                    requestFullExit();
                }
            });
            primaryStage.show();

            log.info("主窗口已显示，大小: {}x{}", 1200, 700);
            log.info("========== JavaClaw 应用启动完成 ==========");

        } catch (Exception e) {
            log.error("应用启动失败", e);
            if (chatViewHandle != null) {
                try {
                    chatViewHandle.close();
                } catch (Throwable closeFailure) {
                    e.addSuppressed(closeFailure);
                }
                chatViewHandle = null;
            }
            if (applicationKernel != null) {
                try {
                    applicationKernel.close();
                } catch (Throwable closeFailure) {
                    e.addSuppressed(closeFailure);
                }
            }
            throw new RuntimeException("JavaClaw 启动失败: " + e.getMessage(), e);
        }
    }

    private void openWorkflowCenter() {
        if (applicationKernel == null || applicationKernel.isTransitioning()) {
            log.warn("工作区运行时切换中，暂不打开工作流中心");
            return;
        }
        WorkflowView currentView = workflowCenterView;
        if (currentView != null && currentView.isShowing()) {
            currentView.show();
            return;
        }
        final com.javaclaw.runtime.WorkspaceRuntime currentRuntime;
        try {
            currentRuntime = applicationKernel.current();
        } catch (IllegalStateException unavailable) {
            log.warn("当前没有可用运行时，暂不打开工作流中心");
            return;
        }
        WorkflowView created = currentRuntime.workflowViews().create(
                primaryStage, published -> {
                    if (chatView != null) {
                        chatView.onWorkflowPublished(published.id(), published.name());
                    }
                });
        workflowCenterView = created;
        created.show();
    }

    private void closeWorkflowCenter() {
        WorkflowView currentView = workflowCenterView;
        if (currentView == null) return;
        currentView.close();
        workflowCenterView = null;
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
                    () -> {
                        showMainWindow();
                        applicationKernel.current().sddTaskViews()
                                .create(primaryStage).showCreate();
                    },
                    () -> { showMainWindow(); if (chatView != null) chatView.openSettings(); },
                    this::requestFullExit,
                    fxDispatcher);
            trayManager.ensureInstalled().thenAccept(installed -> {
                if (!installed) log.warn("系统托盘首次安装失败，关闭窗口时将重试或完整退出");
            });
        } catch (Throwable t) {
            log.warn("系统托盘初始化异常，将使用关闭即退出模式: {}", t.getMessage());
            trayManager = null;
        }
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT).contains("mac");
    }

    /** 仅记录单实例通知的 FX 窗口调用；调用后状态不代表系统已完成置前。 */
    private void showMainWindowFromSingleInstance() {
        if (primaryStage == null) {
            log.info("单实例主窗口恢复调用未执行: 窗口尚未创建");
            return;
        }
        log.info("单实例主窗口恢复调用前: showing={}, iconified={}, focused={}",
                primaryStage.isShowing(), primaryStage.isIconified(), primaryStage.isFocused());
        showMainWindow();
        log.info("单实例主窗口恢复调用后: showing={}, iconified={}, focused={}",
                primaryStage.isShowing(), primaryStage.isIconified(), primaryStage.isFocused());
    }

    /** 从托盘或单实例通知恢复主窗口：显示、取消最小化并请求置前。 */
    private void showMainWindow() {
        if (primaryStage == null) return;
        if (!primaryStage.isShowing()) primaryStage.show();
        if (primaryStage.isIconified()) primaryStage.setIconified(false);
        primaryStage.toFront();
        // Stage 的窗口排序不会激活后台 macOS 应用。AWT 已在 Launcher 中提前初始化，
        // 只在用户明确恢复窗口时请求当前应用置前，不把其他窗口一并拉到前台。
        if (isMac() && DesktopToolkitBootstrap.isPreparedForJavaFx()) {
            try {
                if (Desktop.isDesktopSupported()) {
                    Desktop desktop = Desktop.getDesktop();
                    if (desktop.isSupported(Desktop.Action.APP_REQUEST_FOREGROUND)) {
                        desktop.requestForeground(false);
                    }
                }
            } catch (RuntimeException failure) {
                log.warn("macOS 应用置前请求失败，保留普通窗口恢复: {}",
                        failure.getMessage(), failure);
            }
        }
        primaryStage.requestFocus();
        SystemTrayManager tray = trayManager;
        if (tray != null && !exitInitiated.get()) {
            tray.ensureInstalled().thenAccept(installed -> {
                if (!installed) log.warn("显示主窗口时未能恢复系统托盘");
            });
        }
    }

    /** 不同构建的第二实例只提示手动重启，绝不自动中断当前运行任务。 */
    private void showBuildMismatchNotice() {
        showMainWindow();
        try {
            springContext.getBean(JfxUserInteractionPort.class).notify(new ToastRequest(
                    "需要重启",
                    "应用文件已更新，当前窗口仍在运行旧代码。请完整退出 JavaClaw 后重新启动。"));
        } catch (RuntimeException failure) {
            log.warn("显示构建更新提示失败: {}", failure.getMessage(), failure);
        }
    }

    /** 先异步确认托盘可达；失败或超时按照关闭意图完整退出。 */
    private void minimizeToTrayOrExit() {
        SystemTrayManager tray = trayManager;
        if (tray == null) {
            requestFullExit();
            return;
        }
        trayCloseCoordinator.request(
                tray::ensureInstalled,
                () -> !exitInitiated.get() && tray == trayManager && tray.isInstalled(),
                this::hideToTray,
                this::requestFullExit,
                failure -> log.warn("关闭窗口前无法确认系统托盘（{}），将完整退出应用",
                        failure.getMessage()));
    }

    /** 隐藏主窗口到托盘后台常驻，首次提示一次气泡。 */
    private void hideToTray() {
        SystemTrayManager tray = trayManager;
        if (tray == null || !tray.isInstalled()) {
            log.warn("系统托盘在隐藏窗口前失效，将完整退出应用");
            requestFullExit();
            return;
        }
        if (primaryStage != null) primaryStage.hide();
        if (!trayHintShown) {
            tray.displayInfo("JavaClaw 仍在后台运行",
                    "已最小化到系统托盘，可从托盘菜单恢复窗口或退出应用");
            trayHintShown = true;
        }
        log.info("主窗口已最小化到系统托盘，应用继续后台运行");
    }

    /**
     * 真正退出应用。
     *
     * <p>后台线程先完成清理；已安装 AWT 托盘时才在清理完成后
     * {@link Runtime#halt(int)}，原因有二：</p>
     * <ul>
     *   <li>规避 macOS 上 AWT 托盘与 JavaFX 同时关闭时争用原生主线程导致的死锁
     *       （表现为点退出后卡住）；</li>
     *   <li>{@code halt} 跳过 JVM 关闭钩子（如 Playwright 驱动进程的清理钩子可能阻塞数秒），
     *       而 {@code System.exit} 会同步等待这些钩子。应用自己的资源已在此前关闭。</li>
     * </ul>
     */
    private void requestFullExit() {
        if (!exitInitiated.compareAndSet(false, true)) return;
        log.info("收到退出请求，开始关闭应用...");

        SystemTrayManager tray = trayManager;
        boolean awtActive = tray != null && tray.wasEverInstalled();
        trayManager = null;
        if (tray != null) {
            try {
                // remove() 自身排入 AWT EDT，并先禁止可用性监听器触发重装。
                tray.remove();
            } catch (Throwable trayFailure) {
                log.debug("提交托盘移除任务失败，后台清理仍会继续", trayFailure);
            }
        }

        // 视图句柄会销毁 ContextMenu 等 JavaFX 控件，必须在启动后台 worker 之前
        // 于 FX Application Thread 完成。无法调度 UI 时仍要关闭 Spring 与数据库。
        boolean uiClosed = shutdownUiResources(2000);
        if (!uiClosed) log.warn("JavaFX 视图未及时清理，继续关闭后台资源和数据库");

        Thread worker = new Thread(() -> {
            try {
                shutdownBackendResources(!uiClosed);
            } catch (Throwable failure) {
                log.error("关闭后台资源失败，进程保持运行以便诊断", failure);
                return;
            }
            log.info("资源清理完成，退出进程");
            if (awtActive) {
                // macOS 上退出 JavaFX 与 AWT 会争用原生主线程；此时数据库已关闭。
                Runtime.getRuntime().halt(0);
            } else {
                Platform.exit();
            }
        }, "exit-worker");
        // The window may close before this thread finishes; keep the JVM alive until H2 closes.
        worker.setDaemon(false);
        worker.start();
    }

    /**
     * JavaFX 生命周期关闭回调。与退出 worker 和 JVM 关闭钩子共用幂等清理。
     */
    @Override
    public void stop() {
        exitInitiated.set(true);
        SystemTrayManager tray = trayManager;
        trayManager = null;
        if (tray != null) tray.remove();
        boolean uiClosed = shutdownUiResources(2000);
        shutdownBackendResources(!uiClosed);
    }

    /**
     * 在 FX Application Thread 释放所有视图（幂等）。非 FX 调用会有界等待调度完成，
     * 但超时不会阻止后续数据库关闭。
     */
    private boolean shutdownUiResources(long timeoutMillis) {
        ApplicationShutdownCoordinator coordinator = shutdownCoordinator;
        if (coordinator == null) return false;
        try {
            coordinator.closeUi(this::releaseUiResources).get(timeoutMillis, TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            log.warn("等待 JavaFX 视图清理时被中断");
        } catch (ExecutionException | TimeoutException failure) {
            log.warn("JavaFX 视图清理失败: {}", failure.getMessage(), failure);
        }
        return false;
    }

    private void releaseUiResources() {
        log.info("正在关闭 JavaFX 视图...");
        WorkflowView workflow = workflowCenterView;
        workflowCenterView = null;
        if (workflow != null) safeShutdown("工作流中心", workflow::close);

        ViewHandle<BorderPane> mainView = chatViewHandle;
        chatViewHandle = null;
        chatView = null;
        if (mainView != null) safeShutdown("主界面", mainView::close);
    }

    /** SIGINT/SIGTERM does not reliably call JavaFX stop(); prioritize releasing the database. */
    private void shutdownForJvmTermination() {
        exitInitiated.set(true);
        log.info("JVM 收到终止信号，开始关闭应用资源...");
        // JavaFX may already be stopped. Give queued UI cleanup a short chance, then continue.
        shutdownUiResources(250);
        try {
            shutdownBackendResources(true);
        } catch (Throwable failure) {
            log.error("JVM 终止期间关闭后台资源失败", failure);
        }
    }

    /** 释放后台基础设施（幂等）；进程退出时可越过失效的 JavaFX 清理。 */
    private void shutdownBackendResources(boolean allowUnavailableUi) {
        ApplicationShutdownCoordinator coordinator = shutdownCoordinator;
        if (coordinator == null) {
            closeBackendResources();
        } else if (allowUnavailableUi) {
            coordinator.closeBackendForProcessExit(this::closeBackendResources);
        } else {
            coordinator.closeBackend(this::closeBackendResources);
        }
    }

    private void closeBackendResources() {
        log.info("JavaClaw 应用正在关闭后台资源...");

        // 工作区 Context 按依赖反序关闭；持久化器会在任务作用域之前 flush。
        ApplicationKernel kernel = applicationKernel;
        applicationKernel = null;
        if (kernel != null) safeShutdown("应用内核", kernel::close);

        AnnotationConfigApplicationContext rootContext = springContext;
        try {
            if (rootContext != null) {
                long start = System.currentTimeMillis();
                rootContext.close();
                springContext = null;
                long cost = System.currentTimeMillis() - start;
                if (cost > 200) log.info("关闭 Spring 根 Context 耗时 {}ms", cost);
            }
        } finally {
            safeShutdown("单实例协调器", SingleInstanceCoordinator::closeCurrent);
        }

        // A failed root close must prevent the caller from using Runtime.halt().
        removeJvmShutdownHook();

        log.info("JavaClaw 应用已关闭");
    }

    private void removeJvmShutdownHook() {
        Thread hook = jvmShutdownHook;
        jvmShutdownHook = null;
        if (hook == null) return;
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException shuttingDown) {
            // A shutdown hook cannot be removed once JVM shutdown has begun.
        }
    }

    /** 执行单个清理步骤：吞异常 + 计时，单步 >200ms 记日志（定位退出慢的步骤）。 */
    private void safeShutdown(String name, Runnable action) {
        long t0 = System.currentTimeMillis();
        try {
            action.run();
        } catch (Throwable t) {
            log.warn("关闭 {} 时出错（忽略，继续退出）", name, t);
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
