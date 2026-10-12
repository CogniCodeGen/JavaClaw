package com.javaclaw.ui.javafx.image;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.scene.image.Image;

/** 图片查看器的缩放、平移和内容状态，不持有窗口或服务。 */
public final class ImageViewerViewModel {

    private final ObjectProperty<Image> image = new SimpleObjectProperty<>();
    private final StringProperty fileName = new SimpleStringProperty("");
    private final DoubleProperty scale = new SimpleDoubleProperty(1.0);
    private final DoubleProperty translateX = new SimpleDoubleProperty();
    private final DoubleProperty translateY = new SimpleDoubleProperty();
    private final BooleanProperty fitted = new SimpleBooleanProperty(false);

    public ObjectProperty<Image> imageProperty() { return image; }

    public StringProperty fileNameProperty() { return fileName; }

    public DoubleProperty scaleProperty() { return scale; }

    public DoubleProperty translateXProperty() { return translateX; }

    public DoubleProperty translateYProperty() { return translateY; }

    public BooleanProperty fittedProperty() { return fitted; }
}
