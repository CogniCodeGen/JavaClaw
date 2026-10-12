package com.javaclaw.ui.javafx.control;

import javafx.scene.AccessibleAction;
import javafx.scene.AccessibleRole;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessibleActionPaneTest {

    @Test
    void exposesButtonRoleAndSupportsAssistiveAndKeyboardActivation() {
        var calls = new AtomicInteger();
        var pane = new AccessibleActionPane(0);
        pane.setOnAccessibleAction(calls::incrementAndGet);

        pane.executeAccessibleAction(AccessibleAction.FIRE);
        pane.fireEvent(keyPressed(KeyCode.ENTER));
        pane.fireEvent(keyPressed(KeyCode.SPACE));

        assertEquals(AccessibleRole.BUTTON, pane.getAccessibleRole());
        assertTrue(pane.isFocusTraversable());
        assertEquals(3, calls.get());
    }

    private static KeyEvent keyPressed(KeyCode code) {
        return new KeyEvent(
                KeyEvent.KEY_PRESSED, "", "", code,
                false, false, false, false);
    }
}
