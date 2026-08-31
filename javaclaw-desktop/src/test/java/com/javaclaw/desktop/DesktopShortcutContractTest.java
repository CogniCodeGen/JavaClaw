package com.javaclaw.desktop;

import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DesktopShortcutContractTest {
    @Test
    void onlyCurrentSupportedCommandShortcutsAreMapped() {
        assertEquals(MainController.ShortcutAction.NEW_THREAD, MainController.shortcutAction(KeyCode.N));
        assertEquals(MainController.ShortcutAction.OPEN_SETTINGS, MainController.shortcutAction(KeyCode.COMMA));
        assertEquals(MainController.ShortcutAction.TOGGLE_SIDEBAR, MainController.shortcutAction(KeyCode.BACK_SLASH));
        assertEquals(MainController.ShortcutAction.FOCUS_COMPOSER, MainController.shortcutAction(KeyCode.K));
        assertEquals(MainController.ShortcutAction.OPEN_MCP, MainController.shortcutAction(KeyCode.M));
        assertEquals(MainController.ShortcutAction.SHOW_HELP, MainController.shortcutAction(KeyCode.SLASH));
        assertEquals(MainController.ShortcutAction.NONE, MainController.shortcutAction(KeyCode.L));
    }
}
