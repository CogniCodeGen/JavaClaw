package com.javaclaw.ui.javafx.schedule;

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
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScheduleFxmlStructureTest {

    private static final Map<String, Class<?>> CONTROLLERS = Map.of(
            "/fxml/schedule/schedule-view.fxml", ScheduleViewController.class,
            "/fxml/schedule/schedule-detail.fxml", ScheduleDetailController.class);
    private static final Set<String> ALL_RESOURCES = Set.of(
            "/fxml/schedule/schedule-view.fxml",
            "/fxml/schedule/schedule-detail.fxml",
            "/fxml/schedule/schedule-task-cell.fxml",
            "/fxml/schedule/schedule-history-cell.fxml");

    @Test
    void productionTemplatesMatchControllerContracts() throws Exception {
        for (var entry : CONTROLLERS.entrySet()) assertContract(entry.getKey(), entry.getValue());
        for (String resource : ALL_RESOURCES) {
            try (InputStream input = getClass().getResourceAsStream(resource)) {
                assertNotNull(input, resource);
                assertTrue(new String(input.readAllBytes(), StandardCharsets.UTF_8)
                        .lines().filter(line -> !line.isBlank()).count() <= 600, resource);
            }
        }
    }

    @Test
    void controllersRespectMvcThreadAndSizeBoundaries() throws Exception {
        for (Class<?> controller : CONTROLLERS.values()) {
            String source = Files.readString(Path.of("src/main/java")
                    .resolve(controller.getName().replace('.', '/') + ".java"));
            assertFalse(source.contains("Platform.runLater"), controller.getSimpleName());
            assertFalse(source.contains("new Thread"), controller.getSimpleName());
            assertFalse(source.matches("(?s).*new (VBox|HBox|BorderPane|GridPane|StackPane|Button|Label)\\(.*"),
                    controller.getSimpleName());
            assertTrue(source.lines().filter(line -> !line.isBlank()).count() <= 350,
                    controller.getSimpleName());
        }
    }

    private void assertContract(String resource, Class<?> controller) throws Exception {
        Document document = load(resource);
        assertEquals(controller.getName(), document.getDocumentElement()
                .getAttributeNS("http://javafx.com/fxml/1", "controller"));
        Set<String> ids = attributes(document, "fx:id");
        for (var field : controller.getDeclaredFields()) {
            if (!field.isAnnotationPresent(FXML.class)) continue;
            String expected = field.getName().endsWith("Controller")
                    ? field.getName().substring(0, field.getName().length() - "Controller".length())
                    : field.getName();
            assertTrue(ids.contains(expected), resource + " 缺少 fx:id=" + expected);
        }
        Set<String> methods = new HashSet<>();
        for (var method : controller.getDeclaredMethods()) {
            if (method.isAnnotationPresent(FXML.class)) methods.add("#" + method.getName());
        }
        Set<String> handlers = new HashSet<>(attributes(document, "onAction"));
        assertTrue(methods.containsAll(handlers), resource + " 存在未实现事件入口 " + handlers);
    }

    private Document load(String resource) throws Exception {
        try (InputStream input = getClass().getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            return factory.newDocumentBuilder().parse(input);
        }
    }

    private static Set<String> attributes(Document document, String name) {
        Set<String> values = new HashSet<>();
        NodeList nodes = document.getElementsByTagName("*");
        for (int index = 0; index < nodes.getLength(); index++) {
            Element element = (Element) nodes.item(index);
            if (element.hasAttribute(name)) values.add(element.getAttribute(name));
        }
        return values;
    }
}
