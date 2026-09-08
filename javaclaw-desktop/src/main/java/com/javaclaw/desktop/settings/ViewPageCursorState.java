package com.javaclaw.desktop.settings;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.desktop.view.ViewData;
import com.javaclaw.desktop.view.ViewLoadRequest;
import com.javaclaw.desktop.view.ViewPageDirection;
import com.javaclaw.extension.spi.ViewInitialSelection;
import com.javaclaw.protocol.CanonicalJson;

/** 页面拥有的分页游标；刷新不会清除当前选择，换页不接受服务端以外的游标。 */
final class ViewPageCursorState {
    final Map<String, String> selections = new LinkedHashMap<>();
    private final Map<String, CanonicalPayload> initialSelections = new LinkedHashMap<>();
    private final CanonicalJson json = new CanonicalJson();
    private final Map<String, String> cursors = new LinkedHashMap<>();
    private final Map<String, Integer> pageIndexes = new LinkedHashMap<>();
    private final Map<String, Deque<String>> history = new LinkedHashMap<>();

    boolean move(String sourceId, ViewData.Source source, ViewPageDirection direction) {
        Deque<String> previous = history.computeIfAbsent(sourceId, ignored -> new ArrayDeque<>());
        if (direction == ViewPageDirection.NEXT && source.hasMore()) {
            previous.push(source.cursor());
            cursors.put(sourceId, source.nextCursor());
            pageIndexes.put(sourceId, source.pageIndex() + 1);
        } else if (direction == ViewPageDirection.PREVIOUS && !previous.isEmpty()) {
            cursors.put(sourceId, previous.pop());
            pageIndexes.put(sourceId, Math.max(0, source.pageIndex() - 1));
        } else {
            return false;
        }
        return true;
    }

    ViewLoadRequest request(Map<String, String> graphWindows) {
        return new ViewLoadRequest(cursors, selections, pageIndexes, graphWindows, initialSelections);
    }

    void acceptInitialSelections(ViewData data) {
        data.sources().forEach((id, source) -> {
            if (source.values().containsKey(ViewInitialSelection.VALUES_KEY)) {
                // 自动初选不能伪装成用户选择：目录同 ID 更新后仍须匹配原 revision，避免静默升级引用。
                initialSelections.computeIfAbsent(id, ignored -> initialHint(source));
            }
        });
    }

    private CanonicalPayload initialHint(ViewData.Source source) {
        try {
            return json.encode(source.values().get(ViewInitialSelection.VALUES_KEY));
        } catch (IllegalArgumentException | com.javaclaw.protocol.ProtocolException invalidMetadata) {
            // 无效提示也消费首次初始化，后续响应不能把它偷偷替换成另一引用。
            return json.parse("{}");
        }
    }

    void clear() {
        initialSelections.clear();
        cursors.clear();
        selections.clear();
        pageIndexes.clear();
        history.clear();
    }
}
