package com.javaclaw.ui.javafx.interaction;

import org.junit.jupiter.api.Test;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InteractionDialogFxmlStructureTest {

    private static final Path FXML = Path.of("src/main/resources/fxml/interaction");

    @Test
    void allInteractionDialogResourcesAreWellFormedAndDeclareControllers() throws Exception {
        Map<String, String> resources = Map.of(
                "confirm-dialog.fxml", ConfirmDialogController.class.getName(),
                "choice-dialog.fxml", ChoiceDialogController.class.getName(),
                "secret-dialog.fxml", SecretDialogController.class.getName());

        for (var entry : resources.entrySet()) {
            Path resource = FXML.resolve(entry.getKey());
            assertTrue(Files.isRegularFile(resource), entry.getKey());
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(resource.toFile());
            assertTrue(Files.readString(resource).contains(
                    "fx:controller=\"" + entry.getValue() + "\""), entry.getKey());
        }
    }

    @Test
    void controllersAndPortDoNotConstructLayoutOrDispatchDirectly() throws Exception {
        for (Class<?> controller : new Class<?>[]{
                ConfirmDialogController.class,
                ChoiceDialogController.class,
                SecretDialogController.class}) {
            Path source = Path.of("src/main/java",
                    controller.getName().replace('.', '/') + ".java");
            String text = Files.readString(source);
            assertFalse(text.contains("new VBox"), controller.getSimpleName());
            assertFalse(text.contains("new HBox"), controller.getSimpleName());
            assertFalse(text.contains("new Dialog"), controller.getSimpleName());
            assertFalse(text.contains("Platform.runLater"), controller.getSimpleName());
            assertFalse(text.contains("new Thread"), controller.getSimpleName());
        }

        String port = Files.readString(Path.of(
                "src/main/java/com/javaclaw/ui/javafx/JfxUserInteractionPort.java"));
        assertFalse(port.contains("new Dialog"));
        assertFalse(port.contains("new Alert"));
        assertFalse(port.contains("new ChoiceDialog"));
    }
}
