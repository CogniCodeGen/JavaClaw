package com.javaclaw.platform.fxml;

import javafx.fxml.FXML;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.Test;

import java.net.URL;

import static org.junit.jupiter.api.Assertions.assertSame;

class EmbeddedFxmlLoaderTest {

    @Test
    void loadsRootAndInjectsExistingController() {
        Controller controller = new Controller();
        URL resource = getClass().getResource("/fxml/control/toggle-switch.fxml");

        StackPane root = EmbeddedFxmlLoader.load(resource, controller, StackPane.class);

        assertSame(root, controller.track);
    }

    private static final class Controller {
        @FXML private StackPane track;

        @FXML
        @SuppressWarnings("unused")
        private void toggleRequested() {}
    }
}
