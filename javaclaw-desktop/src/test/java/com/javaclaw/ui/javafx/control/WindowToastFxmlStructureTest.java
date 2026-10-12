package com.javaclaw.ui.javafx.control;

import javafx.fxml.FXML;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowToastFxmlStructureTest {

    private static final String RESOURCE = "/fxml/control/window-toast.fxml";

    @Test
    void templateMatchesControllerContract() throws Exception {
        Document document = load();
        assertEquals(WindowToastController.class.getName(), document.getDocumentElement()
                .getAttributeNS("http://javafx.com/fxml/1", "controller"));
        Set<String> ids = attributes(document, "fx:id");
        for (var field : WindowToastController.class.getDeclaredFields()) {
            if (field.isAnnotationPresent(FXML.class)) {
                assertTrue(ids.contains(field.getName()), "缺少 fx:id=" + field.getName());
            }
        }
        assertTrue(attributes(document, "onAction").isEmpty());
        assertTrue(attributes(document, "onMouseClicked").isEmpty());
    }

    @Test
    void controllerKeepsLayoutAndThreadDispatchOutOfItsImplementation() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/javaclaw/ui/javafx/control/WindowToastController.java"));
        assertFalse(source.contains("Platform.runLater"));
        assertFalse(source.contains("new Thread"));
        assertFalse(source.matches(
                "(?s).*new (VBox|HBox|BorderPane|GridPane|StackPane|Button|Label)\\(.*"));
        assertTrue(source.lines().filter(line -> !line.isBlank()).count() <= 350);
        try (InputStream input = getClass().getResourceAsStream(RESOURCE)) {
            assertNotNull(input);
            long lines = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .lines().filter(line -> !line.isBlank()).count();
            assertTrue(lines <= 600);
        }
    }

    private Document load() throws Exception {
        try (InputStream input = getClass().getResourceAsStream(RESOURCE)) {
            assertNotNull(input);
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            return factory.newDocumentBuilder().parse(input);
        }
    }

    private static Set<String> attributes(Document document, String name) {
        Set<String> values = new HashSet<>();
        NodeList all = document.getElementsByTagName("*");
        for (int index = 0; index < all.getLength(); index++) {
            Element element = (Element) all.item(index);
            if (element.hasAttribute(name)) values.add(element.getAttribute(name));
        }
        return values;
    }
}
