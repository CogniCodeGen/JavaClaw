package com.javaclaw.ui.javafx.diagnostics;

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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosticsFxmlStructureTest {

    @Test
    void definesAllInjectedFieldsAndEventHandlers() throws Exception {
        Document document = load();
        Element root = document.getDocumentElement();
        assertEquals(DiagnosticsController.class.getName(),
                root.getAttributeNS("http://javafx.com/fxml/1", "controller"));

        Set<String> ids = attributes(document, "fx:id");
        for (Field field : DiagnosticsController.class.getDeclaredFields()) {
            if (field.isAnnotationPresent(FXML.class)) {
                assertTrue(ids.contains(field.getName()), "缺少 fx:id=" + field.getName());
            }
        }

        Set<String> handlers = attributes(document, "onAction");
        Set<String> methods = new HashSet<>();
        for (Method method : DiagnosticsController.class.getDeclaredMethods()) {
            if (method.isAnnotationPresent(FXML.class)) methods.add("#" + method.getName());
        }
        assertTrue(methods.containsAll(handlers), "FXML 存在 Controller 未实现的事件入口");
        assertTrue(handlers.contains("#queryRequested"));
        assertTrue(handlers.contains("#exportRequested"));
    }

    private static Document load() throws Exception {
        try (InputStream input = DiagnosticsFxmlStructureTest.class.getResourceAsStream(
                "/fxml/diagnostics/diagnostics-view.fxml")) {
            assertNotNull(input);
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            return factory.newDocumentBuilder().parse(input);
        }
    }

    private static Set<String> attributes(Document document, String name) {
        Set<String> values = new HashSet<>();
        NodeList all = document.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Element element = (Element) all.item(i);
            if (element.hasAttribute(name)) values.add(element.getAttribute(name));
        }
        return values;
    }
}
