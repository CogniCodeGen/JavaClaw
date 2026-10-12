package com.javaclaw.infrastructure.plugin.v2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.application.plugin.v2.PluginManagementService.ContributionInfo;
import com.javaclaw.application.plugin.v2.PluginManagementService.PluginException;
import com.javaclaw.framework.spi.JsonSchemaValidator;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 已验证清单的静态视图，不读取或加载插件类。 */
record PluginManifest(String id, String name, String version, String description, String publisher,
                      String entrypoint, String artifact, String json, String schemaJson,
                      String uiSchemaJson, Set<String> permissions, Set<String> capabilities,
                      List<ContributionInfo> contributions, Set<String> secretKeys,
                      boolean exclusive, int heapMiB, Set<String> javaModules, boolean nativeAccess) {
    private static final Set<String> SUPPORTED = Set.of("TOOL", "MODEL", "SKILL", "CONTEXT", "WORKFLOW", "HOOK");
    private static final JsonSchemaValidator SCHEMAS = new JsonSchemaValidator();
    private static final String MANIFEST_SCHEMA = loadManifestSchema();

    static PluginManifest parse(ObjectMapper json, String value) {
        try {
            JsonNode root = json.readTree(value);
            if (root == null || !root.isObject()) throw invalid("插件清单必须为对象");
            rejectRemoteReferences(root);
            var issues = SCHEMAS.validate(json.readTree(MANIFEST_SCHEMA), root, "manifest");
            if (!issues.isEmpty()) throw invalid("插件清单不符合 v2 格式: " + issues.getFirst().path() + " " + issues.getFirst().message());
            validatePlatform(root);
            String id = identifier(required(root, "id"));
            String version = required(root, "version");
            if (!version.matches("[A-Za-z0-9][A-Za-z0-9._+\\-]{0,79}")) throw invalid("插件版本无效");
            if (root.path("protocol").path("major").asInt(-1) != 2) throw invalid("插件必须使用 v2 协议");
            String entry = required(root, "entrypoint");
            if (!entry.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+")) throw invalid("插件入口类无效");
            String artifact = PluginPaths.relative(required(root, "artifact"));
            if (!artifact.endsWith(".jar")) throw invalid("Java 插件工件必须为 JAR");
            Set<String> permissions = new LinkedHashSet<>(strings(root.path("permissions")));
            if (permissions.stream().anyMatch(permission -> !permission.matches("[A-Za-z][A-Za-z0-9._:\\-/]{0,127}"))) throw invalid("插件权限名称无效");
            Set<String> javaModules = strings(root.path("runtime").path("javaModules"));
            // 只接收独立系统模块名；实际存在性在启动前检查，每项仍需单独批准。
            if (javaModules.stream().anyMatch(module -> module.length() > 113 || !module.matches("(?:java|jdk)(?:\\.[A-Za-z][A-Za-z0-9_]*)+")))
                throw invalid("插件 JVM 模块名称无效");
            if (root.path("runtime").has("jvmArgs") || root.path("runtime").has("javaOptions") || root.path("runtime").has("agents")) throw invalid("插件不能声明任意 JVM 参数或代理");
            javaModules.forEach(module -> permissions.add("runtime.module:" + module));
            boolean nativeAccess = root.path("runtime").path("nativeAccess").asBoolean(false);
            if (nativeAccess) permissions.add("runtime.native-access");
            Set<String> capabilities = new LinkedHashSet<>();
            strings(root.path("capabilities")).forEach(kind -> capabilities.add(kind.toUpperCase(java.util.Locale.ROOT)));
            List<ContributionInfo> contributions = new ArrayList<>();
            Set<String> ids = new LinkedHashSet<>();
            JsonNode declarations = root.path("contributions");
            if (!declarations.isArray()) throw invalid("插件必须声明 contributions 数组");
            for (JsonNode declaration : declarations) {
                String contributionId = required(declaration, "id");
                if (!contributionId.matches("[A-Za-z0-9][A-Za-z0-9._:/\\-]{0,127}") || !ids.add(contributionId)) throw invalid("插件贡献标识无效或重复");
                String capability = declaration.path("capability").asText(declaration.path("kind").asText()).toUpperCase(java.util.Locale.ROOT);
                if (!SUPPORTED.contains(capability)) throw invalid("不支持的插件能力: " + capability);
                String contractVersion = declaration.path("contractVersion").asText("2.0");
                if (!contractVersion.equals("2.0")) throw invalid("不支持的能力契约版本: " + contractVersion);
                capabilities.add(capability);
                JsonNode input = declaration.path("inputSchema");
                String schema = input.isMissingNode() ? "{}" : input.toString();
                SCHEMAS.requireValidSchema(json.readTree(schema), "插件输入 Schema");
                String outputSchema = declaration.path("outputSchema").isMissingNode() ? "{}" : declaration.path("outputSchema").toString();
                SCHEMAS.requireValidSchema(json.readTree(outputSchema), "插件输出 Schema");
                contributions.add(new ContributionInfo(contributionId, capability, contractVersion,
                        declaration.path("name").asText(contributionId), declaration.path("description").asText(""), schema,
                        declaration.path("metadata").isObject() ? declaration.path("metadata").toString() : "{}",
                        outputSchema));
            }
            if (!SUPPORTED.containsAll(capabilities)) throw invalid("插件声明了未知能力");
            JsonNode schema = root.path("configurationSchema");
            if (schema.isMissingNode()) schema = json.readTree("{\"type\":\"object\",\"additionalProperties\":false}");
            if (!schema.isObject()) throw invalid("配置 Schema 必须为对象");
            SCHEMAS.requireValidSchema(schema, "插件配置 Schema");
            Set<String> secrets = new LinkedHashSet<>();
            schema.path("properties").fields().forEachRemaining(field -> {
                if (field.getValue().path("writeOnly").asBoolean(false) || field.getValue().path("x-javaclaw-secret").asBoolean(false)) secrets.add(field.getKey());
            });
            for (String secret : secrets) if (!schema.path("properties").path(secret).path("type").asText().equals("string")) throw invalid("敏感配置必须为顶层字符串字段");
            rejectNestedSecrets(schema, schema.path("properties"));
            return new PluginManifest(id, root.path("name").asText(id), version,
                    root.path("description").asText(""), root.path("publisher").asText(""), entry,
                    artifact, value, schema.toString(), root.path("uiSchema").isObject() ? root.path("uiSchema").toString() : "{}",
                    Set.copyOf(permissions), Set.copyOf(capabilities), List.copyOf(contributions), Set.copyOf(secrets),
                    root.path("resources").path("exclusive").asBoolean(false),
                    Math.max(128, Math.min(16_384, root.path("resources").path("heapMiB").asInt(256))), Set.copyOf(javaModules), nativeAccess);
        } catch (PluginException failure) { throw failure; }
        catch (Exception failure) { throw new PluginException("invalid_manifest", "插件清单无法解析: " + failure.getMessage(), failure); }
    }

    static String identifier(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._\\-]{0,127}") || value.equals(".") || value.equals("..")) throw invalid("插件或工作区标识无效");
        return value;
    }
    private static String required(JsonNode root, String field) {
        String value = root.path(field).asText("").strip();
        if (value.isEmpty()) throw invalid("插件清单缺少字段: " + field);
        return value;
    }
    private static Set<String> strings(JsonNode values) {
        if (values.isMissingNode()) return Set.of();
        if (!values.isArray()) throw invalid("清单列表字段必须为数组");
        Set<String> result = new LinkedHashSet<>();
        for (JsonNode value : values) {
            if (!value.isTextual() || value.asText().isBlank() || !result.add(value.asText())) throw invalid("清单列表包含空值或重复值");
        }
        return result;
    }
    private static PluginException invalid(String message) { return new PluginException("invalid_manifest", message); }
    private static String loadManifestSchema() {
        try (var source = PluginManifest.class.getResourceAsStream("/schema/plugin-manifest-v2.schema.json")) {
            if (source == null) throw new IOException("协议工件缺少插件清单 Schema，请重新构建 plugin-protocol");
            return new String(source.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException failure) { throw new IllegalStateException("插件清单 Schema 无法加载", failure); }
    }
    private static void validatePlatform(JsonNode root) {
        JsonNode minimum = root.path("runtime").path("minimumJavaVersion");
        if (!minimum.isMissingNode() && (!minimum.canConvertToInt() || minimum.intValue() > Runtime.version().feature())) throw invalid("插件要求更高版本的 Java: " + minimum.asText());
        if (minimum.isMissingNode() && Runtime.version().feature() < 25) throw invalid("插件 Runner 至少需要 Java 25");
        JsonNode platform = root.path("platform");
        checkSupportedPlatform(platform.path("os"), normalizedOs(System.getProperty("os.name", "")), "操作系统");
        checkSupportedPlatform(platform.path("architectures"), normalizedArchitecture(System.getProperty("os.arch", "")), "处理器架构");
    }
    private static void checkSupportedPlatform(JsonNode declaration, String current, String description) {
        if (declaration.isMissingNode()) return;
        Set<String> allowed = strings(declaration);
        if (allowed.isEmpty() || !allowed.contains(current)) throw invalid("插件不支持当前" + description + ": " + current);
    }
    static String normalizedOs(String name) {
        String value = name.toLowerCase(java.util.Locale.ROOT);
        if (value.contains("mac") || value.contains("darwin")) return "macos";
        if (value.startsWith("windows")) return "windows";
        if (value.contains("linux")) return "linux";
        return value;
    }
    static String normalizedArchitecture(String name) {
        return switch (name.toLowerCase(java.util.Locale.ROOT)) {
            case "arm64", "aarch64" -> "aarch64";
            case "amd64", "x86-64", "x86_64" -> "x86_64";
            default -> name.toLowerCase(java.util.Locale.ROOT);
        };
    }
    private static void rejectRemoteReferences(JsonNode node) {
        for (String reference : List.of("$ref", "$dynamicRef", "$recursiveRef")) {
            if (node.isObject() && node.has(reference) && !node.path(reference).asText().startsWith("#")) throw invalid("Schema 只能引用包内定义");
        }
        if (node.isObject() && node.has("$schema") && !Set.of("https://json-schema.org/draft/2020-12/schema", "http://json-schema.org/draft-07/schema#", "https://json-schema.org/draft-07/schema#").contains(node.path("$schema").asText())) throw invalid("Schema 必须使用受支持的本地元规范");
        if (node.isContainerNode()) node.forEach(PluginManifest::rejectRemoteReferences);
    }
    private static void rejectNestedSecrets(JsonNode node, JsonNode topProperties) {
        if (node.isObject() && (node.path("writeOnly").asBoolean(false) || node.path("x-javaclaw-secret").asBoolean(false))) {
            boolean direct = false;
            for (JsonNode property : topProperties) if (property == node) direct = true;
            if (!direct) throw invalid("敏感配置仅支持顶层字段，请将嵌套密钥提升到配置顶层");
        }
        if (node.isContainerNode()) node.forEach(child -> rejectNestedSecrets(child, topProperties));
    }
}
