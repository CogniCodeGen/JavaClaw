package com.javaclaw.ui.javafx.settings;

import javafx.scene.Node;
import javafx.scene.layout.VBox;

import java.util.Objects;

/** 宿主模型视图接入统一插件生命周期操作的通用契约。 */
public record InferenceRuntimeControls(Node settings, Runnable start, Runnable stop,
                                       Runnable restart, Runnable unquarantine) {
    public InferenceRuntimeControls {
        Objects.requireNonNull(settings); Objects.requireNonNull(start); Objects.requireNonNull(stop);
        Objects.requireNonNull(restart); Objects.requireNonNull(unquarantine);
    }
    public static InferenceRuntimeControls none() {
        return new InferenceRuntimeControls(new VBox(), () -> { }, () -> { }, () -> { }, () -> { });
    }
}
