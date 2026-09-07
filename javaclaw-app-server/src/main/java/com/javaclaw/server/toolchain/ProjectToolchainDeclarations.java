package com.javaclaw.server.toolchain;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.xml.sax.InputSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.tomlj.Toml;

import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.ToolchainKind;

/** 只解析固定声明字段；XML 禁止实体，Gradle 只识别静态字面量，声明中的 URI 从不作为下载源。 */
final class ProjectToolchainDeclarations {
    private final Map<ToolchainKind, List<String>> constraints = new EnumMap<>(ToolchainKind.class);
    private final Map<ToolchainKind, List<String>> errors = new EnumMap<>(ToolchainKind.class);

    static List<String> paths() {
        return List.of(
                "pom.xml",
                ".java-version",
                ".tool-versions",
                "build.gradle",
                "build.gradle.kts",
                "gradle/wrapper/gradle-wrapper.properties",
                "package.json",
                ".node-version",
                ".nvmrc",
                ".python-version",
                "pyproject.toml");
    }

    static ProjectToolchainDeclarations parse(Map<String, String> files) {
        var declarations = new ProjectToolchainDeclarations();
        files.forEach(declarations::file);
        return declarations;
    }

    Map<ToolchainKind, List<String>> constraints() {
        return Map.copyOf(constraints);
    }

    Map<ToolchainKind, List<String>> errors() {
        return Map.copyOf(errors);
    }

    private void file(String path, String content) {
        try {
            switch (path) {
                case "pom.xml" -> pom(content);
                case ".java-version" -> add(ToolchainKind.JDK, content.strip());
                case ".tool-versions" -> toolVersions(content);
                case "build.gradle", "build.gradle.kts" -> gradle(content);
                case "gradle/wrapper/gradle-wrapper.properties" -> wrapper(content);
                case "package.json" -> node(content);
                case ".node-version", ".nvmrc" ->
                    add(ToolchainKind.NODE, content.strip().replaceFirst("^v", ""));
                case ".python-version" -> add(ToolchainKind.PYTHON, content.strip());
                case "pyproject.toml" -> python(content);
                default -> throw new IllegalArgumentException("非固定声明文件");
            }
        } catch (Exception failure) {
            for (ToolchainKind kind : kinds(path)) {
                errors.computeIfAbsent(kind, ignored -> new ArrayList<>()).add(path + ": 声明无法静态解析");
            }
        }
    }

    private void pom(String content) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(content)));
        for (String name :
                List.of("maven.compiler.release", "maven.compiler.source", "maven.compiler.target", "java.version")) {
            var nodes = document.getElementsByTagName(name);
            for (int index = 0; index < nodes.getLength(); index++) {
                javaMinimum(document, nodes.item(index).getTextContent());
            }
        }
        compilerPlugin(document);
    }

    private void compilerPlugin(org.w3c.dom.Document document) {
        var plugins = document.getElementsByTagName("plugin");
        for (int index = 0; index < plugins.getLength(); index++) {
            var plugin = (org.w3c.dom.Element) plugins.item(index);
            boolean compiler = children(plugin, "artifactId").stream()
                    .anyMatch(value -> value.getTextContent().strip().equals("maven-compiler-plugin"));
            if (compiler) {
                var configurations = plugin.getElementsByTagName("configuration");
                for (int position = 0; position < configurations.getLength(); position++) {
                    compilerConfiguration(document, (org.w3c.dom.Element) configurations.item(position));
                }
            }
        }
    }

    private void compilerConfiguration(org.w3c.dom.Document document, org.w3c.dom.Element configuration) {
        for (String name : List.of("release", "source", "target")) {
            children(configuration, name).forEach(value -> javaMinimum(document, value.getTextContent()));
        }
    }

    private void javaMinimum(org.w3c.dom.Document document, String declaration) {
        String value = xmlValue(document, declaration.strip());
        if (!value.matches("(?:1\\.)?[0-9]+")) {
            throw new IllegalArgumentException("非静态 Java 版本");
        }
        add(ToolchainKind.JDK, ">=" + value.replaceFirst("^1\\.", ""));
    }

    private static List<org.w3c.dom.Element> children(org.w3c.dom.Element parent, String name) {
        List<org.w3c.dom.Element> result = new ArrayList<>();
        var nodes = parent.getChildNodes();
        for (int index = 0; index < nodes.getLength(); index++) {
            if (nodes.item(index) instanceof org.w3c.dom.Element element
                    && element.getTagName().equals(name)) {
                result.add(element);
            }
        }
        return result;
    }

    private static String xmlValue(org.w3c.dom.Document document, String value) {
        java.util.Set<String> visited = new java.util.HashSet<>();
        while (value.startsWith("${") && value.endsWith("}")) {
            String property = value.substring(2, value.length() - 1);
            if (!property.matches("[A-Za-z][A-Za-z0-9_.-]{0,80}") || !visited.add(property) || visited.size() > 8) {
                throw new IllegalArgumentException("Maven 属性引用不是有界静态值");
            }
            var nodes = document.getElementsByTagName(property);
            if (nodes.getLength() != 1) {
                throw new IllegalArgumentException("Maven 版本属性不存在或不唯一");
            }
            value = nodes.item(0).getTextContent().strip();
        }
        return value;
    }

    private void gradle(String content) {
        GradleJavaDeclarations.parse(content).forEach(value -> add(ToolchainKind.JDK, value));
    }

    private void toolVersions(String content) {
        var parsed = ToolVersionsDeclarations.parse(content);
        parsed.versions().forEach(this::add);
        parsed.errors()
                .forEach((kind, values) -> errors.computeIfAbsent(kind, ignored -> new ArrayList<>())
                        .addAll(values));
    }

    private void wrapper(String content) throws Exception {
        Properties properties = new Properties();
        properties.load(new StringReader(content));
        String distribution = properties.getProperty("distributionUrl", "");
        var matcher = Pattern.compile("(?:.*/)?gradle-([0-9]+(?:\\.[0-9]+){1,2})-(?:bin|all)\\.zip")
                .matcher(distribution);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("非固定 Gradle 发行版本");
        }
        add(ToolchainKind.GRADLE, matcher.group(1));
    }

    private void node(String content) throws Exception {
        JsonNode root = new ObjectMapper().readTree(content);
        if (!root.isObject()) {
            throw new IllegalArgumentException("package.json 必须是对象");
        }
        JsonNode engines = root.path("engines");
        if (root.has("engines") && !engines.isObject()) {
            throw new IllegalArgumentException("engines 必须是对象");
        }
        for (var entry : Map.of("node", ToolchainKind.NODE, "npm", ToolchainKind.NPM, "pnpm", ToolchainKind.PNPM)
                .entrySet()) {
            if (engines.has(entry.getKey())) {
                JsonNode value = engines.get(entry.getKey());
                if (!value.isTextual()) {
                    throw new IllegalArgumentException("engines 必须是文本约束");
                }
                add(entry.getValue(), value.textValue());
            }
        }
        if (root.has("packageManager")) {
            var manager = Pattern.compile(
                            "(npm|pnpm)@([0-9]+\\.[0-9]+\\.[0-9]+)(?:\\+sha(?:224|256|384|512)\\.[a-fA-F0-9]+)?")
                    .matcher(root.path("packageManager").asText());
            if (!manager.matches()) {
                throw new IllegalArgumentException("未注册的包管理器或非固定版本");
            }
            ToolchainKind selected = manager.group(1).equals("npm") ? ToolchainKind.NPM : ToolchainKind.PNPM;
            add(selected, manager.group(2));
        }
    }

    private void python(String content) {
        var parsed = Toml.parse(content);
        if (parsed.hasErrors()) {
            throw new IllegalArgumentException("pyproject.toml 格式错误");
        }
        String requirement = parsed.getString("project.requires-python");
        if (requirement != null) {
            requirePythonSpecifier(requirement);
            add(ToolchainKind.PYTHON, requirement);
        }
        String poetry = parsed.getString("tool.poetry.dependencies.python");
        if (poetry != null) {
            add(ToolchainKind.PYTHON, poetry);
        }
    }

    private static void requirePythonSpecifier(String value) {
        for (String part : value.split(",", -1)) {
            var matcher = Pattern.compile("(~=|==|!=|<=|>=|<|>)\\s*([0-9]+(?:\\.[0-9]+){0,2})(\\.\\*)?")
                    .matcher(part.strip());
            if (!matcher.matches()
                    || matcher.group(1).equals("~=") && !matcher.group(2).contains(".")
                    || matcher.group(3) != null && !List.of("==", "!=").contains(matcher.group(1))) {
                throw new IllegalArgumentException("requires-python 不是受支持的 PEP 440 数字约束");
            }
        }
    }

    private void add(ToolchainKind kind, String constraint) {
        // 同时校验语法，不依赖此版本是否满足，从而保留矛盾约束的明确诊断。
        ToolchainVersionConstraint.matches("0.0.0", constraint, kind == ToolchainKind.PYTHON);
        constraints.computeIfAbsent(kind, ignored -> new ArrayList<>()).add(constraint);
    }

    private static List<ToolchainKind> kinds(String path) {
        if (path.equals(".tool-versions")) {
            return List.of(ToolchainKind.values());
        }
        if (path.equals("pom.xml") || path.equals(".java-version") || path.startsWith("build.gradle")) {
            return List.of(ToolchainKind.JDK);
        }
        if (path.startsWith("gradle/")) {
            return List.of(ToolchainKind.GRADLE);
        }
        if (path.equals("pyproject.toml") || path.equals(".python-version")) {
            return List.of(ToolchainKind.PYTHON);
        }
        return List.of(ToolchainKind.NODE, ToolchainKind.NPM, ToolchainKind.PNPM);
    }
}
