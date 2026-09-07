package com.javaclaw.protocol;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolMethodSchemaCatalogTest {
    private static final String DRAFT_2020_12 = "https://json-schema.org/draft/2020-12/schema";
    private static final Set<String> JSON_TYPES =
            Set.of("null", "boolean", "object", "array", "number", "string", "integer");

    @Test
    void 方法资源与Java目录逐项一致且没有无契约方法() throws Exception {
        Map<String, JsonNode> resources = schemaResources();
        JsonNode methods = resources.get("methods-v3.json").path("methods");
        Map<String, RpcMethodKind> javaMethods = javaMethods();
        Map<String, RpcMethodKind> resourceMethods = new HashMap<>();
        List<String> resourceOrder = new ArrayList<>();

        for (JsonNode method : methods) {
            String name = requiredText(method, "name");
            RpcMethodKind kind =
                    RpcMethodKind.valueOf(requiredText(method, "kind").toUpperCase());
            assertFalse(resourceMethods.containsKey(name), "重复方法: " + name);
            resourceMethods.put(name, kind);
            resourceOrder.add(name);
            assertReferenceExists(requiredText(method, "paramsSchema"), "methods-v3.json", resources);
            if (kind == RpcMethodKind.NOTIFICATION) {
                assertFalse(method.has("resultSchema"), "通知不得声明 result: " + name);
            } else {
                assertReferenceExists(requiredText(method, "resultSchema"), "methods-v3.json", resources);
            }
        }

        assertEquals(javaMethods, resourceMethods);
        assertEquals(159, resourceOrder.size());
        assertEquals(
                resourceOrder,
                MethodCatalog.methods().stream().map(RpcMethod::name).toList());
        assertEquals(Set.of("extension/event"), notificationNames(resourceMethods));
    }

    @Test
    void 所有写方法都使用封闭幂等并发信封() throws Exception {
        Map<String, JsonNode> resources = schemaResources();
        JsonNode methods = resources.get("methods-v3.json").path("methods");

        for (JsonNode method : methods) {
            if (!"command".equals(requiredText(method, "kind"))) {
                continue;
            }
            String name = requiredText(method, "name");
            if ("initialize/session".equals(name)) {
                continue;
            }
            SchemaLocation location = resolve(requiredText(method, "paramsSchema"), "methods-v3.json", resources);
            List<JsonNode> composition = composition(location, resources);
            assertTrue(composition.stream().anyMatch(ProtocolMethodSchemaCatalogTest::isWriteEnvelope), name);
            assertTrue(composition.stream().anyMatch(node -> hasClosedPayload(node, location.file(), resources)), name);
        }
    }

    @Test
    void 所有Schema使用Draft202012且引用可解析() throws Exception {
        Map<String, JsonNode> resources = schemaResources();

        resources.forEach((file, schema) -> {
            assertEquals(DRAFT_2020_12, schema.path("$schema").textValue(), file);
            assertTrue(schema.isObject(), file);
            validateSchemaKeywords(schema, file);
            visit(schema, node -> {
                if (node.isObject() && node.has("$ref")) {
                    assertReferenceExists(node.path("$ref").textValue(), file, resources);
                }
            });
        });
    }

    private static Map<String, RpcMethodKind> javaMethods() {
        Map<String, RpcMethodKind> result = new HashMap<>();
        MethodCatalog.methods().forEach(method -> {
            assertFalse(result.containsKey(method.name()), "Java 目录重复方法: " + method.name());
            result.put(method.name(), method.kind());
        });
        return Map.copyOf(result);
    }

    private static Set<String> notificationNames(Map<String, RpcMethodKind> methods) {
        HashSet<String> result = new HashSet<>();
        methods.forEach((name, kind) -> {
            if (kind == RpcMethodKind.NOTIFICATION) {
                result.add(name);
            }
        });
        return Set.copyOf(result);
    }

    private static Map<String, JsonNode> schemaResources() throws IOException, URISyntaxException {
        var directoryUrl = ProtocolMethodSchemaCatalogTest.class.getResource("/schema");
        assertNotNull(directoryUrl, "schema 资源目录不存在");
        Path directory = Path.of(directoryUrl.toURI());
        Map<String, JsonNode> result = new HashMap<>();
        try (var files = Files.list(directory)) {
            for (Path path : files.filter(file -> file.getFileName().toString().endsWith(".json"))
                    .toList()) {
                JsonNode previous = result.put(
                        path.getFileName().toString(),
                        new CanonicalJson().mapper().readTree(Files.readString(path)));
                assertEquals(null, previous, "重复 schema 文件");
            }
        }
        assertTrue(result.containsKey("methods-v3.json"));
        return Map.copyOf(result);
    }

    private static String requiredText(JsonNode object, String name) {
        JsonNode value = object.get(name);
        assertNotNull(value, "缺少字段: " + name);
        assertTrue(value.isTextual() && !value.textValue().isBlank(), "字段必须是非空文本: " + name);
        return value.textValue();
    }

    private static void assertReferenceExists(String reference, String currentFile, Map<String, JsonNode> resources) {
        SchemaLocation resolved = resolve(reference, currentFile, resources);
        assertFalse(resolved.node().isMissingNode(), currentFile + " 引用了不存在的位置: " + reference);
    }

    private static SchemaLocation resolve(String reference, String currentFile, Map<String, JsonNode> resources) {
        int fragment = reference.indexOf('#');
        String file = fragment < 0 ? reference : reference.substring(0, fragment);
        String pointer = fragment < 0 ? "" : reference.substring(fragment + 1);
        String targetFile = file.isEmpty() ? currentFile : file;
        JsonNode document = resources.get(targetFile);
        assertNotNull(document, currentFile + " 引用了不存在的文件: " + targetFile);
        JsonNode target = pointer.isEmpty() ? document : document.at(pointer);
        return new SchemaLocation(targetFile, target);
    }

    private static List<JsonNode> composition(SchemaLocation root, Map<String, JsonNode> resources) {
        ArrayList<JsonNode> result = new ArrayList<>();
        collectComposition(root, resources, new HashSet<>(), result);
        return List.copyOf(result);
    }

    private static void collectComposition(
            SchemaLocation location, Map<String, JsonNode> resources, Set<String> visited, List<JsonNode> result) {
        JsonNode node = location.node();
        String key = location.file() + ':' + node.toString();
        if (!visited.add(key)) {
            return;
        }
        result.add(node);
        if (node.has("$ref")) {
            collectComposition(
                    resolve(node.path("$ref").textValue(), location.file(), resources), resources, visited, result);
        }
        for (String keyword : List.of("allOf", "anyOf", "oneOf")) {
            for (JsonNode child : node.path(keyword)) {
                collectComposition(new SchemaLocation(location.file(), child), resources, visited, result);
            }
        }
    }

    private static boolean isWriteEnvelope(JsonNode node) {
        Set<String> required = textSet(node.path("required"));
        return node.path("additionalProperties").isBoolean()
                && !node.path("additionalProperties").booleanValue()
                && required.containsAll(Set.of("idempotencyKey", "expectedRevision", "payload"));
    }

    private static boolean hasClosedPayload(JsonNode node, String file, Map<String, JsonNode> resources) {
        JsonNode payload = node.path("properties").path("payload");
        if (payload.isMissingNode()) {
            return false;
        }
        SchemaLocation location = payload.has("$ref")
                ? resolve(payload.path("$ref").textValue(), file, resources)
                : new SchemaLocation(file, payload);
        return composition(location, resources).stream()
                .anyMatch(candidate -> candidate.path("additionalProperties").isBoolean()
                        && !candidate.path("additionalProperties").booleanValue());
    }

    private static Set<String> textSet(JsonNode array) {
        HashSet<String> result = new HashSet<>();
        if (array.isArray()) {
            array.forEach(value -> {
                if (value.isTextual()) {
                    result.add(value.textValue());
                }
            });
        }
        return Set.copyOf(result);
    }

    private static void validateSchemaKeywords(JsonNode schema, String file) {
        visitSchemas(schema, node -> {
            if (!node.isObject()) {
                return;
            }
            validateType(node, file);
            validateRequired(node, file);
            validateObjectKeyword(node, "properties", file);
            validateObjectKeyword(node, "$defs", file);
            validateAdditionalProperties(node, file);
            validateComposition(node, file);
        });
    }

    private static void validateType(JsonNode schema, String file) {
        JsonNode type = schema.get("type");
        if (type == null) {
            return;
        }
        if (type.isTextual()) {
            assertTrue(JSON_TYPES.contains(type.textValue()), file + " 含非法 type");
            return;
        }
        assertTrue(type.isArray() && type.size() > 0, file + " 的 type 必须是文本或非空数组");
        type.forEach(value -> assertTrue(value.isTextual() && JSON_TYPES.contains(value.textValue()), file));
    }

    private static void validateRequired(JsonNode schema, String file) {
        JsonNode required = schema.get("required");
        if (required == null) {
            return;
        }
        assertTrue(required.isArray(), file + " 的 required 必须是数组");
        HashSet<String> names = new HashSet<>();
        required.forEach(value -> assertTrue(value.isTextual() && names.add(value.textValue()), file));
    }

    private static void validateObjectKeyword(JsonNode schema, String keyword, String file) {
        JsonNode value = schema.get(keyword);
        if (value != null) {
            assertTrue(value.isObject(), file + " 的 " + keyword + " 必须是对象");
        }
    }

    private static void validateAdditionalProperties(JsonNode schema, String file) {
        JsonNode value = schema.get("additionalProperties");
        if (value != null) {
            assertTrue(value.isBoolean() || value.isObject(), file + " 的 additionalProperties 非法");
        }
    }

    private static void validateComposition(JsonNode schema, String file) {
        for (String keyword : List.of("allOf", "anyOf", "oneOf")) {
            JsonNode value = schema.get(keyword);
            if (value == null) {
                continue;
            }
            assertTrue(value.isArray() && !value.isEmpty(), file + " 的 " + keyword + " 必须是非空数组");
            value.forEach(child -> assertTrue(child.isObject() || child.isBoolean(), file));
        }
    }

    private static void visit(JsonNode root, java.util.function.Consumer<JsonNode> visitor) {
        visitor.accept(root);
        if (root.isContainerNode()) {
            Iterator<JsonNode> children = root.elements();
            while (children.hasNext()) {
                visit(children.next(), visitor);
            }
        }
    }

    private static void visitSchemas(JsonNode schema, java.util.function.Consumer<JsonNode> visitor) {
        visitor.accept(schema);
        visitSchemaMap(schema.path("properties"), visitor);
        visitSchemaMap(schema.path("$defs"), visitor);
        for (String keyword : List.of("allOf", "anyOf", "oneOf")) {
            schema.path(keyword).forEach(child -> visitSchemas(child, visitor));
        }
        for (String keyword :
                List.of("items", "additionalProperties", "propertyNames", "not", "if", "then", "else", "contains")) {
            JsonNode child = schema.path(keyword);
            if (child.isObject()) {
                visitSchemas(child, visitor);
            }
        }
    }

    private static void visitSchemaMap(JsonNode schemas, java.util.function.Consumer<JsonNode> visitor) {
        if (schemas.isObject()) {
            schemas.elements().forEachRemaining(child -> visitSchemas(child, visitor));
        }
    }

    private record SchemaLocation(String file, JsonNode node) {}
}
