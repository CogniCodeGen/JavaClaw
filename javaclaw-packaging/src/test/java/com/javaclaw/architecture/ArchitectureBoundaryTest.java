package com.javaclaw.architecture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchitectureBoundaryTest {
    private static final List<String> MODULES = List.of(
            "javaclaw-api",
            "javaclaw-protocol",
            "javaclaw-extension-spi",
            "javaclaw-agent-runtime",
            "javaclaw-model-adapters",
            "javaclaw-builtin-contracts",
            "javaclaw-builtin-extensions",
            "javaclaw-app-server",
            "javaclaw-native-hosts",
            "javaclaw-browser-service",
            "javaclaw-knowledge-worker",
            "javaclaw-client",
            "javaclaw-desktop",
            "javaclaw-packaging");
    private static final Set<String> LIBRARY_MODULES = Set.of(
            "javaclaw-api",
            "javaclaw-protocol",
            "javaclaw-extension-spi",
            "javaclaw-agent-runtime",
            "javaclaw-model-adapters",
            "javaclaw-builtin-contracts",
            "javaclaw-builtin-extensions",
            "javaclaw-native-hosts",
            "javaclaw-browser-service",
            "javaclaw-knowledge-worker",
            "javaclaw-client",
            "javaclaw-desktop");
    private static final Set<String> TEXT_SUFFIXES =
            Set.of(".java", ".xml", ".json", ".sql", ".css", ".fxml", ".md", ".sh", ".cmd", ".ps1", ".yml", ".yaml");
    private static final Set<String> MAINTAINED_PRODUCTION_SUFFIXES =
            Set.of(".java", ".sql", ".css", ".fxml", ".sh", ".cmd", ".ps1");
    private static final Pattern REMOVED_IDENTIFIERS = Pattern.compile(
            "(?i)(data-v4|config-v4|protocol[-_ ]?v?1|javaclaw-v4|javaclaw\\s+v?4(?:\\.0)?\\b|@Deprecated)");
    private static final Path ROOT = locateRoot();

    @Test
    void reactor只包含批准的十四个模块() {
        assertEquals(MODULES, childTexts(readDocument(ROOT.resolve("pom.xml")), "modules", "module"));
    }

    @Test
    void 模块依赖图无环() {
        Map<String, Set<String>> graph = reactorDependencies();
        Set<String> remaining = new HashSet<>(MODULES);
        ArrayDeque<String> leaves = new ArrayDeque<>();

        while (!remaining.isEmpty()) {
            graph.forEach((module, dependencies) -> {
                if (remaining.contains(module) && dependencies.stream().noneMatch(remaining::contains)) {
                    leaves.add(module);
                }
            });
            assertFalse(leaves.isEmpty(), () -> "检测到模块依赖环：" + remaining);
            while (!leaves.isEmpty()) {
                remaining.remove(leaves.remove());
            }
        }
    }

    @Test
    void Harness生产依赖只有CoreApi和日志门面() {
        Set<Coordinate> expected =
                Set.of(new Coordinate("com.javaclaw", "javaclaw-api"), new Coordinate("org.slf4j", "slf4j-api"));
        assertEquals(expected, directDependencies("javaclaw-agent-runtime"));
    }

    @Test
    void AppServer不直接依赖SpringAi() {
        Set<Coordinate> dependencies = directDependencies("javaclaw-app-server");
        assertTrue(
                dependencies.stream().noneMatch(value -> value.groupId().startsWith("org.springframework.ai")),
                () -> "Spring AI 只能由 model-adapters 引入：" + dependencies);
    }

    @Test
    void 公共库模块都有JPMS描述符() {
        List<String> missing = LIBRARY_MODULES.stream()
                .filter(module -> Files.notExists(ROOT.resolve(module).resolve("src/main/java/module-info.java")))
                .sorted()
                .toList();
        assertTrue(missing.isEmpty(), () -> "缺少 module-info.java：" + missing);
    }

    @Test
    void Harness不包含业务领域包() throws IOException {
        Path sourceRoot = ROOT.resolve("javaclaw-agent-runtime/src/main/java");
        Set<String> forbiddenSegments = Set.of("plan", "workflow", "schedule", "memory", "knowledge", "sdd", "site");
        List<Path> violations;
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            violations = files.filter(Files::isRegularFile)
                    .filter(path -> forbiddenSegments.stream().anyMatch(segment -> pathContains(path, segment)))
                    .map(ROOT::relativize)
                    .toList();
        }
        assertTrue(violations.isEmpty(), () -> "Harness 混入业务领域源码：" + violations);
    }

    @Test
    void 生产源码和现行文档不含已移除标识() throws IOException {
        List<Path> scanRoots = new ArrayList<>();
        scanRoots.add(ROOT.resolve("README.md"));
        scanRoots.add(ROOT.resolve("docs"));
        scanRoots.add(ROOT.resolve(".github"));
        MODULES.forEach(module -> scanRoots.add(ROOT.resolve(module).resolve("src/main")));

        List<String> violations = new ArrayList<>();
        for (Path scanRoot : scanRoots) {
            scanPath(scanRoot, violations);
        }
        assertTrue(violations.isEmpty(), () -> "发现已移除的运行标识：" + violations);
    }

    @Test
    void 手写生产源码和资源不超过六百行() throws IOException {
        List<String> violations = new ArrayList<>();
        for (String module : MODULES) {
            Path sourceRoot = ROOT.resolve(module).resolve("src/main");
            if (Files.notExists(sourceRoot)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(sourceRoot)) {
                for (Path file : files.filter(Files::isRegularFile)
                        .filter(ArchitectureBoundaryTest::isMaintainedProductionFile)
                        .toList()) {
                    try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
                        long count = lines.count();
                        if (count > 600) {
                            violations.add(ROOT.relativize(file) + "=" + count);
                        }
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(), () -> "手写生产文件超过 600 行：" + violations);
    }

    private static Map<String, Set<String>> reactorDependencies() {
        Map<String, Set<String>> graph = new LinkedHashMap<>();
        for (String module : MODULES) {
            Set<String> dependencies = new HashSet<>();
            for (Coordinate coordinate : directDependencies(module)) {
                if (coordinate.groupId().equals("com.javaclaw") && MODULES.contains(coordinate.artifactId())) {
                    dependencies.add(coordinate.artifactId());
                }
            }
            graph.put(module, Set.copyOf(dependencies));
        }
        return graph;
    }

    private static Set<Coordinate> directDependencies(String module) {
        Document document = readDocument(ROOT.resolve(module).resolve("pom.xml"));
        Element project = document.getDocumentElement();
        Element dependencies = firstDirectChild(project, "dependencies");
        Set<Coordinate> result = new HashSet<>();
        if (dependencies == null) {
            return result;
        }
        for (Element dependency : directChildren(dependencies, "dependency")) {
            if ("test".equals(directText(dependency, "scope"))) {
                continue;
            }
            result.add(new Coordinate(directText(dependency, "groupId"), directText(dependency, "artifactId")));
        }
        return Set.copyOf(result);
    }

    private static void scanPath(Path path, List<String> violations) throws IOException {
        if (Files.notExists(path)) {
            return;
        }
        if (Files.isRegularFile(path)) {
            scanFile(path, violations);
            return;
        }
        try (Stream<Path> files = Files.walk(path)) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(ArchitectureBoundaryTest::isTextFile)
                    .toList()) {
                scanFile(file, violations);
            }
        }
    }

    private static void scanFile(Path file, List<String> violations) throws IOException {
        String relative = ROOT.relativize(file).toString();
        String contents = Files.readString(file, StandardCharsets.UTF_8);
        if (REMOVED_IDENTIFIERS.matcher(relative).find()
                || REMOVED_IDENTIFIERS.matcher(contents).find()) {
            violations.add(relative);
        }
    }

    private static boolean isTextFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return TEXT_SUFFIXES.stream().anyMatch(name::endsWith);
    }

    private static boolean isMaintainedProductionFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return MAINTAINED_PRODUCTION_SUFFIXES.stream().anyMatch(name::endsWith);
    }

    private static boolean pathContains(Path path, String segment) {
        for (Path element : path) {
            if (element.toString().equals(segment)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> childTexts(Document document, String containerName, String childName) {
        Element container = firstDirectChild(document.getDocumentElement(), containerName);
        assert container != null;
        return directChildren(container, childName).stream()
                .map(Node::getTextContent)
                .map(String::trim)
                .toList();
    }

    private static Element firstDirectChild(Element parent, String name) {
        return directChildren(parent, name).stream().findFirst().orElse(null);
    }

    private static List<Element> directChildren(Element parent, String name) {
        List<Element> result = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (child instanceof Element element && element.getTagName().equals(name)) {
                result.add(element);
            }
        }
        return result;
    }

    private static String directText(Element parent, String name) {
        Element child = firstDirectChild(parent, name);
        return child == null ? "" : child.getTextContent().trim();
    }

    private static Document readDocument(Path path) {
        try {
            DocumentBuilderFactory factory = secureDocumentFactory();
            return factory.newDocumentBuilder().parse(path.toFile());
        } catch (IOException | ParserConfigurationException | SAXException exception) {
            throw new IllegalStateException("无法读取 Maven 模型：" + path, exception);
        }
    }

    private static DocumentBuilderFactory secureDocumentFactory() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory;
    }

    private static Path locateRoot() {
        String configured = System.getProperty("maven.multiModuleProjectDirectory", "");
        if (!configured.isBlank()) {
            Path candidate = Path.of(configured).toAbsolutePath().normalize();
            if (Files.isDirectory(candidate.resolve("javaclaw-api"))) {
                return candidate;
            }
        }
        Path candidate = Path.of("").toAbsolutePath().normalize();
        while (candidate != null && Files.notExists(candidate.resolve("javaclaw-api"))) {
            candidate = candidate.getParent();
        }
        if (candidate == null) {
            throw new IllegalStateException("无法定位 JavaClaw Reactor 根目录");
        }
        return candidate;
    }

    private record Coordinate(String groupId, String artifactId) {}
}
