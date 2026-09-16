package com.javaclaw.desktop.view;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import javafx.scene.control.MultipleSelectionModel;

/** 行选择只有在页面许可后才影响命令绑定；拒绝时同步恢复单值属性与所选行集合。 */
final class ViewSelectionBinding {
    private final MultipleSelectionModel<Map<String, Object>> selection;
    private final List<Map<String, Object>> rows;
    private Map<String, Object> accepted;
    private boolean restoring;

    ViewSelectionBinding(
            MultipleSelectionModel<Map<String, Object>> selection,
            List<Map<String, Object>> rows,
            String sourceId,
            String keyField,
            ViewInteractionHandler interactions,
            ViewCommandBindingResolver bindings) {
        this.selection = selection;
        this.rows = rows;
        accepted = selection.getSelectedItem();
        selection.selectedItemProperty().addListener((ignored, previous, next) -> {
            if (restoring || Objects.equals(accepted, next)) {
                return;
            }
            if (next != null && selection.getSelectedIndex() != rows.indexOf(next)) {
                // TableView.select(index) 在索引监听期间被回滚后，仍可能补发原目标的 selectedItem。
                // 此时索引已恢复，补发值不是新的用户选择，必须直接修复而不能重复弹出草稿确认。
                restore();
                return;
            }
            if (!interactions.selectRequested(sourceId, key(next, keyField))) {
                restore();
                return;
            }
            accepted = next;
            bindings.select(sourceId, next);
        });
    }

    private void restore() {
        restoring = true;
        try {
            // TableView 的一次选择可能发布两次通知；恢复已接受的行而非通知中的 previous，防止第二次通知留下错误目标。
            int index = rows.indexOf(accepted);
            if (index < 0) {
                selection.clearSelection();
            } else {
                selection.clearAndSelect(index);
            }
        } finally {
            restoring = false;
        }
    }

    private static Optional<String> key(Map<String, Object> row, String field) {
        String value = row == null ? "" : Objects.toString(row.get(field), "").strip();
        return value.isEmpty() ? Optional.empty() : Optional.of(value);
    }
}
