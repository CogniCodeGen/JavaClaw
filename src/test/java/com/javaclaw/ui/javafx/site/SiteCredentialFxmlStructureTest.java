package com.javaclaw.ui.javafx.site;

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

class SiteCredentialFxmlStructureTest {

    private static final Map<String, Class<?>> RESOURCES = Map.of(
            "/fxml/site/site-credential-panel.fxml", SiteCredentialController.class,
            "/fxml/site/site-credential-card.fxml", SiteCredentialCardController.class,
            "/fxml/site/site-credential-editor.fxml", SiteCredentialEditorController.class);

    @Test
    void everyTemplateMatchesControllerFieldsAndActions() throws Exception {
        for (var entry : RESOURCES.entrySet()) assertContract(entry.getKey(), entry.getValue());
    }

    @Test
    void controllersContainNoLayoutConstructionOrDirectThreadDispatch() throws Exception {
        for (Class<?> controller : RESOURCES.values()) {
            String source = Files.readString(Path.of("src/main/java")
                    .resolve(controller.getName().replace('.', '/') + ".java"));
            assertFalse(source.contains("Platform.runLater"), controller.getSimpleName());
            assertFalse(source.contains("new Thread"), controller.getSimpleName());
            assertFalse(source.matches(
                    "(?s).*new (VBox|HBox|BorderPane|GridPane|StackPane|Button|Label)\\(.*"),
                    controller.getSimpleName());
            assertTrue(source.lines().filter(line -> !line.isBlank()).count() <= 350,
                    controller.getSimpleName());
        }
    }

    private void assertContract(String resource, Class<?> controller) throws Exception {
        Document document = load(resource);
        assertEquals(controller.getName(), document.getDocumentElement()
                .getAttributeNS("http://javafx.com/fxml/1", "controller"), resource);
        Set<String> ids = attributes(document, "fx:id");
        for (var field : controller.getDeclaredFields()) {
            if (field.isAnnotationPresent(FXML.class)) {
                assertTrue(ids.contains(field.getName()), resource + " 缺少 fx:id=" + field.getName());
            }
        }
        Set<String> methods = new HashSet<>();
        for (var method : controller.getDeclaredMethods()) {
            if (method.isAnnotationPresent(FXML.class)) methods.add("#" + method.getName());
        }
        Set<String> handlers = new HashSet<>(attributes(document, "onAction"));
        handlers.addAll(attributes(document, "onMouseClicked"));
        assertTrue(methods.containsAll(handlers), resource + " 存在未实现事件入口 " + handlers);
        try (InputStream input = getClass().getResourceAsStream(resource)) {
            assertNotNull(input);
            assertTrue(new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .lines().filter(line -> !line.isBlank()).count() <= 600);
        }
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
        NodeList all = document.getElementsByTagName("*");
        for (int index = 0; index < all.getLength(); index++) {
            Element element = (Element) all.item(index);
            if (element.hasAttribute(name)) values.add(element.getAttribute(name));
        }
        return values;
    }
}
