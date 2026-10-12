package com.javaclaw.desktop.ffm.macos;

import com.javaclaw.desktop.nativebridge.DesktopBridge.NativeWindow;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MacWindowsPolicyTest {
    @Test void discoveryExcludesHiddenAndMinimizedWindowsBeforeAccessibilityDecoration() {
        NativeWindow hidden = window(1, 100, 0, "hidden");
        NativeWindow visible = window(2, 100, 2, "QQ");
        NativeWindow minimized = window(3, 100, 1, "minimized");
        NativeWindow hiddenPopup = window(4, 100, 4, "popup");
        List<NativeWindow> metadata = List.of(hidden, visible, minimized, hiddenPopup);

        assertEquals(List.of(visible), MacWindows.discoverableWindows(metadata));
        assertEquals(List.of(hidden, visible, minimized, hiddenPopup), metadata);
    }

    @Test void visibleUnnamedPopupsAndSystemPanelsKeepTheirFrontToBackOrder() {
        NativeWindow popup = window(1, 100, 2 | 4, "");
        NativeWindow systemPanel = window(2, 100, 2 | 8, "Control Center");
        NativeWindow ordinary = window(3, 100, 2, "QQ");

        assertEquals(List.of(popup, systemPanel, ordinary),
                MacWindows.discoverableWindows(List.of(popup, systemPanel, ordinary)));
        assertTrue(MacWindows.discoverableWindows(List.of()).isEmpty());
    }

    @Test void anObservedWindowStillRefreshesWhenHiddenOrMinimized() {
        NativeWindow observed = window(1, 100, 2, "QQ");
        for (int flags : new int[]{0, 1}) {
            NativeWindow live = window(1, 100, flags, "QQ");
            List<NativeWindow> metadata = List.of(live);

            assertTrue(MacWindows.discoverableWindows(metadata).isEmpty());
            assertSame(live, MacWindows.originalWindow(observed, metadata));
        }
    }

    @Test void aHiddenWindowMustStillMatchItsOriginalProcessWindowAndBirthInstance() {
        NativeWindow observed = window(1, 100, 2, "QQ");
        NativeWindow restarted = window(1, 101, 0, "QQ");
        NativeWindow anotherWindow = window(2, 100, 0, "QQ");
        NativeWindow anotherProcess = new NativeWindow(11, 1, 100, 0, 0, 800, 600, 0,
                "QQ", "QQ", "com.tencent.qq");

        assertThrows(IllegalStateException.class, () -> MacWindows.originalWindow(observed,
                List.of(restarted, anotherWindow, anotherProcess)));
        assertThrows(IllegalStateException.class, () -> MacWindows.originalWindow(observed, List.of()));
    }

    private static NativeWindow window(long id, long birth, int flags, String title) {
        return new NativeWindow(10, id, birth, 0, 0, 800, 600, flags,
                "QQ", title, "com.tencent.qq");
    }
}
