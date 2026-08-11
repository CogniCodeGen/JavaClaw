package com.javaclaw.ui.javafx.image;

import javafx.beans.value.ChangeListener;
import javafx.fxml.FXML;
import javafx.scene.Cursor;
import javafx.scene.Group;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.input.ZoomEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** 图片查看器 Controller：只协调 FXML 事件和视口状态变换。 */
public final class ImageViewerController implements AutoCloseable {

    static final double MIN_SCALE = 0.1;
    static final double MAX_SCALE = 12.0;
    private static final double ZOOM_STEP = 1.2;
    private static final double SCROLL_ZOOM_BASE = 1.004;

    @FXML private Pane canvas;
    @FXML private StackPane viewport;
    @FXML private Group imageGroup;
    @FXML private ImageView imageView;
    @FXML private Label fileNameLabel;

    private final ImageViewerViewModel viewModel = new ImageViewerViewModel();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ChangeListener<Number> viewportSizeListener =
            (observable, previous, current) -> {
                if (!viewModel.fittedProperty().get()) fitToWindow();
            };
    private Runnable closeWindow = () -> {};
    private double dragSceneX;
    private double dragSceneY;
    private double dragTranslateX;
    private double dragTranslateY;

    @FXML
    private void initialize() {
        imageView.imageProperty().bind(viewModel.imageProperty());
        fileNameLabel.textProperty().bind(viewModel.fileNameProperty());
        imageGroup.scaleXProperty().bind(viewModel.scaleProperty());
        imageGroup.scaleYProperty().bind(viewModel.scaleProperty());
        imageGroup.translateXProperty().bind(viewModel.translateXProperty());
        imageGroup.translateYProperty().bind(viewModel.translateYProperty());
        viewport.widthProperty().addListener(viewportSizeListener);
        viewport.heightProperty().addListener(viewportSizeListener);
        canvas.setCursor(Cursor.OPEN_HAND);
    }

    void configure(Image image, String fileName, Runnable closeAction) {
        viewModel.imageProperty().set(Objects.requireNonNull(image, "image"));
        viewModel.fileNameProperty().set(Objects.requireNonNullElse(fileName, ""));
        closeWindow = Objects.requireNonNull(closeAction, "closeAction");
        viewModel.fittedProperty().set(false);
    }

    @FXML
    void zoomInRequested() {
        zoomAt(ZOOM_STEP, viewport.getWidth() / 2.0, viewport.getHeight() / 2.0);
    }

    @FXML
    void zoomOutRequested() {
        zoomAt(1.0 / ZOOM_STEP, viewport.getWidth() / 2.0, viewport.getHeight() / 2.0);
    }

    @FXML
    void fitRequested() {
        fitToWindow();
    }

    @FXML
    private void closeRequested() {
        closeWindow.run();
    }

    @FXML
    private void canvasScrolled(ScrollEvent event) {
        if (event.getDeltaY() == 0) return;
        zoomAt(Math.pow(SCROLL_ZOOM_BASE, event.getDeltaY()), event.getX(), event.getY());
        event.consume();
    }

    @FXML
    private void canvasZoomed(ZoomEvent event) {
        if (event.getZoomFactor() <= 0 || event.getZoomFactor() == 1.0) return;
        zoomAt(event.getZoomFactor(), event.getX(), event.getY());
        event.consume();
    }

    @FXML
    private void dragStarted(MouseEvent event) {
        if (event.getButton() != MouseButton.PRIMARY) return;
        dragSceneX = event.getSceneX();
        dragSceneY = event.getSceneY();
        dragTranslateX = viewModel.translateXProperty().get();
        dragTranslateY = viewModel.translateYProperty().get();
        canvas.setCursor(Cursor.CLOSED_HAND);
    }

    @FXML
    private void dragContinued(MouseEvent event) {
        if (!event.isPrimaryButtonDown()) return;
        viewModel.translateXProperty().set(
                dragTranslateX + event.getSceneX() - dragSceneX);
        viewModel.translateYProperty().set(
                dragTranslateY + event.getSceneY() - dragSceneY);
    }

    @FXML
    private void dragFinished(MouseEvent event) {
        canvas.setCursor(Cursor.OPEN_HAND);
    }

    @FXML
    void keyPressed(KeyEvent event) {
        KeyCode code = event.getCode();
        if (code == KeyCode.ESCAPE) {
            closeWindow.run();
        } else if (code == KeyCode.PLUS || code == KeyCode.EQUALS || code == KeyCode.ADD) {
            zoomInRequested();
        } else if (code == KeyCode.MINUS || code == KeyCode.SUBTRACT) {
            zoomOutRequested();
        } else if (code == KeyCode.DIGIT0 || code == KeyCode.NUMPAD0) {
            fitToWindow();
        } else {
            return;
        }
        event.consume();
    }

    void fitToWindow() {
        Image image = viewModel.imageProperty().get();
        if (image == null) return;
        double viewportWidth = viewport.getWidth();
        double viewportHeight = viewport.getHeight();
        double imageWidth = image.getWidth();
        double imageHeight = image.getHeight();
        if (viewportWidth <= 0 || viewportHeight <= 0
                || imageWidth <= 0 || imageHeight <= 0) return;
        double fittedScale = Math.min(
                Math.min(viewportWidth / imageWidth, viewportHeight / imageHeight), 1.0);
        viewModel.scaleProperty().set(fittedScale);
        viewModel.translateXProperty().set((viewportWidth - imageWidth) / 2.0);
        viewModel.translateYProperty().set((viewportHeight - imageHeight) / 2.0);
        viewModel.fittedProperty().set(true);
    }

    private void zoomAt(double factor, double pivotX, double pivotY) {
        double oldScale = viewModel.scaleProperty().get();
        double newScale = clamp(oldScale * factor);
        if (Double.compare(newScale, oldScale) == 0) return;
        double oldTranslateX = viewModel.translateXProperty().get();
        double oldTranslateY = viewModel.translateYProperty().get();
        double ratio = newScale / oldScale;
        viewModel.translateXProperty().set(
                pivotX - (pivotX - oldTranslateX) * ratio);
        viewModel.translateYProperty().set(
                pivotY - (pivotY - oldTranslateY) * ratio);
        viewModel.scaleProperty().set(newScale);
        viewModel.fittedProperty().set(false);
    }

    static double clamp(double scale) {
        return Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale));
    }

    double scale() { return viewModel.scaleProperty().get(); }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        viewport.widthProperty().removeListener(viewportSizeListener);
        viewport.heightProperty().removeListener(viewportSizeListener);
        imageView.imageProperty().unbind();
        fileNameLabel.textProperty().unbind();
        imageGroup.scaleXProperty().unbind();
        imageGroup.scaleYProperty().unbind();
        imageGroup.translateXProperty().unbind();
        imageGroup.translateYProperty().unbind();
        viewModel.imageProperty().set(null);
    }

    boolean isClosed() { return closed.get(); }
}
