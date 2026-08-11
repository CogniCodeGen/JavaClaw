package com.javaclaw.memory;

import com.javaclaw.memory.store.MemoryStore;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 进程内共享记忆存储的引用计数注册表。
 *
 * <p>工作区运行时重建期间，旧任务可能仍在响应取消。新旧运行时必须复用同一路径的
 * {@link MemoryStore}，直到最后一个租约释放，避免重复打开底层目录或过早释放目录锁。
 * 所有注册表状态都由同一监视器保护；租约释放幂等。</p>
 */
final class MemoryStoreRegistry {

    private static final Map<Path, Entry> ENTRIES = new HashMap<>();

    private MemoryStoreRegistry() {
    }

    static Lease acquire(Path memoryDir, int dimensions) {
        Path key = Objects.requireNonNull(memoryDir, "memoryDir")
                .toAbsolutePath().normalize();
        synchronized (ENTRIES) {
            Entry entry = ENTRIES.get(key);
            if (entry == null) {
                MemoryStore created = new MemoryStore(key, dimensions, "workspace");
                created.open();
                entry = new Entry(key, dimensions, created);
                ENTRIES.put(key, entry);
            } else if (entry.dimensions != dimensions) {
                throw new IllegalStateException(
                        "同一记忆库不能同时使用不同向量维度: path=" + key
                                + ", opened=" + entry.dimensions
                                + ", requested=" + dimensions);
            }
            entry.references++;
            return new Lease(entry);
        }
    }

    private static final class Entry {
        private final Path path;
        private final int dimensions;
        private final MemoryStore store;
        private int references;

        private Entry(Path path, int dimensions, MemoryStore store) {
            this.path = Objects.requireNonNull(path);
            this.dimensions = dimensions;
            this.store = Objects.requireNonNull(store);
        }
    }

    static final class Lease implements AutoCloseable {
        private final Entry entry;
        private boolean closed;

        private Lease(Entry entry) {
            this.entry = entry;
        }

        MemoryStore store() {
            synchronized (ENTRIES) {
                if (closed) {
                    throw new IllegalStateException("记忆存储租约已释放");
                }
                return entry.store;
            }
        }

        @Override
        public void close() {
            synchronized (ENTRIES) {
                if (closed) {
                    return;
                }
                closed = true;
                entry.references--;
                if (entry.references < 0) {
                    throw new IllegalStateException("记忆存储租约计数失衡: " + entry.path);
                }
                if (entry.references == 0) {
                    if (!ENTRIES.remove(entry.path, entry)) {
                        throw new IllegalStateException(
                                "记忆存储注册表状态失衡: " + entry.path);
                    }
                    entry.store.close();
                }
            }
        }
    }
}
