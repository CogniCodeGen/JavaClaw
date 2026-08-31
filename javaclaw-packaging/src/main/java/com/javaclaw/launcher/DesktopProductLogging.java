package com.javaclaw.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 在 JavaFX 启动前建立 Desktop 的独立滚动日志，不依赖发行脚本或 IDE VM 参数。 */
final class DesktopProductLogging {
    private static final Pattern ASSIGNMENT = Pattern.compile(
            "(?i)(authorization|api[-_ ]?key|access[-_ ]?token|refresh[-_ ]?token|password|secret)\\s*[:=]\\s*[^\\s,;]+");

    private DesktopProductLogging() {}

    static Logger initialize(java.util.Map<String, String> launchDefaults) throws IOException {
        Path directory = logDirectory(System.getenv(), launchDefaults);
        try {
            Files.createDirectories(directory);
        } catch (IOException failure) {
            throw new IOException("程序目录不可写，无法创建 JavaClaw 日志目录：" + directory, failure);
        }
        restrictPosixDirectory(directory);
        System.setProperty("javaclaw.log.dir", directory.toString());
        System.setProperty("javaclaw.log.process", "desktop");
        Logger logger = LoggerFactory.getLogger("com.javaclaw.desktop.product");
        logger.info("Desktop 产品入口启动；日志目录已就绪");
        return logger;
    }

    static Path logDirectory(java.util.Map<String, String> environment, java.util.Map<String, String> launchDefaults) {
        String explicit = environment.get("JAVACLAW_LOG_DIR");
        if (explicit != null && !explicit.isBlank()) {
            return Path.of(explicit).toAbsolutePath().normalize();
        }
        String configuration = environment.get("JAVACLAW_CONFIG_DIR");
        if (configuration == null || configuration.isBlank()) {
            configuration = launchDefaults.get("JAVACLAW_CONFIG_DIR");
        }
        Path root;
        if (configuration != null && !configuration.isBlank()) {
            root = Path.of(configuration);
        } else {
            String program = environment.get("JAVACLAW_PROGRAM_DIR");
            if (program == null || program.isBlank()) {
                program = launchDefaults.get("JAVACLAW_PROGRAM_DIR");
            }
            root = Path.of(program == null || program.isBlank() ? System.getProperty("user.dir", ".") : program)
                    .resolve(".javaclaw/config-v4");
        }
        return root.toAbsolutePath().normalize().resolve("logs");
    }

    static String summarize(Throwable failure) {
        StringBuilder result = new StringBuilder();
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 8; depth++) {
            if (!result.isEmpty()) {
                result.append(" <- ");
            }
            result.append(current.getClass().getSimpleName());
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                result.append(": ").append(redact(current.getMessage()));
            }
            current = current.getCause();
        }
        return result.toString();
    }

    private static void restrictPosixDirectory(Path directory) throws IOException {
        try {
            Files.setPosixFilePermissions(
                    directory,
                    Set.of(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE));
        } catch (UnsupportedOperationException ignored) {
            // Windows 继承配置根的当前 SID ACL；Desktop 不允许直接调用 FFM 修改 ACL。
        }
    }

    private static String redact(String value) {
        return ASSIGNMENT
                .matcher(value)
                .replaceAll("$1=[REDACTED]")
                .replaceAll("(?i)(bearer\\s+)[A-Za-z0-9._~+/-]+", "$1[REDACTED]");
    }
}
