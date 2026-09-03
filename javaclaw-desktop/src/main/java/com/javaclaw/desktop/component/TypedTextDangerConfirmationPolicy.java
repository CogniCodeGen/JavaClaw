package com.javaclaw.desktop.component;

import java.util.Objects;
import java.util.function.Supplier;

import javafx.scene.Node;
import javafx.scene.control.TextInputDialog;

/** 要求逐字输入动态确认文本的危险动作确认策略。 */
public final class TypedTextDangerConfirmationPolicy implements DangerConfirmationPolicy {
    private final Node ownerNode;
    private final Supplier<String> expectedText;

    /**
     * 创建逐字确认策略。
     *
     * @param ownerNode 用于解析所属窗口的页面节点
     * @param expectedText 每次点击时读取的确认文本；资源选择变化后不会沿用旧值
     */
    public TypedTextDangerConfirmationPolicy(Node ownerNode, Supplier<String> expectedText) {
        this.ownerNode = Objects.requireNonNull(ownerNode, "ownerNode");
        this.expectedText = Objects.requireNonNull(expectedText, "expectedText");
    }

    @Override
    public boolean confirm(DangerConfirmationRequest request) {
        DangerConfirmationRequest checked = Objects.requireNonNull(request, "request");
        String expected = Objects.requireNonNullElse(expectedText.get(), "").strip();
        if (expected.isEmpty()) {
            return false;
        }
        TextInputDialog dialog = PlatformDialogs.exactText(
                ownerNode, checked.title(), checked.consequence(), expected, checked.actionLabel());
        return dialog.showAndWait().filter(expected::equals).isPresent();
    }
}
