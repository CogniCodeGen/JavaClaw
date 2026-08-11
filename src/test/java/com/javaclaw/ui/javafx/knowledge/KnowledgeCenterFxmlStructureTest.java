package com.javaclaw.ui.javafx.knowledge;

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

class KnowledgeCenterFxmlStructureTest {

    private static final Map<String, Class<?>> CONTROLLERS = controllers();
    private static final Map<String, Class<?>> EMBEDDED = Map.of(
            "/fxml/knowledge/knowledge-document-cell.fxml", KnowledgeDocumentCell.class,
            "/fxml/knowledge/knowledge-preview-cell.fxml", KnowledgePreviewCell.class,
            "/fxml/knowledge/knowledge-search-hit-cell.fxml", KnowledgeSearchHitCell.class);

    @Test
    void templatesMatchControllerContracts() throws Exception {
        for (var entry : CONTROLLERS.entrySet()) {
            Document document = load(entry.getKey());
            assertEquals(entry.getValue().getName(), document.getDocumentElement()
                    .getAttributeNS("http://javafx.com/fxml/1", "controller"));
            assertContract(entry.getKey(), entry.getValue(), document);
        }
        for (var entry : EMBEDDED.entrySet()) {
            Document document = load(entry.getKey());
            assertFalse(document.getDocumentElement().hasAttribute("fx:controller"), entry.getKey());
            assertContract(entry.getKey(), entry.getValue(), document);
        }
    }

    @Test
    void templatesAndControllersRespectMvcQualityLimits() throws Exception {
        Set<String> resources = new HashSet<>(CONTROLLERS.keySet());
        resources.addAll(EMBEDDED.keySet());
        for (String resource : resources) {
            try (InputStream input = getClass().getResourceAsStream(resource)) {
                assertNotNull(input, resource);
                long lines = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                        .lines().filter(line -> !line.isBlank()).count();
                assertTrue(lines <= 600, resource + " 有 " + lines + " 个非空行");
            }
        }
        for (Class<?> controller : CONTROLLERS.values()) {
            String source = Files.readString(Path.of("src/main/java")
                    .resolve(controller.getName().replace('.', '/') + ".java"));
            assertFalse(source.contains("Platform.runLater"), controller.getSimpleName());
            assertFalse(source.contains("new Thread"), controller.getSimpleName());
            assertFalse(source.contains("AgentConfig.getInstance"), controller.getSimpleName());
            assertFalse(source.contains("AppDatabase"), controller.getSimpleName());
            assertFalse(source.contains("KnowledgeExpert"), controller.getSimpleName());
            assertFalse(source.matches(
                    "(?s).*new (VBox|HBox|BorderPane|GridPane|StackPane|Button|Label)\\(.*"),
                    controller.getSimpleName());
            long lines = source.lines().filter(line -> !line.isBlank()).count();
            assertTrue(lines <= 350, controller.getSimpleName() + " 有 " + lines + " 个非空行");
        }
    }

    private void assertContract(String resource, Class<?> controller, Document document) {
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
        Set<String> handlers = new HashSet<>();
        handlers.addAll(attributes(document, "onAction"));
        handlers.addAll(attributes(document, "onMouseClicked"));
        handlers.addAll(attributes(document, "onMouseReleased"));
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

    private static Map<String, Class<?>> controllers() {
        Map<String, Class<?>> result = new LinkedHashMap<>();
        result.put("/fxml/knowledge/knowledge-center.fxml", KnowledgeCenterController.class);
        result.put("/fxml/knowledge/knowledge-documents.fxml", KnowledgeDocumentsController.class);
        result.put("/fxml/knowledge/knowledge-search.fxml", KnowledgeSearchController.class);
        result.put("/fxml/knowledge/knowledge-settings.fxml", KnowledgeSettingsController.class);
        result.put("/fxml/knowledge/knowledge-text-import-dialog.fxml",
                KnowledgeTextImportDialogController.class);
        return Map.copyOf(result);
    }
}
