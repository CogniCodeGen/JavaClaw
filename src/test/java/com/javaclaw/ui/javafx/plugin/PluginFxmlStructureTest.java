package com.javaclaw.ui.javafx.plugin;

import javafx.fxml.FXML;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginFxmlStructureTest {

    private static final Map<String, Class<?>> RESOURCES = Map.of(
            "/fxml/plugin/plugin-center.fxml", PluginCenterController.class,
            "/fxml/plugin/plugin-detail.fxml", PluginDetailController.class,
            "/fxml/plugin/plugin-card.fxml", PluginCardController.class,
            "/fxml/plugin/plugin-permission-row.fxml", PluginPermissionRowController.class,
            "/fxml/plugin/plugin-named-item.fxml", PluginNamedItemController.class,
            "/fxml/plugin/plugin-config-field.fxml", PluginConfigFieldController.class);

    @Test
    void everyTemplateMatchesItsControllerContract() throws Exception {
        for (Map.Entry<String, Class<?>> entry : RESOURCES.entrySet()) {
            assertContract(entry.getKey(), entry.getValue());
        }
    }

    @Test
    void productionTemplatesStayBelowSizeGate() throws Exception {
        for (String resource : RESOURCES.keySet()) {
            try (InputStream input = getClass().getResourceAsStream(resource)) {
                assertNotNull(input, resource);
                long nonEmpty = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                        .lines().filter(line -> !line.isBlank()).count();
                assertTrue(nonEmpty <= 600, resource + " 超过 600 个非空行");
            }
        }
    }

    private void assertContract(String resource, Class<?> controller) throws Exception {
        Document document = load(resource);
        assertEquals(controller.getName(), document.getDocumentElement()
                .getAttributeNS("http://javafx.com/fxml/1", "controller"), resource);
        Set<String> ids = attributes(document, "fx:id");
        for (Field field : controller.getDeclaredFields()) {
            if (!field.isAnnotationPresent(FXML.class)) continue;
            String expected = field.getName().endsWith("Controller")
                    ? field.getName().substring(0, field.getName().length() - "Controller".length())
                    : field.getName();
            assertTrue(ids.contains(expected), resource + " 缺少 fx:id=" + expected);
        }
        Set<String> methods = new HashSet<>();
        for (Method method : controller.getDeclaredMethods()) {
            if (method.isAnnotationPresent(FXML.class)) methods.add("#" + method.getName());
        }
        Set<String> handlers = new HashSet<>(attributes(document, "onAction"));
        handlers.addAll(attributes(document, "onMouseClicked"));
        assertTrue(methods.containsAll(handlers), resource + " 存在未实现的事件入口 " + handlers);
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
