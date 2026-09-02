package com.javaclaw.nativehost.tray;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 读取不含路径或用户数据的托盘 supervisor 心跳。 */
public final class TrayPresenceProbe {
    private static final long MAXIMUM_FILE_BYTES = 128;
    /** 心跳超过此时限即视为失联。 */
    public static final Duration MAXIMUM_AGE = Duration.ofSeconds(15);

    private final Path presenceFile;
    private final Clock clock;
    private final ProcessLookup processes;

    /**
     * 创建当前用户默认探针。
     *
     * @return 读取 {@code .javaclaw/run/tray-v5.presence} 的探针
     */
    public static TrayPresenceProbe currentUser() {
        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        return new TrayPresenceProbe(home.resolve(".javaclaw/run/tray-v5.presence"), Clock.systemUTC());
    }

    /**
     * 创建探针。
     *
     * @param presenceFile 心跳文件
     * @param clock 时钟
     */
    public TrayPresenceProbe(Path presenceFile, Clock clock) {
        this(
                presenceFile,
                clock,
                processId -> ProcessHandle.of(processId)
                        .filter(ProcessHandle::isAlive)
                        .isPresent());
    }

    TrayPresenceProbe(Path presenceFile, Clock clock, ProcessLookup processes) {
        this.presenceFile = Objects.requireNonNull(presenceFile, "presenceFile")
                .toAbsolutePath()
                .normalize();
        this.clock = Objects.requireNonNull(clock, "clock");
        this.processes = Objects.requireNonNull(processes, "processes");
    }

    /**
     * 读取并验证版本、PID、时钟范围和进程存活性。
     *
     * @return 活动投影；任何歧义均 fail closed
     */
    public TrayPresenceStatus status() {
        try {
            if (!Files.isRegularFile(presenceFile)) {
                return TrayPresenceStatus.unavailable("未检测到托盘 supervisor");
            }
            if (Files.size(presenceFile) > MAXIMUM_FILE_BYTES) {
                return TrayPresenceStatus.unavailable("托盘 supervisor 心跳无效");
            }
            return decode(Files.readAllLines(presenceFile));
        } catch (IOException | RuntimeException failure) {
            return TrayPresenceStatus.unavailable("托盘 supervisor 心跳无效");
        }
    }

    private TrayPresenceStatus decode(List<String> lines) {
        if (lines.size() != 3 || !"1".equals(lines.getFirst())) {
            return TrayPresenceStatus.unavailable("托盘 supervisor 心跳版本无效");
        }
        long processId = positiveLong(lines.get(1));
        Instant updatedAt = Instant.ofEpochMilli(positiveLong(lines.get(2)));
        Instant now = clock.instant();
        Duration age = Duration.between(updatedAt, now);
        if (age.isNegative() || age.compareTo(MAXIMUM_AGE) > 0) {
            return TrayPresenceStatus.unavailable("托盘 supervisor 心跳已过期");
        }
        if (!processes.alive(processId)) {
            return TrayPresenceStatus.unavailable("托盘 supervisor 进程已退出");
        }
        return new TrayPresenceStatus(true, Optional.of(processId), Optional.empty());
    }

    private static long positiveLong(String value) {
        if (!value.matches("[1-9][0-9]{0,18}")) {
            throw new IllegalArgumentException("value is not a positive long");
        }
        return Long.parseLong(value);
    }

    @FunctionalInterface
    interface ProcessLookup {
        boolean alive(long processId);
    }
}
