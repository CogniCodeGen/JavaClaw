package com.javaclaw.desktop.settings;

import java.util.LinkedHashMap;
import java.util.Map;

import com.javaclaw.protocol.ExtensionRpcContracts;

/** 当前 Workspace 的扩展事件与本地命令版本；作用域切换时必须一起清空，不能丢弃新作用域的低版本事件。 */
final class ViewPageEventState {
    private final Map<ViewEventKey, Long> received = new LinkedHashMap<>();
    private final Map<String, Long> completedCommands = new LinkedHashMap<>();

    boolean changed(ExtensionRpcContracts.ExtensionEvent event) {
        ViewEventKey key = new ViewEventKey(event.scope(), event.resourceId(), event.operation());
        Long previous = received.get(key);
        if (previous != null && event.revision() <= previous) {
            return false;
        }
        received.put(key, event.revision());
        return event.revision() > completedCommands.getOrDefault(event.operation(), 0L);
    }

    void completed(String operation, long revision) {
        completedCommands.merge(operation, revision, Math::max);
    }

    void clear() {
        received.clear();
        completedCommands.clear();
    }
}
