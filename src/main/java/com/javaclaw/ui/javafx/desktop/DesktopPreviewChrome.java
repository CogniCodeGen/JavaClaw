package com.javaclaw.ui.javafx.desktop;

import java.util.function.Consumer;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.MouseButton;
import javafx.stage.Screen;
import javafx.stage.Stage;

/** 悬浮窗口的尺寸、拖动和三态切换；迷你状态仍保留实时画面。 */
final class DesktopPreviewChrome {
    enum Mode { NORMAL, MINI, MAXIMIZED }

    static final double NORMAL_WIDTH = 520;
    static final double NORMAL_HEIGHT = 430;
    static final double MINI_WIDTH = 280;
    static final double MINI_HEIGHT = 216;
    static final double MIN_WIDTH = 400;
    static final double MIN_HEIGHT = 340;

    private final Stage stage;
    private final Consumer<Mode> changed;
    private Mode mode = Mode.NORMAL;
    private Mode beforeMini = Mode.NORMAL;
    private Rectangle2D normalBounds;
    private double offsetX;
    private double offsetY;
    private double resizeX;
    private double resizeY;
    private double resizeWidth;
    private double resizeHeight;
    private boolean dragging;

    DesktopPreviewChrome(Stage stage, Consumer<Mode> changed) {
        this.stage = stage;
        this.changed = changed;
    }

    Mode mode() { return mode; }

    void toggleMini() {
        if (mode == Mode.MINI) {
            change(beforeMini);
        } else {
            beforeMini = mode;
            change(Mode.MINI);
        }
    }

    void toggleMaximized() {
        change(mode == Mode.MAXIMIZED ? Mode.NORMAL : Mode.MAXIMIZED);
    }

    void restore() {
        if (mode != Mode.NORMAL) change(Mode.NORMAL);
    }

    private void change(Mode next) {
        if (mode == Mode.NORMAL) normalBounds = bounds();
        Rectangle2D screen = screenBounds();
        mode = next;
        changed.accept(next);
        switch (next) {
            case NORMAL -> apply(normalBounds == null
                    ? new Rectangle2D(stage.getX(), stage.getY(), NORMAL_WIDTH, NORMAL_HEIGHT)
                    : normalBounds);
            case MINI -> apply(new Rectangle2D(
                    Math.max(screen.getMinX(), Math.min(stage.getX(), screen.getMaxX() - MINI_WIDTH)),
                    Math.max(screen.getMinY(), Math.min(stage.getY(), screen.getMaxY() - MINI_HEIGHT)),
                    MINI_WIDTH, MINI_HEIGHT));
            case MAXIMIZED -> apply(screen);
        }
    }

    void dragStarted(MouseEvent event) {
        dragging = event.isPrimaryButtonDown() && !isButton(event.getTarget())
                && mode != Mode.MAXIMIZED;
        if (!dragging) return;
        offsetX = event.getScreenX() - stage.getX();
        offsetY = event.getScreenY() - stage.getY();
    }

    void dragContinued(MouseEvent event) {
        if (!dragging || !event.isPrimaryButtonDown()) return;
        stage.setX(event.getScreenX() - offsetX);
        stage.setY(event.getScreenY() - offsetY);
        event.consume();
    }

    void headerClicked(MouseEvent event) {
        if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2
                && !isButton(event.getTarget())) toggleMaximized();
    }

    void resizeStarted(MouseEvent event) {
        resizeX = event.getScreenX();
        resizeY = event.getScreenY();
        resizeWidth = stage.getWidth();
        resizeHeight = stage.getHeight();
        event.consume();
    }

    void resizeContinued(MouseEvent event) {
        if (mode != Mode.NORMAL || !event.isPrimaryButtonDown()) return;
        Rectangle2D screen = screenBounds();
        stage.setWidth(Math.max(MIN_WIDTH, Math.min(screen.getMaxX() - stage.getX(),
                resizeWidth + event.getScreenX() - resizeX)));
        stage.setHeight(Math.max(MIN_HEIGHT, Math.min(screen.getMaxY() - stage.getY(),
                resizeHeight + event.getScreenY() - resizeY)));
        event.consume();
    }

    private Rectangle2D bounds() {
        return new Rectangle2D(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
    }

    private Rectangle2D screenBounds() {
        return Screen.getScreensForRectangle(stage.getX(), stage.getY(),
                Math.max(1, stage.getWidth()), Math.max(1, stage.getHeight()))
                .stream().findFirst().orElse(Screen.getPrimary()).getVisualBounds();
    }

    private void apply(Rectangle2D bounds) {
        stage.setWidth(bounds.getWidth());
        stage.setHeight(bounds.getHeight());
        stage.setX(bounds.getMinX());
        stage.setY(bounds.getMinY());
    }

    private static boolean isButton(Object target) {
        for (Node node = target instanceof Node value ? value : null;
                node != null; node = node.getParent()) {
            if (node instanceof Button) return true;
        }
        return false;
    }
}
