package com.javaclaw.ui.javafx.desktop;

import com.javaclaw.platform.fxml.EmbeddedFxmlLoader;
import java.util.function.BiConsumer;
import javafx.beans.binding.Bindings;
import javafx.css.PseudoClass;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/** 轻量 FXML 视图控制器；服务订阅与桌面操作由预览窗口协调。 */
final class DesktopPreviewController {
    @FXML private VBox root;
    @FXML private Label title;
    @FXML private Label subtitle;
    @FXML private Label status;
    @FXML private Label actionStatus;
    @FXML private Label empty;
    @FXML private Label stateBadge;
    @FXML private ImageView image;
    @FXML private StackPane viewport;
    @FXML private VBox details;
    @FXML private Button takeover;
    @FXML private Button minimize;
    @FXML private Button maximize;
    @FXML private Region resizeGrip;

    private Stage stage;
    private DesktopPreviewChrome chrome;
    private Runnable stopRequested = () -> {};
    private Runnable takeoverRequested = () -> {};
    private BiConsumer<Integer, Integer> viewportChanged = (width, height) -> {};

    VBox load() {
        return EmbeddedFxmlLoader.load(DesktopPreviewController.class.getResource(
                "/fxml/desktop/desktop-preview.fxml"), this, VBox.class);
    }

    @FXML private void initialize() {
        image.fitWidthProperty().bind(Bindings.createDoubleBinding(() -> Math.max(1,
                viewport.getWidth() - viewport.getInsets().getLeft() - viewport.getInsets().getRight()),
                viewport.widthProperty(), viewport.insetsProperty()));
        image.fitHeightProperty().bind(Bindings.createDoubleBinding(() -> Math.max(1,
                viewport.getHeight() - viewport.getInsets().getTop() - viewport.getInsets().getBottom()),
                viewport.heightProperty(), viewport.insetsProperty()));
        viewport.widthProperty().addListener((observable, oldValue, value) -> updateViewport());
        viewport.heightProperty().addListener((observable, oldValue, value) -> updateViewport());
        viewport.insetsProperty().addListener((observable, oldValue, value) -> updateViewport());
        empty.visibleProperty().bind(image.imageProperty().isNull());
        empty.managedProperty().bind(empty.visibleProperty());
    }

    void configure(Stage window, Runnable stop, Runnable authorize,
                   BiConsumer<Integer, Integer> resized) {
        stage = window;
        stopRequested = stop;
        takeoverRequested = authorize;
        viewportChanged = resized;
        chrome = new DesktopPreviewChrome(stage, this::applyMode);
        stage.outputScaleXProperty().addListener((observable, oldValue, value) -> updateViewport());
        stage.outputScaleYProperty().addListener((observable, oldValue, value) -> updateViewport());
        applyMode(DesktopPreviewChrome.Mode.NORMAL);
    }

    private void updateViewport() {
        if (stage == null) return;
        int width = Math.max(1, (int) Math.ceil(image.getFitWidth() * stage.getOutputScaleX()));
        int height = Math.max(1, (int) Math.ceil(image.getFitHeight() * stage.getOutputScaleY()));
        viewportChanged.accept(width, height);
    }

    private void applyMode(DesktopPreviewChrome.Mode mode) {
        boolean mini = mode == DesktopPreviewChrome.Mode.MINI;
        boolean maximized = mode == DesktopPreviewChrome.Mode.MAXIMIZED;
        root.pseudoClassStateChanged(PseudoClass.getPseudoClass("mini"), mini);
        root.pseudoClassStateChanged(PseudoClass.getPseudoClass("maximized"), maximized);
        show(subtitle, !mini);
        show(details, !mini);
        show(resizeGrip, mode == DesktopPreviewChrome.Mode.NORMAL);
        minimize.setText(mini ? "↗" : "−");
        minimize.setAccessibleText(mini ? "还原悬浮窗口" : "最小化为迷你悬浮窗口");
        minimize.setTooltip(new Tooltip(minimize.getAccessibleText()));
        maximize.setText(maximized ? "❐" : "□");
        maximize.setAccessibleText(maximized ? "还原窗口大小" : "最大化悬浮窗口");
        maximize.setTooltip(new Tooltip(maximize.getAccessibleText()));
    }

    private static void show(javafx.scene.Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    void target(String application, String windowTitle) {
        title.setText(application);
        subtitle.setText(windowTitle == null || windowTitle.isBlank()
                || application.equals(windowTitle) ? "桌面会话 · 实时预览" : windowTitle);
    }

    void status(String text) { status.setText(text); }
    void action(String text) { actionStatus.setText(text); }
    void takeoverDisabled(boolean disabled) { takeover.setDisable(disabled); }
    void image(Image frame) { image.setImage(frame); }

    void state(String text, boolean live, boolean warning) {
        stateBadge.setText(text);
        stateBadge.pseudoClassStateChanged(PseudoClass.getPseudoClass("live"), live);
        stateBadge.pseudoClassStateChanged(PseudoClass.getPseudoClass("warning"), warning);
        empty.setText(warning ? "等待桌面权限或前台接管" : live ? "等待实时画面…" : "会话已暂停");
    }

    @FXML private void minimizeRequested() { chrome.toggleMini(); }
    @FXML private void maximizeRequested() { chrome.toggleMaximized(); }
    @FXML private void stopRequested() { stopRequested.run(); }
    @FXML private void takeoverRequested() { takeoverRequested.run(); }
    @FXML private void dragStarted(MouseEvent event) { chrome.dragStarted(event); }
    @FXML private void dragContinued(MouseEvent event) { chrome.dragContinued(event); }
    @FXML private void headerClicked(MouseEvent event) { chrome.headerClicked(event); }
    @FXML private void resizeStarted(MouseEvent event) { chrome.resizeStarted(event); }
    @FXML private void resizeContinued(MouseEvent event) { chrome.resizeContinued(event); }

    @FXML private void keyPressed(KeyEvent event) {
        if (event.getCode() == KeyCode.ESCAPE) chrome.restore();
        else if (event.isShortcutDown() && event.getCode() == KeyCode.M) chrome.toggleMini();
        else return;
        event.consume();
    }
}
