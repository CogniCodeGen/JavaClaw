package com.javaclaw.memory;

import com.javaclaw.memory.store.MemoryStore;

import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.io.UncheckedIOException;
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
            if (isDeleted(key)) throw new IllegalStateException("会话记忆已删除: " + key);
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

    static boolean isDeleted(Path path) {
        return Files.exists(tombstone(path));
    }

    static void withLiveGraph(Path path, Runnable operation) {
        synchronized (ENTRIES) {
            if (isDeleted(path)) throw new IllegalStateException("来源会话已删除");
            operation.run();
        }
    }

    static void updateMetadata(Path directory, java.util.function.Function<com.javaclaw.memory.model.MemoryRoot,
            java.util.List<Object>> change) {
        Path key = directory.toAbsolutePath().normalize();
        synchronized (ENTRIES) {
            if (!Files.exists(key.resolve("channel_0"))) return;
            Entry existing = ENTRIES.get(key);
            if (existing == null) com.javaclaw.memory.store.MemoryMetadataMaintenance.update(key, change);
            else com.javaclaw.memory.store.MemoryMetadataMaintenance.update(existing.store, change);
        }
    }

    private static Path tombstone(Path path) {
        return path.resolveSibling(path.getFileName() + ".deleted");
    }

    /** Tombstone precedes closing the write gate. Old leases cannot resurrect this graph. */
    static void delete(Path path) {
        Path key = path.toAbsolutePath().normalize();
        synchronized (ENTRIES) {
            try {
                Files.createDirectories(key.getParent());
                Files.writeString(tombstone(key), "deleted\n");
            } catch (IOException failure) {
                throw new UncheckedIOException("无法持久化记忆删除墓碑", failure);
            }
            Entry entry = ENTRIES.get(key);
            if (entry != null) {
                entry.deleting = true;
                entry.store.invalidate();
            } else {
                deleteFiles(key);
            }
        }
    }

    private static void deleteFiles(Path path) {
        if (!Files.exists(path)) return;
        try (var files = Files.walk(path)) {
            for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(file);
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("无法删除会话记忆目录", failure);
        }
    }

    private static final class Entry {
        private final Path path;
        private final int dimensions;
        private final MemoryStore store;
        private int references;
        private boolean deleting;

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
                    if (entry.deleting) deleteFiles(entry.path);
                }
            }
        }
    }
}
