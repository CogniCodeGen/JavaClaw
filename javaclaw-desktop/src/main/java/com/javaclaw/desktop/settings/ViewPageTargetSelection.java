package com.javaclaw.desktop.settings;

import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.desktop.view.ViewData;
import com.javaclaw.desktop.view.ViewPageDirection;
import com.javaclaw.extension.spi.ViewSchema;

/** 保存后按服务端分页游标定位新条目；不把当前页之外的行伪造为可提交的选择。 */
final class ViewPageTargetSelection {
    private final Set<String> visited = new HashSet<>();
    private String sourceId;
    private String keyField;
    private String key;

    void begin(ViewSchema schema, ViewPageCursorState navigation, String source, Optional<String> selected) {
        clear();
        String field = schema.nodes().stream()
                .map(node -> switch (node) {
                    case ViewSchema.Table table when table.sourceId().equals(source) -> table.keyField();
                    case ViewSchema.ListView list when list.sourceId().equals(source) -> list.keyField();
                    default -> null;
                })
                .filter(Objects::nonNull)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("页面没有声明可选择的数据源：" + source));
        navigation.rewind(source);
        navigation.selections.put(source, selected.orElse(""));
        if (selected.isPresent()) {
            sourceId = source;
            keyField = field;
            key = selected.orElseThrow();
        }
    }

    boolean advance(ViewData data, ViewPageCursorState navigation) {
        if (sourceId == null) {
            return false;
        }
        ViewData.Source source = data.source(sourceId);
        if (source.rows().stream().anyMatch(row -> key.equals(Objects.toString(row.get(keyField), "")))
                || !source.hasMore()) {
            clear();
            return false;
        }
        if (!visited.add(source.cursor()) || visited.contains(source.nextCursor())) {
            clear();
            throw new IllegalStateException("页面返回了重复分页游标，无法定位所选条目");
        }
        return navigation.move(sourceId, source, ViewPageDirection.NEXT);
    }

    void clear() {
        sourceId = null;
        keyField = null;
        key = null;
        visited.clear();
    }
}
