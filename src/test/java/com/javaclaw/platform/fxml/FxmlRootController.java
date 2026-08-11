package com.javaclaw.platform.fxml;

import javafx.fxml.FXML;
import javafx.scene.Node;

public final class FxmlRootController implements AutoCloseable {
    private final FxmlDependency dependency;

    @FXML
    private Node child;

    public FxmlRootController(FxmlDependency dependency) {
        this.dependency = dependency;
    }

    public boolean injected() {
        return child != null;
    }

    @Override
    public void close() {
        dependency.closed("root");
    }
}
