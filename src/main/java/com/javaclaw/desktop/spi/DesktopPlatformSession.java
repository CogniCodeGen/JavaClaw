package com.javaclaw.desktop.spi;

import com.javaclaw.desktop.api.DesktopAction;
import com.javaclaw.desktop.api.DesktopActionResult;
import com.javaclaw.desktop.api.DesktopFrame;
import com.javaclaw.desktop.api.DesktopTarget;
import com.javaclaw.desktop.api.DesktopElement;
import java.util.List;
import java.util.Optional;

/** Native-facing session, controlled exclusively by DesktopSessionService. */
public interface DesktopPlatformSession extends AutoCloseable {
    DesktopTarget currentTarget();
    Optional<DesktopFrame> pollFrame(int timeoutMillis);
    default List<DesktopElement> elements(DesktopFrame frame) { return List.of(); }
    /** Diagnostics for the last accessibility catalog read, without field values or user text. */
    default String elementDiagnostics() { return ""; }
    /** Focuses the exact target window for one observed foreground action. */
    default void prepareForeground() {
        throw new UnsupportedOperationException("platform cannot confirm exact foreground window");
    }
    /** Restores the previous focus only while this session still owns it. */
    default void restoreForeground() {
        throw new UnsupportedOperationException("platform cannot restore foreground focus");
    }
    DesktopActionResult perform(DesktopAction action, boolean foreground);
    @Override void close();
}
