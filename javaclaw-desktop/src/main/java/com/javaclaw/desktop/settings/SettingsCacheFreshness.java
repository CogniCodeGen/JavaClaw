package com.javaclaw.desktop.settings;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

/** 页面内快照的五分钟重验期限；只记录成功读取，不缓存数据或失败，也不创建后台计时器。 */
final class SettingsCacheFreshness {
    private static final long MAXIMUM_AGE_NANOS = Duration.ofMinutes(5).toNanos();
    private final LongSupplier nanoTime;
    private long loadedAt;
    private boolean loaded;

    SettingsCacheFreshness() {
        this(System::nanoTime);
    }

    SettingsCacheFreshness(LongSupplier nanoTime) {
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    boolean fresh() {
        return loaded && nanoTime.getAsLong() - loadedAt < MAXIMUM_AGE_NANOS;
    }

    void markFresh() {
        loadedAt = nanoTime.getAsLong();
        loaded = true;
    }

    void invalidate() {
        loaded = false;
    }
}
