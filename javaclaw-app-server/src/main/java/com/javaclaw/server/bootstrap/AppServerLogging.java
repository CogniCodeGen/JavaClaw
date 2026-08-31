package com.javaclaw.server.bootstrap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 在 SLF4J 首次初始化前确定 App Server 日志目录，避免协议 stdout 被日志污染。 */
final class AppServerLogging {
    private static final Pattern ASSIGNMENT = Pattern.compile(
            "(?i)(authorization|api[-_ ]?key|access[-_ ]?token|refresh[-_ ]?token|password|secret)\\s*[:=]\\s*[^\\s,;]+");

    private AppServerLogging() {}

    static Logger initialize(String[] args) throws IOException {
        ServerDirectories directories = ServerDirectories.resolve(args);
        Path directory = logDirectory(args, directories);
        try {
            Files.createDirectories(directory);
        } catch (IOException failure) {
            throw new IOException("程序目录不可写，无法创建 App Server 日志目录：" + directory, failure);
        }
        restrictPosixDirectory(directory);
        System.setProperty(
                "javaclaw.program.dir", directories.programDirectory().toString());
        System.setProperty("javaclaw.log.dir", directory.toString());
        System.setProperty("javaclaw.log.process", "app-server");
        Logger logger = LoggerFactory.getLogger("com.javaclaw.appserver");
        logger.info("App Server 启动；日志目录已就绪");
        return logger;
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

    private static Path logDirectory(String[] args, ServerDirectories directories) {
        String explicit = System.getenv("JAVACLAW_LOG_DIR");
        if (explicit != null && !explicit.isBlank()) {
            return Path.of(explicit).toAbsolutePath().normalize();
        }
        for (int index = 0; index < args.length; index++) {
            if ("--config-dir".equals(args[index]) && index + 1 < args.length) {
                return Path.of(args[index + 1]).toAbsolutePath().normalize().resolve("logs");
            }
        }
        return directories.configurationRoot().resolve("logs");
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
            // Windows 由配置根的当前 SID ACL 约束；此处不引入主 App Server 的 FFM 调用。
        }
    }

    private static String redact(String value) {
        return ASSIGNMENT
                .matcher(value)
                .replaceAll("$1=[REDACTED]")
                .replaceAll("(?i)(bearer\\s+)[A-Za-z0-9._~+/-]+", "$1[REDACTED]");
    }
}
