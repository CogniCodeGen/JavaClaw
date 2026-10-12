package com.javaclaw.ui.javafx.desktop;

import com.javaclaw.desktop.api.DesktopFrame;
import com.javaclaw.desktop.api.DesktopVirtualInputState;
import java.awt.image.BufferedImage;

/** Called only by the preview's UI dispatcher; has no JavaFX or service dependency. */
interface DesktopPreviewHost {
    void show();
    void hide();
    void close();
    void frame(DesktopFrame frame, BufferedImage image);
    void clear();
    void status(String text);
    void action(String text);
    void state(String text, boolean warning);
    void input(DesktopVirtualInputState input);
    void target(String application, String title);
    void takeoverEnabled(boolean enabled);

    @FunctionalInterface
    interface Factory {
        DesktopPreviewHost create(String application, String title, int position,
                Runnable stop, Runnable takeover, Runnable manualInput);
    }
}
