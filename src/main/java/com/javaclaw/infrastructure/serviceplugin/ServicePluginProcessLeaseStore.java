package com.javaclaw.infrastructure.serviceplugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.util.AtomicFileWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/** Persists child-process identity and removes processes that outlive their Desktop lease. */
final class ServicePluginProcessLeaseStore {
    private static final Logger log = LoggerFactory.getLogger(ServicePluginProcessLeaseStore.class);
    private static final String LEGACY_RUNNER =
            "plugin-runner/javaclaw-service-plugin-runner.jar";

    private final Path runDirectory;
    private final Path legacyRunner;
    private final ObjectMapper json;
    private final String runnerMainClass;

    ServicePluginProcessLeaseStore(Path dataRoot, ObjectMapper json, String runnerMainClass) {
        Path normalized = dataRoot.toAbsolutePath().normalize();
        runDirectory = normalized.resolve("run/service-plugins");
        legacyRunner = normalized.resolve(LEGACY_RUNNER);
        this.json = java.util.Objects.requireNonNull(json, "json");
        this.runnerMainClass = java.util.Objects.requireNonNull(runnerMainClass, "runnerMainClass");
    }

    void init() throws IOException {
        Files.createDirectories(runDirectory);
        cleanupStaleProcesses();
        cleanupLegacyRunner();
    }

    void write(ServicePluginSession session, ServicePluginDefinition definition,
               Instant startedAt, long desktopGeneration) {
        try {
            PidRecord record = new PidRecord(session.pid(), startedAt.toEpochMilli(),
                    definition.id(), definition.version(), definition.artifactSha256(),
                    desktopGeneration, null, runnerMainClass, definition.pluginJar().toString());
            AtomicFileWriter.writeJson(json, pidFile(definition.id()).toFile(), record);
        } catch (IOException failure) {
            log.warn("写入服务插件 PID 记录失败: {}", safeMessage(failure));
        }
    }

    void delete(String pluginId) {
        try {
            Files.deleteIfExists(pidFile(pluginId));
        } catch (IOException failure) {
            log.debug("删除服务插件 PID 记录失败", failure);
        }
    }

    private void cleanupStaleProcesses() throws IOException {
        try (var files = Files.list(runDirectory)) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(value -> value.getFileName().toString().endsWith(".json")).toList()) {
                try {
                    PidRecord record = json.readValue(file.toFile(), PidRecord.class);
                    cleanup(record);
                } catch (Exception invalid) {
                    log.warn("忽略无效服务插件 PID 记录 {}: {}",
                            file.getFileName(), safeMessage(invalid));
                } finally {
                    Files.deleteIfExists(file);
                }
            }
        }
    }

    private void cleanup(PidRecord record) {
        ProcessHandle.of(record.pid()).ifPresent(handle -> {
            Instant actual = handle.info().startInstant().orElse(Instant.EPOCH);
            Instant expected = Instant.ofEpochMilli(record.startedAtEpochMilli());
            boolean exactCommand = handle.info().arguments()
                    .map(arguments -> matches(record, Arrays.asList(arguments))).orElse(false);
            if (handle.isAlive() && exactCommand
                    && Math.abs(actual.toEpochMilli() - expected.toEpochMilli()) < 2_000) {
                handle.descendants().forEach(ServicePluginResourceCloser::destroy);
                handle.destroyForcibly();
                log.warn("已清理失去 Desktop 租约的服务插件进程: id={}, pid={}",
                        record.pluginId(), record.pid());
            }
        });
    }

    static boolean matches(PidRecord record, List<String> arguments) {
        if (record.pluginPath() == null || record.pluginPath().isBlank()
                || !arguments.contains(record.pluginPath())) return false;
        if (record.runnerMainClass() != null && !record.runnerMainClass().isBlank()) {
            return arguments.contains(record.runnerMainClass());
        }
        return record.runnerPath() != null && !record.runnerPath().isBlank()
                && arguments.contains(record.runnerPath());
    }

    private void cleanupLegacyRunner() {
        Path parent = legacyRunner.getParent();
        if (Files.isSymbolicLink(parent)) {
            log.warn("跳过符号链接中的旧服务插件 Runner 缓存: {}", parent);
            return;
        }
        try {
            if (Files.deleteIfExists(legacyRunner)) {
                log.info("已清理旧版服务插件 Runner 缓存: {}", legacyRunner);
            }
            if (Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) Files.deleteIfExists(parent);
        } catch (java.nio.file.DirectoryNotEmptyException ignored) {
            // Preserve any unrelated cache entries in the legacy directory.
        } catch (IOException failure) {
            log.debug("清理旧版服务插件 Runner 缓存失败: {}", legacyRunner, failure);
        }
    }

    private Path pidFile(String pluginId) {
        return runDirectory.resolve(pluginId + ".json");
    }

    private static String safeMessage(Throwable failure) {
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }

    record PidRecord(long pid, long startedAtEpochMilli, String pluginId,
                     String pluginVersion, String artifactSha256, long desktopGeneration,
                     String runnerPath, String runnerMainClass, String pluginPath) { }
}
