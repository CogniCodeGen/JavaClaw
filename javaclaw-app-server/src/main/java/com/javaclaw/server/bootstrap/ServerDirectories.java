package com.javaclaw.server.bootstrap;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * App Server 的程序本地目录快照。
 *
 * <p>未显式配置时，持久数据、配置和缓存都位于 {@code <program>/.javaclaw} 下。
 *
 * <p>解析阶段不创建目录；安装目录不可写时也不会回退到用户主目录，从而保证一次启动始终使用可解释的同一组路径。
 *
 * @param programDirectory 程序目录，绝对规范路径且不可为 null
 * @param dataRoot H2、附件和插件等权威数据目录，绝对规范路径且不可为 null
 * @param configurationRoot 凭据主密钥、日志和项目指令配置目录，绝对规范路径且不可为 null
 * @param cacheRoot 工作树、Browser 和 Worker 临时投影目录，绝对规范路径且不可为 null
 */
record ServerDirectories(Path programDirectory, Path dataRoot, Path configurationRoot, Path cacheRoot) {
    private static final String LOCAL_ROOT = ".javaclaw";

    ServerDirectories {
        programDirectory = normalize(programDirectory, "programDirectory");
        dataRoot = normalize(dataRoot, "dataRoot");
        configurationRoot = normalize(configurationRoot, "configurationRoot");
        cacheRoot = normalize(cacheRoot, "cacheRoot");
    }

    /** 按启动参数、环境变量、系统属性和当前程序工作目录的优先级解析目录；不访问用户主目录。 */
    static ServerDirectories resolve(String[] args) {
        return resolve(args, System.getenv(), System.getProperties(), Path.of(System.getProperty("user.dir", ".")));
    }

    /**
     * 可测试的纯路径解析入口。单项目录覆盖只改变对应目录，其余默认值仍从 programDirectory 派生。
     *
     * @param args App Server 启动参数，可为 null
     * @param environment 非空环境变量快照
     * @param properties 非空系统属性快照
     * @param workingDirectory 未配置程序目录时使用的非空工作目录
     * @return 不创建文件的不可变目录快照
     */
    static ServerDirectories resolve(
            String[] args, Map<String, String> environment, Properties properties, Path workingDirectory) {
        String[] safeArgs = args == null ? new String[0] : args.clone();
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(properties, "properties");
        Path program = configuredPath(safeArgs, "--program-dir");
        if (program == null) {
            program = configuredPath(environment.get("JAVACLAW_PROGRAM_DIR"));
        }
        if (program == null) {
            program = configuredPath(properties.getProperty("javaclaw.program.dir"));
        }
        if (program == null) {
            program = Objects.requireNonNull(workingDirectory, "workingDirectory");
        }
        program = program.toAbsolutePath().normalize();
        Path local = program.resolve(LOCAL_ROOT);
        return new ServerDirectories(
                program,
                resolveRoot(safeArgs, "--data-dir", environment, "JAVACLAW_DATA_DIR", local.resolve("data-v4")),
                resolveRoot(safeArgs, "--config-dir", environment, "JAVACLAW_CONFIG_DIR", local.resolve("config-v4")),
                resolveRoot(safeArgs, "--cache-dir", environment, "JAVACLAW_CACHE_DIR", local.resolve("cache-v4")));
    }

    private static Path resolveRoot(
            String[] args, String option, Map<String, String> environment, String variable, Path fallback) {
        Path argument = configuredPath(args, option);
        if (argument != null) {
            return argument;
        }
        Path configured = configuredPath(environment.get(variable));
        return configured == null ? fallback : configured;
    }

    private static Path configuredPath(String[] args, String option) {
        for (int index = 0; index < args.length; index++) {
            if (!option.equals(args[index])) {
                continue;
            }
            if (index + 1 == args.length || args[index + 1].isBlank()) {
                throw new IllegalArgumentException(option + " requires a path");
            }
            return Path.of(args[index + 1]).toAbsolutePath().normalize();
        }
        return null;
    }

    private static Path configuredPath(String value) {
        return value == null || value.isBlank()
                ? null
                : Path.of(value).toAbsolutePath().normalize();
    }

    private static Path normalize(Path value, String name) {
        return Objects.requireNonNull(value, name).toAbsolutePath().normalize();
    }
}
