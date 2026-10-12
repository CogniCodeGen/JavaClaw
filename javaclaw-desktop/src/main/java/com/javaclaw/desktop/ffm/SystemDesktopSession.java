package com.javaclaw.desktop.ffm;

import com.javaclaw.desktop.api.DesktopAction;
import com.javaclaw.desktop.api.DesktopActionResult;
import com.javaclaw.desktop.api.DesktopElement;
import com.javaclaw.desktop.api.DesktopFrame;
import com.javaclaw.desktop.nativebridge.DesktopBridge.NativeWindow;
import java.util.List;
import java.util.Optional;

/** One process-instance and window binding; Java owns its observations and dispatch bookkeeping. */
public interface SystemDesktopSession extends AutoCloseable {
    NativeWindow current();
    Optional<DesktopFrame> capture(String targetId, int timeoutMillis);
    List<DesktopElement> elements(DesktopFrame frame);
    default String diagnostics() { return ""; }
    Optional<Boolean> targetActive();
    void prepareForeground();
    void restoreForeground();
    DesktopActionResult perform(DesktopAction action, boolean foreground);
    @Override void close();
}
