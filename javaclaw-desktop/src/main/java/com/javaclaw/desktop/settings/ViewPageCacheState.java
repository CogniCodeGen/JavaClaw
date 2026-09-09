package com.javaclaw.desktop.settings;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * 单个扩展页面的成功结果缓存；仅当前 Workspace 和页面拥有数据、Schema 及节点。
 *
 * <p>动态数据最多复用 15 秒，与可见页对账间隔一致。读取失败和取消不续期，重连及作用域变化还会使目录失效。
 */
final class ViewPageCacheState {
    private static final long MAX_AGE_NANOS = Duration.ofSeconds(15).toNanos();

    private final LongSupplier clock;
    private long loadedAt;
    private boolean ready;
    private boolean catalogInvalidated = true;

    ViewPageCacheState() {
        this(System::nanoTime);
    }

    ViewPageCacheState(LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    boolean fresh() {
        return ready && clock.getAsLong() - loadedAt < MAX_AGE_NANOS;
    }

    void loading() {
        ready = false;
    }

    void loaded() {
        loadedAt = clock.getAsLong();
        ready = true;
    }

    boolean requiresCatalog() {
        return catalogInvalidated;
    }

    void catalogLoaded() {
        catalogInvalidated = false;
    }

    void invalidate() {
        ready = false;
        catalogInvalidated = true;
    }
}
