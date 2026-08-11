package com.javaclaw.architecture;

import javafx.fxml.FXML;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FxmlIntegrityTest {

    private static final String FXML_NAMESPACE = "http://javafx.com/fxml/1";
    private static final Path FXML_ROOT =
            Path.of(System.getProperty("user.dir"), "src/main/resources/fxml");
    private static final Path JAVA_ROOT =
            Path.of(System.getProperty("user.dir"), "src/main/java");
    private static final Pattern PACKAGE = Pattern.compile("(?m)^package\\s+([\\w.]+);");

    @Test
    void everyProductionFxmlHasResolvableControllersHandlersIncludesAndUniqueIds() throws Exception {
        List<String> violations = new ArrayList<>();
        try (var paths = Files.walk(FXML_ROOT)) {
            for (Path file : paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".fxml"))
                    .sorted()
                    .toList()) {
                inspect(file, violations);
            }
        }
        assertTrue(
                violations.isEmpty(),
                () -> "FXML integrity violations:\n" + String.join("\n", violations));
    }

    private static void inspect(Path file, List<String> violations) {
        String label = FXML_ROOT.relativize(file).toString().replace('\\', '/');
        try {
            Document document = parser().newDocumentBuilder().parse(file.toFile());
            Element root = document.getDocumentElement();
            String controllerName = root.getAttributeNS(FXML_NAMESPACE, "controller");
            Class<?> controller = controllerName.isBlank()
                    ? embeddedController(label)
                    : loadWithoutInitialization(controllerName);
            Set<String> handlerNames = new HashSet<>();
            Set<String> ids = new HashSet<>();
            inspectNodes(file, document.getElementsByTagName("*"), ids, handlerNames, violations);
            if (controller == null && !handlerNames.isEmpty()) {
                violations.add(label + " declares event handlers without fx:controller");
            } else if (controller != null) {
                Set<String> methods = controllerMethods(controller);
                handlerNames.stream()
                        .filter(handler -> !methods.contains(handler))
                        .forEach(handler ->
                                violations.add(label + " references missing handler #" + handler));
                controllerFields(controller).stream()
                        .filter(field -> !ids.contains(field))
                        .forEach(field ->
                                violations.add(label + " cannot inject @FXML field " + field));
            }
        } catch (Exception failure) {
            violations.add(label + " cannot be parsed: " + failure.getMessage());
        }
    }

    private static void inspectNodes(
            Path file,
            NodeList nodes,
            Set<String> ids,
            Set<String> handlers,
            List<String> violations) {
        String label = FXML_ROOT.relativize(file).toString().replace('\\', '/');
        for (int index = 0; index < nodes.getLength(); index++) {
            Node node = nodes.item(index);
            String id = attribute(node, FXML_NAMESPACE, "id");
            if (!id.isBlank() && !ids.add(id)) {
                violations.add(label + " repeats fx:id " + id);
            }
            if (!id.isBlank()
                    && FXML_NAMESPACE.equals(node.getNamespaceURI())
                    && "include".equals(node.getLocalName())) {
                ids.add(id + "Controller");
            }
            collectHandlers(node.getAttributes(), handlers);
            if (FXML_NAMESPACE.equals(node.getNamespaceURI()) && "include".equals(node.getLocalName())) {
                String source = attribute(node, null, "source");
                if (!source.isBlank() && !Files.isRegularFile(file.getParent().resolve(source).normalize())) {
                    violations.add(label + " includes missing resource " + source);
                }
            }
        }
    }

    private static void collectHandlers(NamedNodeMap attributes, Set<String> handlers) {
        if (attributes == null) return;
        for (int index = 0; index < attributes.getLength(); index++) {
            Node attribute = attributes.item(index);
            String name = attribute.getLocalName() == null ? attribute.getNodeName() : attribute.getLocalName();
            String value = attribute.getNodeValue();
            if (name.startsWith("on")
                    && value != null
                    && value.startsWith("#")
                    && value.length() > 1) {
                handlers.add(value.substring(1));
            }
        }
    }

    private static Class<?> embeddedController(String label) throws Exception {
        String resource = "\"/fxml/" + label + "\"";
        List<Path> matches;
        try (var paths = Files.walk(JAVA_ROOT)) {
            matches = paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> containsEmbeddedResource(path, resource))
                    .toList();
        }
        if (matches.isEmpty()) return null;
        if (matches.size() > 1) {
            throw new IllegalStateException("多个 EmbeddedFxmlLoader 控制器引用同一资源: " + matches);
        }
        Path sourceFile = matches.getFirst();
        String source = Files.readString(sourceFile);
        Matcher packageName = PACKAGE.matcher(source);
        if (!packageName.find()) throw new IllegalStateException("控制器源码缺少 package: " + sourceFile);
        String simpleName = sourceFile.getFileName().toString().replaceFirst("\\.java$", "");
        return loadWithoutInitialization(packageName.group(1) + "." + simpleName);
    }

    private static Class<?> loadWithoutInitialization(String name) throws ClassNotFoundException {
        return Class.forName(name, false, Thread.currentThread().getContextClassLoader());
    }

    private static boolean containsEmbeddedResource(Path sourceFile, String resource) {
        try {
            String source = Files.readString(sourceFile);
            return source.contains("EmbeddedFxmlLoader.load") && source.contains(resource);
        } catch (Exception unreadable) {
            throw new IllegalStateException("无法读取 FXML 控制器源码: " + sourceFile, unreadable);
        }
    }

    private static Set<String> controllerMethods(Class<?> controller) {
        Set<String> names = new HashSet<>();
        for (Class<?> type = controller; type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) names.add(method.getName());
        }
        return names;
    }

    private static Set<String> controllerFields(Class<?> controller) {
        Set<String> names = new HashSet<>();
        for (Class<?> type = controller; type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (field.isAnnotationPresent(FXML.class)) names.add(field.getName());
            }
        }
        return names;
    }

    private static String attribute(Node node, String namespace, String localName) {
        Node attribute = namespace == null
                ? node.getAttributes().getNamedItem(localName)
                : node.getAttributes().getNamedItemNS(namespace, localName);
        return attribute == null ? "" : attribute.getNodeValue();
    }

    private static DocumentBuilderFactory parser() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory;
    }
}
