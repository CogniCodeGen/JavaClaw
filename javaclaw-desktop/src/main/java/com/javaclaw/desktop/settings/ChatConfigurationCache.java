package com.javaclaw.desktop.settings;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;

import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.ExecutionPreview;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.desktop.DesktopConfigurationChange;

/** UI 线程拥有的聊天作用域缓存；只保存同次已完成的配置与预览，最多 32 项、五分钟，失效不续期。 */
final class ChatConfigurationCache {
    private static final int CAPACITY = 32;
    private static final long LIFETIME_NANOS = Duration.ofMinutes(5).toNanos();
    private final Map<Key, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private final LongSupplier clock;

    ChatConfigurationCache(LongSupplier clock) {
        this.clock = clock;
    }

    Optional<Entry> get(ExecutionSelectionLoader.Scope scope) {
        Key key = Key.of(scope);
        Entry entry = entries.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (clock.getAsLong() - entry.loadedAt() >= LIFETIME_NANOS) {
            entries.remove(key);
            return Optional.empty();
        }
        return Optional.of(entry);
    }

    void put(ExecutionSelectionLoader.Scope scope, ChatConfigurationState state, long revision) {
        if (state.pending()
                || state.dirty()
                || state.snapshot().isEmpty()
                || state.preview().isEmpty()) {
            return;
        }
        entries.put(
                Key.of(scope),
                new Entry(
                        state.snapshot().orElseThrow(),
                        state.preview().orElseThrow(),
                        state.selection(),
                        revision,
                        state.message(),
                        clock.getAsLong()));
        if (entries.size() > CAPACITY) {
            entries.remove(entries.keySet().iterator().next());
        }
    }

    void remove(ExecutionSelectionLoader.Scope scope) {
        entries.remove(Key.of(scope));
    }

    void invalidate(DesktopConfigurationChange change) {
        entries.keySet().removeIf(key -> affected(key, change));
    }

    void clear() {
        entries.clear();
    }

    static boolean affected(ExecutionSelectionLoader.Scope scope, DesktopConfigurationChange change) {
        return affected(Key.of(scope), change);
    }

    private static boolean affected(Key key, DesktopConfigurationChange change) {
        return change.kind() != DesktopConfigurationChange.Kind.EXECUTION
                || (change.workspaceId().isEmpty() || change.workspaceId().equals(key.workspace()))
                        && (change.threadId().isEmpty() || change.threadId().equals(key.thread()));
    }

    /**
     * 同次权威读取的完整结果。
     *
     * @param snapshot 目录与来源，非空
     * @param preview 服务端就绪判断，非空
     * @param selection 已保存的覆盖，非空
     * @param revision 对话写入基线，未保存时为零
     * @param message 展示说明，非空
     * @param loadedAt 单调时钟纳秒，不因使用缓存而续期
     */
    record Entry(
            ExecutionSelectionLoader.Snapshot snapshot,
            ExecutionPreview preview,
            ExecutionOverrides selection,
            long revision,
            String message,
            long loadedAt) {}

    /**
     * @param workspace 工作区标识，可缺省
     * @param thread 对话标识，可缺省
     */
    private record Key(Optional<WorkspaceId> workspace, Optional<ThreadId> thread) {
        private static Key of(ExecutionSelectionLoader.Scope scope) {
            return new Key(scope.workspace().map(Workspace::id), scope.thread().map(ConversationThread::id));
        }
    }
}
