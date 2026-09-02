package com.javaclaw.nativehost.startup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.extension.spi.LoginStartupPort;

/** 在 macOS LaunchAgents、Linux systemd user 或 Windows Task Scheduler 中管理登录启动项。 */
public final class UserLoginStartup implements LoginStartupPort {
    /** App Server 专用启动器的 JVM 属性。 */
    public static final String LAUNCHER_PROPERTY = "javaclaw.server.launcher";

    private final PlatformRegistration registration;

    private UserLoginStartup(PlatformRegistration registration) {
        this.registration = Objects.requireNonNull(registration, "registration");
    }

    /**
     * 按当前操作系统与用户目录创建端口。
     *
     * <p>启动器只在首次注册时校验，因此没有 Schedule 的开发运行不要求配置该属性。
     *
     * @return 当前平台登录启动端口
     */
    public static UserLoginStartup fromSystemProperties() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        String configured = System.getProperty(LAUNCHER_PROPERTY, "").strip();
        Path launcher = configured.isEmpty()
                ? null
                : Path.of(configured).toAbsolutePath().normalize();
        return new UserLoginStartup(platform(os, home, launcher, new ProcessCommandRunner()));
    }

    @Override
    public synchronized void setRequired(boolean required) {
        if (required) {
            registration.install();
        } else {
            registration.remove();
        }
    }

    @Override
    public synchronized Status status(boolean required) {
        return registration.status(required);
    }

    static UserLoginStartup createForTest(String os, Path home, Path launcher, CommandRunner commands) {
        return new UserLoginStartup(platform(os.toLowerCase(Locale.ROOT), home, launcher, commands));
    }

    private static PlatformRegistration platform(String os, Path home, Path launcher, CommandRunner commands) {
        if (os.contains("mac")) {
            return new MacRegistration(home, launcher);
        }
        if (os.contains("win")) {
            return new WindowsRegistration(launcher, commands);
        }
        if (os.contains("linux")) {
            return new LinuxRegistration(home, launcher, commands);
        }
        return new UnsupportedRegistration(os);
    }

    private static Path requireLauncher(Path launcher) {
        if (launcher == null) {
            throw new IllegalStateException("启用 Schedule 前必须配置 -D" + LAUNCHER_PROPERTY + "=<绝对启动器路径>");
        }
        Path normalized = launcher.toAbsolutePath().normalize();
        if (!launcher.isAbsolute() || !Files.isRegularFile(normalized)) {
            throw new IllegalStateException("App Server 登录启动器不存在或不是绝对文件");
        }
        return normalized;
    }

    private static void atomicWrite(Path target, String content) {
        try {
            Files.createDirectories(target.getParent());
            Path temporary = Files.createTempFile(
                    target.getParent(), target.getFileName().toString(), ".tmp");
            try {
                Files.writeString(temporary, content, StandardCharsets.UTF_8);
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("写入用户登录启动项失败", failure);
        }
    }

    private static void delete(Path target) {
        try {
            Files.deleteIfExists(target);
        } catch (IOException failure) {
            throw new IllegalStateException("删除用户登录启动项失败", failure);
        }
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private static String systemd(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    interface CommandRunner {
        int run(List<String> command);
    }

    private interface PlatformRegistration {
        void install();

        void remove();

        Status status(boolean required);
    }

    private record MacRegistration(Path home, Path launcher) implements PlatformRegistration {
        @Override
        public void install() {
            Path executable = requireLauncher(launcher);
            atomicWrite(file(), """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                    <plist version="1.0"><dict>
                      <key>Label</key><string>com.javaclaw.app-server</string>
                      <key>ProgramArguments</key><array><string>%s</string></array>
                      <key>RunAtLoad</key><true/>
                      <key>ProcessType</key><string>Background</string>
                    </dict></plist>
                    """.formatted(xml(executable.toString())));
        }

        @Override
        public void remove() {
            delete(file());
        }

        @Override
        public Status status(boolean required) {
            return launcherStatus(launcher, Files.isRegularFile(file()));
        }

        private Path file() {
            return home.resolve("Library/LaunchAgents/com.javaclaw.app-server.plist");
        }
    }

    private record LinuxRegistration(Path home, Path launcher, CommandRunner commands) implements PlatformRegistration {
        @Override
        public void install() {
            Path executable = requireLauncher(launcher);
            atomicWrite(file(), """
                    [Unit]
                    Description=JavaClaw App Server

                    [Service]
                    Type=simple
                    ExecStart="%s"
                    Restart=on-failure

                    [Install]
                    WantedBy=default.target
                    """.formatted(systemd(executable.toString())));
            requireSuccess(commands, List.of("systemctl", "--user", "daemon-reload"));
            requireSuccess(commands, List.of("systemctl", "--user", "enable", "javaclaw-app-server.service"));
        }

        @Override
        public void remove() {
            if (!Files.exists(file())) {
                return;
            }
            commands.run(List.of("systemctl", "--user", "disable", "javaclaw-app-server.service"));
            delete(file());
            commands.run(List.of("systemctl", "--user", "daemon-reload"));
        }

        @Override
        public Status status(boolean required) {
            return launcherStatus(launcher, Files.isRegularFile(file()));
        }

        private Path file() {
            return home.resolve(".config/systemd/user/javaclaw-app-server.service");
        }
    }

    private record WindowsRegistration(Path launcher, CommandRunner commands) implements PlatformRegistration {
        @Override
        public void install() {
            Path executable = requireLauncher(launcher);
            requireSuccess(
                    commands,
                    List.of(
                            "schtasks.exe",
                            "/Create",
                            "/TN",
                            "JavaClaw App Server",
                            "/SC",
                            "ONLOGON",
                            "/TR",
                            "\"" + executable + "\"",
                            "/F"));
        }

        @Override
        public void remove() {
            commands.run(List.of("schtasks.exe", "/Delete", "/TN", "JavaClaw App Server", "/F"));
        }

        @Override
        public Status status(boolean required) {
            boolean installed = commands.run(List.of("schtasks.exe", "/Query", "/TN", "JavaClaw App Server")) == 0;
            return launcherStatus(launcher, installed);
        }
    }

    private record UnsupportedRegistration(String os) implements PlatformRegistration {
        @Override
        public void install() {
            throw new IllegalStateException("当前平台不支持登录启动项: " + os);
        }

        @Override
        public void remove() {}

        @Override
        public Status status(boolean required) {
            return new Status(false, false, Optional.of("当前操作系统不支持 JavaClaw 登录启动项"));
        }
    }

    static final class ProcessCommandRunner implements CommandRunner {
        @Override
        public int run(List<String> command) {
            try {
                Process process = new ProcessBuilder(command)
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start();
                return process.waitFor();
            } catch (IOException failure) {
                throw new IllegalStateException("无法执行登录启动项命令", failure);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("登录启动项命令被中断", failure);
            }
        }
    }

    private static void requireSuccess(CommandRunner commands, List<String> command) {
        if (commands.run(command) != 0) {
            throw new IllegalStateException("登录启动项命令失败: " + command.getFirst());
        }
    }

    private static Status launcherStatus(Path launcher, boolean installed) {
        if (launcher == null
                || !launcher.isAbsolute()
                || !Files.isRegularFile(launcher.toAbsolutePath().normalize())) {
            return new Status(false, installed, Optional.of("IDEA 调试未配置 App Server launcher"));
        }
        return new Status(true, installed, Optional.empty());
    }
}
