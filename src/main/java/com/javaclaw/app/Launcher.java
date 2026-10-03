package com.javaclaw.app;

import com.javaclaw.platform.data.ApplicationHome;
import com.javaclaw.platform.data.DataRoot;
import com.javaclaw.platform.data.ApplicationUpgradeGuard;
import com.javaclaw.platform.build.ApplicationBuildIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 应用启动器
 *
 * <p>在非模块化的 JavaFX 项目中，如果直接运行继承自 {@link javafx.application.Application}
 * 的类，JavaFX 运行时会检测到缺少 module-info.java 而报错。</p>
 *
 * <p>解决方案：通过一个普通的 Java 类（不继承 Application）作为入口点，
 * 间接调用 {@link JavaClawApp#main(String[])} 来启动 JavaFX 应用。</p>
 *
 * <p>此类是 Maven javafx-maven-plugin 配置中的 mainClass。</p>
 *
 * @author JavaClaw
 * @see JavaClawApp
 */
public class Launcher {

    /**
     * 程序入口
     *
     * @param args 命令行参数，将透传给 JavaFX Application
     */
    public static void main(String[] args) {
        // Resolve from the launcher's code location before SLF4J initializes file appenders.
        // Otherwise the initial logs/ directory would be created in the caller's working directory.
        ApplicationHome home = ApplicationHome.resolve();
        // Both locks are acquired before deleting any version-3 data. The data lock is
        // transferred directly to the coordinator, so an old launcher never sees a gap.
        ApplicationUpgradeGuard upgrade;
        try {
            upgrade = ApplicationUpgradeGuard.acquire(home);
        } catch (ApplicationUpgradeGuard.AlreadyRunningException running) {
            System.setProperty("workspace.log.dir", home.logsDirectory().toString());
            ApplicationBuildIdentity build = ApplicationBuildIdentity.launch(Launcher.class);
            SingleInstanceCoordinator.notifyRunning(home.dataDirectory(), build.fingerprint());
            return;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("无法获取 JavaClaw 升级锁", e);
        }
        if (upgrade == null) return;
        SingleInstanceCoordinator coordinator;
        try (upgrade) {
            home.prepare(upgrade);
            System.setProperty("workspace.log.dir", home.logsDirectory().toString());
            System.setProperty("java.io.tmpdir", home.temporaryDirectory().toString());
            System.setProperty("playwright.driver.tmpdir", home.temporaryDirectory().toString());
            ApplicationBuildIdentity buildIdentity = ApplicationBuildIdentity.launch(Launcher.class);
            coordinator = SingleInstanceCoordinator.adopt(
                    new DataRoot(home.dataDirectory()).path(), buildIdentity.fingerprint(),
                    upgrade.takeDataLock());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("无法初始化 JavaClaw 4 数据目录或单实例协调器", e);
        }
        try (coordinator) {
            Logger log = LoggerFactory.getLogger(Launcher.class);
            log.info("JavaClaw 启动器开始运行");
            log.info("JDK 版本: {}", System.getProperty("java.version"));
            log.info("JavaFX 版本: {}", System.getProperty("javafx.version"));
            log.info("操作系统: {} {}", System.getProperty("os.name"), System.getProperty("os.arch"));
            // macOS must join AppKit before JavaFX to publish a native tray icon.
            DesktopToolkitBootstrap.prepareForJavaFxLaunch();
            JavaClawApp.main(args);
        }
    }
}
