package com.javaclaw.desktop.settings;

import java.util.ArrayList;
import java.util.List;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.TextInputControl;

/**
 * 同一 Schema 刷新前的焦点路径；只恢复展示状态，不保留或重放表单值。
 *
 * @param path 从页面根到焦点控件的子节点索引，不可空
 * @param type 原焦点控件类型，不可空；结构改变时拒绝转移给其他类型
 * @param anchor 文本选区锚点，单位为 UTF-16 字符；非文本控件为 -1
 * @param caret 文本光标位置，单位为 UTF-16 字符；非文本控件为 -1
 */
record ViewPageFocus(List<Integer> path, Class<?> type, int anchor, int caret) {
    static ViewPageFocus capture(Node root) {
        Node focused =
                root == null || root.getScene() == null ? null : root.getScene().getFocusOwner();
        if (focused == null) {
            return null;
        }
        List<Integer> path = new ArrayList<>();
        for (Node current = focused; current != root; current = current.getParent()) {
            Parent parent = current.getParent();
            if (parent == null) {
                return null;
            }
            path.addFirst(parent.getChildrenUnmodifiable().indexOf(current));
        }
        int anchor = focused instanceof TextInputControl input ? input.getAnchor() : -1;
        int caret = focused instanceof TextInputControl input ? input.getCaretPosition() : -1;
        return new ViewPageFocus(List.copyOf(path), focused.getClass(), anchor, caret);
    }

    void restore(Node root) {
        Node target = root;
        for (int index : path) {
            if (!(target instanceof Parent parent)
                    || index >= parent.getChildrenUnmodifiable().size()) {
                return;
            }
            target = parent.getChildrenUnmodifiable().get(index);
        }
        if (target.getClass() != type || target.isDisabled() || !target.isVisible()) {
            return;
        }
        target.requestFocus();
        if (target instanceof TextInputControl input && anchor >= 0) {
            input.selectRange(Math.min(anchor, input.getLength()), Math.min(caret, input.getLength()));
        }
    }
}
