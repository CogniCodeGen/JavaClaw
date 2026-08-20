package com.javaclaw.platform.fxml;

import com.javaclaw.platform.build.ApplicationBuildIdentity;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpringFxmlLoaderTest {

    @TempDir
    Path tempDirectory;

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

    @Test
    void changedBuildTurnsFxmlFailureIntoActionableRestartMessage() throws Exception {
        Path build = tempDirectory.resolve("classes");
        Files.createDirectories(build);
        Path marker = build.resolve("Controller.class");
        Files.writeString(marker, "old");
        ApplicationBuildIdentity identity = ApplicationBuildIdentity.fromPath(build);
        Files.writeString(marker, "new");

        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(FxmlDependency.class, FxmlDependency::new);
            context.refresh();
            SpringFxmlLoader loader = new SpringFxmlLoader(
                    context.getBeanFactory(), identity);

            IOException failure = assertThrows(IOException.class, () -> loader.load(
                    getClass().getResource("/fxml/test/broken-handler-view.fxml")));

            assertInstanceOf(StaleApplicationBuildException.class, failure);
            assertEquals(StaleApplicationBuildException.USER_MESSAGE, failure.getMessage());
            assertEquals(1, failure.getSuppressed().length,
                    "原始 FXML 错误应作为诊断信息保留");
        }
    }

    @Test
    void unchangedBuildPreservesRealFxmlFailure() throws Exception {
        Path build = tempDirectory.resolve("stable-classes");
        Files.createDirectories(build);
        Files.writeString(build.resolve("Controller.class"), "stable");
        ApplicationBuildIdentity identity = ApplicationBuildIdentity.fromPath(build);

        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(FxmlDependency.class, FxmlDependency::new);
            context.refresh();
            SpringFxmlLoader loader = new SpringFxmlLoader(
                    context.getBeanFactory(), identity);

            IOException failure = assertThrows(IOException.class, () -> loader.load(
                    getClass().getResource("/fxml/test/broken-handler-view.fxml")));

            assertTrue(!(failure instanceof StaleApplicationBuildException));
            StringBuilder messages = new StringBuilder();
            for (Throwable current = failure; current != null; current = current.getCause()) {
                if (current.getMessage() != null) messages.append(current.getMessage()).append('\n');
            }
            assertTrue(messages.toString().contains("handlerThatDoesNotExist"),
                    "一致构建必须保留真正的 FXML 处理器错误，实际为: " + messages);
        }
    }
}
