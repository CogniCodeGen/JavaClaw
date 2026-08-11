package com.javaclaw.platform.fxml;

import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpringFxmlLoaderTest {

    @Test
    void springCreatesIncludedControllersAndHandleDestroysThemInReverseOrder() throws Exception {
        try (var context = new AnnotationConfigApplicationContext()) {
            FxmlDependency dependency = new FxmlDependency();
            context.registerBean(FxmlDependency.class, () -> dependency);
            context.refresh();
            SpringFxmlLoader loader = new SpringFxmlLoader(context.getBeanFactory());

            var resource = getClass().getResource("/fxml/test/root-view.fxml");
            try (ViewHandle<VBox> view = loader.load(resource)) {
                assertEquals(2, view.controllers().size());
                assertTrue(view.controller(FxmlRootController.class).injected());
                assertTrue(view.controller(FxmlChildController.class).injected());
            }

            assertEquals(List.of("child", "root"), dependency.closeOrder());
        }
    }
}
