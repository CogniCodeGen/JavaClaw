package com.javaclaw.platform.fxml;

import javafx.fxml.FXML;
import javafx.scene.layout.Pane;

public final class FxmlChildController implements AutoCloseable {
    private final FxmlDependency dependency;

    @FXML
    private Pane content;

    public FxmlChildController(FxmlDependency dependency) {
        this.dependency = dependency;
    }

    public boolean injected() {
        return content != null;
    }

    @Override
    public void close() {
        dependency.closed("child");
    }
}
