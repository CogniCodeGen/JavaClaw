package com.javaclaw.ui.javafx.image;

import javafx.fxml.FXML;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageViewerFxmlStructureTest {

    private static final String RESOURCE = "/fxml/image/image-viewer.fxml";

    @Test
    void templateMatchesControllerFieldsAndEventEntrypoints() throws Exception {
        Document document = load();
        assertEquals(ImageViewerController.class.getName(), document.getDocumentElement()
                .getAttributeNS("http://javafx.com/fxml/1", "controller"));
        Set<String> ids = attributes(document, "fx:id");
        for (Field field : ImageViewerController.class.getDeclaredFields()) {
            if (field.isAnnotationPresent(FXML.class)) {
                assertTrue(ids.contains(field.getName()), "缺少 fx:id=" + field.getName());
            }
        }
        Set<String> methods = new HashSet<>();
        for (Method method : ImageViewerController.class.getDeclaredMethods()) {
            if (method.isAnnotationPresent(FXML.class)) methods.add("#" + method.getName());
        }
        Set<String> handlers = new HashSet<>();
        for (String attribute : Set.of(
                "onAction", "onKeyPressed", "onScroll", "onZoom",
                "onMousePressed", "onMouseDragged", "onMouseReleased")) {
            handlers.addAll(attributes(document, attribute));
        }
        assertTrue(methods.containsAll(handlers), "存在未实现事件入口 " + handlers);
    }

    @Test
    void viewerStaysWithinSourceAndFxmlGates() throws Exception {
        try (InputStream input = getClass().getResourceAsStream(RESOURCE)) {
            assertNotNull(input);
            long lines = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .lines().filter(line -> !line.isBlank()).count();
            assertTrue(lines <= 600);
        }
        String source = Files.readString(Path.of(
                "src/main/java/com/javaclaw/ui/javafx/image/ImageViewerController.java"));
        assertFalse(source.contains("Platform.runLater"));
        assertFalse(source.contains("new Thread"));
        assertFalse(source.matches(
                "(?s).*new (VBox|HBox|BorderPane|GridPane|StackPane|Button|Label)\\(.*"));
        assertTrue(source.lines().filter(line -> !line.isBlank()).count() <= 350);
    }

    @Test
    void scaleClampHonorsViewerLimits() {
        assertEquals(ImageViewerController.MIN_SCALE, ImageViewerController.clamp(0.001));
        assertEquals(2.0, ImageViewerController.clamp(2.0));
        assertEquals(ImageViewerController.MAX_SCALE, ImageViewerController.clamp(100.0));
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
