package com.javaclaw.launcher;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import com.javaclaw.launcher.tray.AwtSystemTrayView;
import com.javaclaw.launcher.tray.ProtocolTrayServerControl;
import com.javaclaw.launcher.tray.TrayApplicationController;
import com.javaclaw.launcher.tray.TrayCommand;
import com.javaclaw.launcher.tray.TrayServerProcess;
import com.javaclaw.nativehost.ManagedRuntimeDirectory;
import com.javaclaw.nativehost.transport.WindowsPipeName;
import com.javaclaw.nativehost.tray.SystemTrayFeature;
import com.javaclaw.nativehost.tray.TrayPresenceLease;

/** JavaClaw 发行包的薄启动器；不持有领域状态，也不访问数据库。 */
public final class JavaClawLauncher {
    private static final String SUPERVISED_PROPERTY = "javaclaw.launcher.supervised";
    private static final String TRAY_ACTIVE_PROPERTY = "javaclaw.launcher.tray-active";

    private JavaClawLauncher() {}

    /**
     * 确保本机 App Server 可用后启动 SDK-only JavaFX Desktop。
     *
     * @param arguments 透传给 JavaFX Application 的参数
     * @throws Exception 本地服务或 Desktop 启动失败
     */
    public static void main(String[] arguments) throws Exception {
        RuntimeLayout layout = RuntimeLayout.fromSystemProperties();
        ManagedRuntimeDirectory.prepare(layout.dataDirectory());
        SystemTrayFeature.Status tray = SystemTrayFeature.detect();
        if (!tray.available()) {
            launchDesktopOnce(layout, arguments);
            return;
        }
        launchWithTray(layout, arguments, tray);
    }

    private static void launchDesktopOnce(RuntimeLayout layout, String[] arguments) throws Exception {
        String transportProperty;
        if (RuntimeLayout.isWindows()) {
            WindowsPipeName pipe = new WindowsServerSupervisor(layout).ensureRunning();
            transportProperty = "-Djavaclaw.server.pipe=" + pipe.value();
        } else {
            Path socket = new UnixServerSupervisor(layout).ensureRunning();
            transportProperty = "-Djavaclaw.server.socket=" + socket;
        }
        Process desktop = new ProcessBuilder(desktopCommand(layout, transportProperty, arguments, false))
                .inheritIO()
                .start();
        int exitCode = desktop.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("JavaClaw Desktop exited with " + exitCode);
        }
    }

    private static void launchWithTray(RuntimeLayout layout, String[] arguments, SystemTrayFeature.Status trayFeature)
            throws Exception {
        try (TrayPresenceLease presence = TrayPresenceLease.acquireCurrentUser()) {
            LaunchTarget target = launchTarget(layout);
            AwtSystemTrayView view = new AwtSystemTrayView(trayFeature);
            DesktopProcessSupervisor desktop =
                    new DesktopProcessSupervisor(layout, target.transportProperty(), arguments);
            try (TrayApplicationController controller =
                    new TrayApplicationController(new ProtocolTrayServerControl(target.process()), desktop, view)) {
                awaitTrayShutdown(controller, presence);
            } catch (Exception failure) {
                view.close();
                throw failure;
            }
        }
    }

    private static LaunchTarget launchTarget(RuntimeLayout layout) throws Exception {
        if (RuntimeLayout.isWindows()) {
            WindowsServerSupervisor supervisor = new WindowsServerSupervisor(layout);
            WindowsPipeName pipe = supervisor.ensureRunning();
            return new LaunchTarget(supervisor, "-Djavaclaw.server.pipe=" + pipe.value());
        }
        UnixServerSupervisor supervisor = new UnixServerSupervisor(layout);
        Path socket = supervisor.ensureRunning();
        return new LaunchTarget(supervisor, "-Djavaclaw.server.socket=" + socket);
    }

    private static void awaitTrayShutdown(TrayApplicationController controller, TrayPresenceLease presence)
            throws InterruptedException {
        CountDownLatch shutdown = new CountDownLatch(1);
        Thread hook = Thread.ofPlatform().name("javaclaw-tray-shutdown").unstarted(() -> {
            closeQuietly(controller);
            closeQuietly(presence);
            shutdown.countDown();
        });
        Runtime.getRuntime().addShutdownHook(hook);
        try {
            controller.start();
            controller.submit(TrayCommand.OPEN_MAIN);
            shutdown.await();
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException ignored) {
                // JVM 已开始执行 shutdown hook。
            }
        }
    }

    private static void closeQuietly(AutoCloseable resource) {
        try {
            resource.close();
        } catch (Exception ignored) {
            // JVM 退出阶段只能尽力删除图标和心跳；App Server 生命周期不由此处改变。
        }
    }

    static List<String> desktopCommand(
            RuntimeLayout layout, String transportProperty, String[] arguments, boolean trayActive) {
        ArrayList<String> command = new ArrayList<>();
        command.add(layout.javaExecutable().toString());
        if (RuntimeLayout.isWindows()) {
            command.add("--enable-native-access=ALL-UNNAMED");
        }
        command.addAll(layout.runtimeProperties());
        command.add(transportProperty);
        command.add("-D" + SUPERVISED_PROPERTY + "=true");
        command.add("-D" + TRAY_ACTIVE_PROPERTY + "=" + trayActive);
        command.add("-cp");
        command.add(layout.classpath());
        command.add("com.javaclaw.desktop.shell.JavaClawDesktopMain");
        command.addAll(List.of(arguments));
        return List.copyOf(command);
    }

    private record LaunchTarget(TrayServerProcess process, String transportProperty) {}
}
