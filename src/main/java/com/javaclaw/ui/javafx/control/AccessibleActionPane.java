package com.javaclaw.ui.javafx.control;

import javafx.scene.AccessibleAction;
import javafx.scene.AccessibleRole;
import javafx.scene.Node;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.VBox;

import java.util.Objects;

/**
 * 可像按钮一样被键盘和辅助技术激活的 VBox。
 *
 * <p>用于仍需保留复杂布局、拖拽或右键菜单语义的卡片，不替换其现有鼠标处理。</p>
 */
public final class AccessibleActionPane extends VBox {

    private Runnable accessibleAction = () -> {};

    public AccessibleActionPane(double spacing, Node... children) {
        super(spacing, children);
        setAccessibleRole(AccessibleRole.BUTTON);
        setFocusTraversable(true);
        addEventHandler(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ENTER || event.getCode() == KeyCode.SPACE) {
                fireAccessibleAction();
                event.consume();
            }
        });
        addEventHandler(MouseEvent.MOUSE_PRESSED, event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                requestFocus();
            }
        });
    }

    public void setOnAccessibleAction(Runnable action) {
        accessibleAction = Objects.requireNonNull(action, "action");
    }

    @Override
    public void executeAccessibleAction(AccessibleAction action, Object... parameters) {
        if (action == AccessibleAction.FIRE) {
            fireAccessibleAction();
            return;
        }
        super.executeAccessibleAction(action, parameters);
    }

    private void fireAccessibleAction() {
        if (!isDisabled()) {
            accessibleAction.run();
        }
    }
}
