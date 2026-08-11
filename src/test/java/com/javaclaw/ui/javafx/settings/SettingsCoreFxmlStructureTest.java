package com.javaclaw.ui.javafx.settings;

import com.javaclaw.ui.javafx.control.SecretFieldController;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsCoreFxmlStructureTest {

    private static final Map<String, Class<?>> RESOURCES = resources();

    @Test
    void templatesMatchTheirControllerContracts() throws Exception {
        for (var entry : RESOURCES.entrySet()) assertContract(entry.getKey(), entry.getValue());
    }

    @Test
    void controllersRespectMvcThreadAndSizeBoundaries() throws Exception {
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
            if (!field.isAnnotationPresent(FXML.class)) continue;
            String name = field.getName();
            boolean includeController = name.endsWith("Controller")
                    && ids.contains(name.substring(0, name.length() - "Controller".length()));
            assertTrue(ids.contains(name) || includeController,
                    resource + " 缺少 fx:id=" + name);
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
                    .lines().filter(line -> !line.isBlank()).count() <= 600, resource);
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
        NodeList nodes = document.getElementsByTagName("*");
        for (int index = 0; index < nodes.getLength(); index++) {
            Element element = (Element) nodes.item(index);
            if (element.hasAttribute(name)) values.add(element.getAttribute(name));
        }
        return values;
    }

    private static Map<String, Class<?>> resources() {
        Map<String, Class<?>> values = new LinkedHashMap<>();
        values.put("/fxml/control/secret-field.fxml", SecretFieldController.class);
        values.put("/fxml/settings/model-settings.fxml", ModelSettingsController.class);
        values.put("/fxml/settings/tiered-model-settings.fxml",
                TieredModelSettingsController.class);
        values.put("/fxml/settings/embedding-settings.fxml", EmbeddingSettingsController.class);
        values.put("/fxml/settings/email-settings.fxml", EmailSettingsController.class);
        values.put("/fxml/settings/notification-settings.fxml",
                NotificationSettingsController.class);
        values.put("/fxml/settings/gepa-settings.fxml", GepaSettingsController.class);
        values.put("/fxml/settings/skill-evolution-settings.fxml",
                SkillEvolutionSettingsController.class);
        values.put("/fxml/settings/general-settings.fxml", GeneralSettingsController.class);
        values.put("/fxml/settings/test-data-maintenance.fxml",
                TestDataMaintenanceController.class);
        values.put("/fxml/settings/appearance-settings.fxml",
                AppearanceSettingsController.class);
        values.put("/fxml/settings/font-settings.fxml", FontSettingsController.class);
        return Map.copyOf(values);
    }
}
