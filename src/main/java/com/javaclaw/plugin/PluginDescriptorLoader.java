package com.javaclaw.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.framework.spi.JsonSchemaValidator;
import com.javaclaw.plugin.api.Capability;
import com.javaclaw.plugin.api.PluginDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 描述符解析器 —— 从插件 jar 根目录读取并校验 {@code plugin.json}，产出 {@link PluginDescriptor}。
 *
 * <p>在建立插件类加载器<b>之前</b>调用：直接以 {@link JarFile} 打开 jar 读取条目，不加载任何插件类。</p>
 *
 * @author JavaClaw
 */
final class PluginDescriptorLoader {

    private static final Logger log = LoggerFactory.getLogger(PluginDescriptorLoader.class);
    private static final JsonSchemaValidator SCHEMAS = new JsonSchemaValidator();
    private static final String DESCRIPTOR_ENTRY = "plugin.json";
    /** 同时用于目录名、数据库键与线程名前缀，必须是单个安全路径段。 */
    private static final Pattern SAFE_ID = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?");
    private static final Pattern UI_ID = Pattern.compile("[a-z][a-z0-9-]{0,31}");
    private static final int MAX_UI_PAGES = 8;
    private static final int MAX_UI_SECTIONS = 24;
    private static final Set<String> UI_ROOT_FIELDS = Set.of("schemaVersion", "pages");
    private static final Set<String> UI_PAGE_FIELDS = Set.of(
            "id", "title", "description", "sections");
    private static final Set<String> UI_SECTION_FIELDS = Set.of(
            "id", "type", "title", "description", "fields");

    private final ObjectMapper json;

    PluginDescriptorLoader(ObjectMapper json) {
        this.json = java.util.Objects.requireNonNull(json, "json");
    }

    /**
     * 读取并校验插件 jar 中的描述符。
     *
     * @param jarPath 插件 jar 路径
     * @return 解析后的描述符
     * @throws IOException jar 无法打开、缺少 plugin.json、或必填字段缺失/非法
     */
    PluginDescriptor load(Path jarPath) throws IOException {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            JarEntry entry = jar.getJarEntry(DESCRIPTOR_ENTRY);
            if (entry == null) {
                throw new IOException("插件 jar 缺少 " + DESCRIPTOR_ENTRY + "：" + jarPath.getFileName());
            }
            try (InputStream in = jar.getInputStream(entry)) {
                JsonNode root = json.readTree(in);
                return parse(root, jarPath);
            }
        }
    }

    private static PluginDescriptor parse(JsonNode root, Path jarPath) throws IOException {
        String id = requireText(root, "id", jarPath);
        if (!SAFE_ID.matcher(id).matches()) {
            throw new IOException("插件 " + jarPath.getFileName()
                    + " 的 id 非法；仅允许 1-64 位小写字母、数字和中间短横线");
        }
        PluginDescriptor.PluginType pluginType = parsePluginType(root, jarPath);
        PluginDescriptor.Service service = pluginType == PluginDescriptor.PluginType.SERVICE_PLUGIN
                ? parseService(root.get("service"), jarPath) : null;
        String mainClass = pluginType == PluginDescriptor.PluginType.SERVICE_PLUGIN
                ? service.mainClass() : requireText(root, "main", jarPath);
        String name = optText(root, "name", id);
        String version = optText(root, "version", "0.0.0");
        String apiVersion = pluginType == PluginDescriptor.PluginType.SERVICE_PLUGIN
                ? optText(root, "apiVersion", service.apiVersion())
                : requireText(root, "apiVersion", jarPath);
        String description = optText(root, "description", "");

        Set<Capability> capabilities = parseCapabilities(root.get("capabilities"), id);
        List<PluginDescriptor.ConfigField> config = parseConfig(root.get("config"));
        String configurationSchema = parseConfigurationSchema(
                root.get("configurationSchema"), jarPath);
        PluginDescriptor.Inference inference = parseInference(root.get("inference"), jarPath);
        if (inference != null && pluginType != PluginDescriptor.PluginType.SERVICE_PLUGIN) {
            throw new IOException("推理能力只能由 SERVICE_PLUGIN 声明");
        }
        PluginDescriptor.ConfigurationUi configurationUi = parseConfigurationUi(
                root.get("configurationUi"), root.get("configurationSchema"),
                pluginType, service, inference, jarPath);

        PluginDescriptor descriptor = new PluginDescriptor(
                id, name, version, apiVersion, mainClass, description, capabilities, config,
                pluginType, service, configurationSchema, inference, configurationUi);
        log.info("已解析插件描述符：id={}, name={}, version={}, type={}, 能力={}",
                id, name, version, pluginType, capabilities);
        return descriptor;
    }

    private static PluginDescriptor.PluginType parsePluginType(JsonNode root, Path jarPath)
            throws IOException {
        String raw = optText(root, "pluginType", "IN_PROCESS");
        try {
            return PluginDescriptor.PluginType.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException failure) {
            throw new IOException("插件 " + jarPath.getFileName() + " 的 pluginType 无效: " + raw,
                    failure);
        }
    }

    private static PluginDescriptor.Service parseService(JsonNode node, Path jarPath)
            throws IOException {
        if (node == null || !node.isObject()) {
            throw new IOException("服务插件 " + jarPath.getFileName() + " 缺少 service 配置");
        }
        String mainClass = requireText(node, "mainClass", jarPath);
        String apiVersion = requireText(node, "apiVersion", jarPath);
        PluginDescriptor.StartupPolicy startupPolicy;
        try {
            startupPolicy = PluginDescriptor.StartupPolicy.valueOf(
                    optText(node, "startupPolicy", "MANUAL").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException failure) {
            throw new IOException("服务插件启动策略无效", failure);
        }
        JsonNode hints = node.path("resourceHints");
        PluginDescriptor.ResourceHints resources = new PluginDescriptor.ResourceHints(
                hints.path("heapMiB").asInt(256),
                hints.path("nativeMemoryMiB").asInt(0),
                hints.path("computeThreads").asInt(1),
                hints.path("ioConcurrency").asInt(64),
                hints.path("fileDescriptors").asInt(64),
                hints.path("usesNativeCode").asBoolean(false));
        List<PluginDescriptor.ExternalEndpoint> endpoints = new ArrayList<>();
        JsonNode endpointNodes = node.path("externalEndpoints");
        if (endpointNodes.isArray()) {
            Set<String> ids = new LinkedHashSet<>();
            for (JsonNode endpoint : endpointNodes) {
                String endpointId = requireText(endpoint, "id", jarPath);
                if (!ids.add(endpointId)) {
                    throw new IOException("服务插件包含重复外部端点 id: " + endpointId);
                }
                PluginDescriptor.EndpointProtocol protocol;
                try {
                    protocol = PluginDescriptor.EndpointProtocol.valueOf(
                            optText(endpoint, "protocol", "HTTP").toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException failure) {
                    throw new IOException("服务插件外部端点协议无效: " + endpointId, failure);
                }
                Set<String> capabilities = new LinkedHashSet<>();
                JsonNode capabilityNodes = endpoint.path("capabilities");
                if (capabilityNodes.isArray()) {
                    capabilityNodes.forEach(value -> {
                        String capability = value.asText("").strip();
                        if (!capability.isEmpty()) capabilities.add(capability);
                    });
                }
                endpoints.add(new PluginDescriptor.ExternalEndpoint(
                        endpointId, protocol, capabilities));
            }
        }
        Set<String> desktopServices = new LinkedHashSet<>();
        JsonNode desktopServiceNodes = node.path("desktopServices");
        if (desktopServiceNodes.isArray()) {
            desktopServiceNodes.forEach(value -> {
                String service = value.asText("").strip();
                if (!service.isEmpty()) desktopServices.add(service);
            });
        }
        try {
            return new PluginDescriptor.Service(mainClass, apiVersion, startupPolicy, resources,
                    endpoints, desktopServices);
        } catch (IllegalArgumentException failure) {
            throw new IOException("服务插件配置无效: " + failure.getMessage(), failure);
        }
    }

    /** 解析能力数组：大小写不敏感映射到枚举，未知能力告警并跳过。 */
    private static Set<Capability> parseCapabilities(JsonNode node, String pluginId) {
        Set<Capability> result = new LinkedHashSet<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                String raw = item.asText("").strip();
                if (raw.isEmpty()) {
                    continue;
                }
                try {
                    result.add(Capability.valueOf(raw.toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException e) {
                    log.warn("插件[{}]声明了未知能力「{}」，已忽略", pluginId, raw);
                }
            }
        }
        return result;
    }

    private static List<PluginDescriptor.ConfigField> parseConfig(JsonNode node) {
        List<PluginDescriptor.ConfigField> result = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                String key = item.path("key").asText("").strip();
                if (key.isEmpty()) {
                    continue;
                }
                String label = item.path("label").asText(key);
                boolean secret = item.path("secret").asBoolean(false);
                result.add(new PluginDescriptor.ConfigField(key, label, secret));
            }
        }
        return result;
    }

    private static String parseConfigurationSchema(JsonNode node, Path jarPath)
            throws IOException {
        if (node == null || node.isNull()) return "";
        if (!node.isObject() || !"object".equals(node.path("type").asText())) {
            throw new IOException("插件 " + jarPath.getFileName()
                    + " 的 configurationSchema 必须是 object Schema");
        }
        String draft = node.path("$schema").asText("");
        if (!draft.isBlank()
                && !"https://json-schema.org/draft/2020-12/schema".equals(draft)) {
            throw new IOException("插件 configurationSchema 仅支持 JSON Schema Draft 2020-12");
        }
        JsonNode properties = node.path("properties");
        if (!properties.isMissingNode() && !properties.isObject()) {
            throw new IOException("插件 configurationSchema.properties 必须是对象");
        }
        try {
            SCHEMAS.requireValidSchema(node,
                    "plugin configuration in " + jarPath.getFileName());
        } catch (IllegalArgumentException invalid) {
            throw new IOException("插件 configurationSchema 不是有效的 Draft 2020-12 Schema",
                    invalid);
        }
        return node.toString();
    }

    private static PluginDescriptor.Inference parseInference(JsonNode node, Path jarPath)
            throws IOException {
        if (node == null || node.isNull()) return null;
        if (!node.isObject()) {
            throw new IOException("插件 " + jarPath.getFileName() + " 的 inference 必须是对象");
        }
        JsonNode protocol = node.path("protocol");
        JsonNode parameterSchema = node.get("parameterSchema");
        if (parameterSchema == null || !parameterSchema.isObject()) {
            throw new IOException("推理插件缺少 parameterSchema 对象");
        }
        try {
            SCHEMAS.requireValidSchema(parameterSchema,
                    "inference parameters in " + jarPath.getFileName());
        } catch (IllegalArgumentException invalid) {
            throw new IOException("推理插件 parameterSchema 不是有效的 Draft 2020-12 Schema",
                    invalid);
        }
        Set<String> inferenceCapabilities = new LinkedHashSet<>();
        JsonNode values = node.path("capabilities");
        if (values.isArray()) {
            values.forEach(value -> {
                String capability = value.asText("").strip();
                if (!capability.isEmpty()) inferenceCapabilities.add(capability);
            });
        }
        try {
            return new PluginDescriptor.Inference(
                    requireText(node, "engine", jarPath),
                    requireText(node, "engineVersion", jarPath),
                    optText(node, "adapterVersion", ""),
                    protocol.path("major").asInt(0),
                    protocol.path("minor").asInt(-1),
                    inferenceCapabilities, parameterSchema.toString());
        } catch (IllegalArgumentException failure) {
            throw new IOException("推理插件声明无效: " + failure.getMessage(), failure);
        }
    }

    private static PluginDescriptor.ConfigurationUi parseConfigurationUi(
            JsonNode node,
            JsonNode configurationSchema,
            PluginDescriptor.PluginType pluginType,
            PluginDescriptor.Service service,
            PluginDescriptor.Inference inference,
            Path jarPath) throws IOException {
        if (node == null || node.isNull()) return null;
        if (pluginType != PluginDescriptor.PluginType.SERVICE_PLUGIN) {
            throw new IOException("configurationUi 只能由 SERVICE_PLUGIN 声明");
        }
        requireObject(node, "configurationUi");
        requireOnlyFields(node, UI_ROOT_FIELDS, "configurationUi");
        if (!node.path("schemaVersion").isIntegralNumber()
                || node.path("schemaVersion").asInt() != 1) {
            throw new IOException("插件 " + jarPath.getFileName()
                    + " 的 configurationUi.schemaVersion 仅支持 1");
        }
        JsonNode pagesNode = node.get("pages");
        if (pagesNode == null || !pagesNode.isArray() || pagesNode.isEmpty()) {
            throw new IOException("configurationUi.pages 必须是非空数组");
        }
        if (pagesNode.size() > MAX_UI_PAGES) {
            throw new IOException("configurationUi 最多允许 " + MAX_UI_PAGES + " 个页面");
        }
        List<PluginDescriptor.ConfigurationPage> pages = new ArrayList<>();
        Set<String> pageIds = new LinkedHashSet<>();
        int sectionCount = 0;
        for (JsonNode pageNode : pagesNode) {
            requireObject(pageNode, "configurationUi 页面");
            requireOnlyFields(pageNode, UI_PAGE_FIELDS, "configurationUi 页面");
            String pageId = requireUiId(pageNode, "id", "配置页面", jarPath);
            if (pageId.startsWith("host-") || "runtime".equals(pageId)) {
                throw new IOException("配置页面 id 由宿主保留: " + pageId);
            }
            if (!pageIds.add(pageId)) throw new IOException("配置页面 id 重复: " + pageId);
            String title = requireUiText(pageNode, "title", "配置页面标题", 64, jarPath);
            String description = optionalUiText(
                    pageNode, "description", "配置页面说明", 512, jarPath);
            JsonNode sectionsNode = pageNode.get("sections");
            if (sectionsNode == null || !sectionsNode.isArray() || sectionsNode.isEmpty()) {
                throw new IOException("配置页面 " + pageId + " 的 sections 必须是非空数组");
            }
            sectionCount += sectionsNode.size();
            if (sectionCount > MAX_UI_SECTIONS) {
                throw new IOException("configurationUi 最多允许 " + MAX_UI_SECTIONS + " 个区块");
            }
            pages.add(new PluginDescriptor.ConfigurationPage(pageId, title, description,
                    parseUiSections(sectionsNode, configurationSchema, service, inference,
                            pageId, jarPath)));
        }
        long runtimeSections = pages.stream().flatMap(page -> page.sections().stream())
                .filter(section -> section.type()
                        == PluginDescriptor.ConfigurationSectionType.SERVICE_RUNTIME)
                .count();
        if (runtimeSections > 1) {
            throw new IOException("configurationUi 最多只能声明一个 SERVICE_RUNTIME 区块");
        }
        return new PluginDescriptor.ConfigurationUi(1, pages);
    }

    private static List<PluginDescriptor.ConfigurationSection> parseUiSections(
            JsonNode nodes,
            JsonNode configurationSchema,
            PluginDescriptor.Service service,
            PluginDescriptor.Inference inference,
            String pageId,
            Path jarPath) throws IOException {
        List<PluginDescriptor.ConfigurationSection> result = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        Set<String> referencedFields = new LinkedHashSet<>();
        for (JsonNode section : nodes) {
            requireObject(section, "configurationUi 区块");
            requireOnlyFields(section, UI_SECTION_FIELDS, "configurationUi 区块");
            String id = requireUiId(section, "id", "配置区块", jarPath);
            if (!ids.add(id)) throw new IOException("配置页面 " + pageId + " 的区块 id 重复: " + id);
            PluginDescriptor.ConfigurationSectionType type;
            try {
                type = PluginDescriptor.ConfigurationSectionType.valueOf(
                        requireUiText(section, "type", "配置区块类型", 32, jarPath));
            } catch (IllegalArgumentException failure) {
                throw new IOException("配置区块 " + id + " 的类型未知", failure);
            }
            String title = optionalUiText(section, "title", "配置区块标题", 64, jarPath);
            String description = optionalUiText(
                    section, "description", "配置区块说明", 2_048, jarPath);
            List<String> fields = parseUiFields(section, type, configurationSchema,
                    referencedFields, id, jarPath);
            validateUiCapabilities(type, service, inference, id);
            if (type == PluginDescriptor.ConfigurationSectionType.INFO
                    && title.isBlank() && description.isBlank()) {
                throw new IOException("INFO 区块 " + id + " 必须提供 title 或 description");
            }
            result.add(new PluginDescriptor.ConfigurationSection(
                    id, type, title, description, fields));
        }
        return List.copyOf(result);
    }

    private static List<String> parseUiFields(
            JsonNode section,
            PluginDescriptor.ConfigurationSectionType type,
            JsonNode configurationSchema,
            Set<String> referencedFields,
            String sectionId,
            Path jarPath) throws IOException {
        JsonNode fieldsNode = section.get("fields");
        if (type != PluginDescriptor.ConfigurationSectionType.SCHEMA_FORM) {
            if (fieldsNode != null) {
                throw new IOException("仅 SCHEMA_FORM 区块可以声明 fields: " + sectionId);
            }
            return List.of();
        }
        JsonNode properties = configurationSchema == null
                ? null : configurationSchema.path("properties");
        if (properties == null || !properties.isObject() || properties.isEmpty()) {
            throw new IOException("SCHEMA_FORM 区块要求插件声明 configurationSchema.properties");
        }
        List<String> candidates = new ArrayList<>();
        if (fieldsNode == null) {
            properties.fieldNames().forEachRemaining(candidates::add);
        } else {
            if (!fieldsNode.isArray() || fieldsNode.isEmpty()) {
                throw new IOException("SCHEMA_FORM.fields 必须是非空数组");
            }
            for (JsonNode field : fieldsNode) {
                if (!field.isTextual() || field.asText().isBlank()) {
                    throw new IOException("SCHEMA_FORM.fields 只能包含非空字段名");
                }
                candidates.add(field.asText().strip());
            }
        }
        List<String> result = new ArrayList<>();
        Set<String> local = new LinkedHashSet<>();
        for (String field : candidates) {
            JsonNode property = properties.get(field);
            if (property == null) {
                throw new IOException("SCHEMA_FORM 引用了不存在的配置字段: " + field);
            }
            if (property.path("x-javaclaw-hidden").asBoolean(false)
                    || property.path("x-javaclaw-host-managed").asBoolean(false)) {
                if (fieldsNode != null) {
                    throw new IOException("SCHEMA_FORM 不得引用宿主管理或隐藏字段: " + field);
                }
                continue;
            }
            if (!local.add(field)) throw new IOException("SCHEMA_FORM 字段重复: " + field);
            if (!referencedFields.add(field)) {
                throw new IOException("配置字段不能出现在多个 SCHEMA_FORM 区块: " + field);
            }
            result.add(field);
        }
        if (result.isEmpty()) {
            throw new IOException("SCHEMA_FORM 区块没有可由用户编辑的字段: " + sectionId);
        }
        return List.copyOf(result);
    }

    private static void validateUiCapabilities(
            PluginDescriptor.ConfigurationSectionType type,
            PluginDescriptor.Service service,
            PluginDescriptor.Inference inference,
            String sectionId) throws IOException {
        if ((type == PluginDescriptor.ConfigurationSectionType.INFERENCE_MODELS
                || type == PluginDescriptor.ConfigurationSectionType.INFERENCE_API
                || type == PluginDescriptor.ConfigurationSectionType.INFERENCE_CATALOG
                || type == PluginDescriptor.ConfigurationSectionType.INFERENCE_SERVICE)
                && inference == null) {
            throw new IOException(type + " 区块要求插件声明 inference: " + sectionId);
        }
        if ((type == PluginDescriptor.ConfigurationSectionType.EXTERNAL_ENDPOINTS
                || type == PluginDescriptor.ConfigurationSectionType.INFERENCE_API
                || type == PluginDescriptor.ConfigurationSectionType.INFERENCE_SERVICE)
                && (service == null || service.externalEndpoints().isEmpty())) {
            throw new IOException(type + " 区块要求插件声明外部端点: " + sectionId);
        }
        if (type == PluginDescriptor.ConfigurationSectionType.SERVICE_RUNTIME
                && service == null) {
            throw new IOException(type + " 区块要求插件声明 service: " + sectionId);
        }
    }

    private static void requireObject(JsonNode node, String label) throws IOException {
        if (node == null || !node.isObject()) throw new IOException(label + " 必须是对象");
    }

    private static void requireOnlyFields(JsonNode node, Set<String> allowed, String label)
            throws IOException {
        var names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!allowed.contains(name)) {
                throw new IOException(label + " 包含不允许的字段「" + name + "」");
            }
        }
    }

    private static String requireUiId(
            JsonNode node, String field, String label, Path jarPath) throws IOException {
        String value = requireUiText(node, field, label + " id", 32, jarPath);
        if (!UI_ID.matcher(value).matches()) {
            throw new IOException(label + " id 非法: " + value);
        }
        return value;
    }

    private static String requireUiText(
            JsonNode node, String field, String label, int maxLength, Path jarPath)
            throws IOException {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().strip().isEmpty()) {
            throw new IOException("插件 " + jarPath.getFileName() + " 的" + label + "不能为空");
        }
        String text = value.asText().strip();
        if (text.length() > maxLength) throw new IOException(label + "超过长度限制");
        return text;
    }

    private static String optionalUiText(
            JsonNode node, String field, String label, int maxLength, Path jarPath)
            throws IOException {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return "";
        if (!value.isTextual()) {
            throw new IOException("插件 " + jarPath.getFileName() + " 的" + label + "必须是文本");
        }
        String text = value.asText().strip();
        if (text.length() > maxLength) throw new IOException(label + "超过长度限制");
        return text;
    }

    private static String requireText(JsonNode root, String field, Path jarPath) throws IOException {
        JsonNode n = root.get(field);
        if (n == null || n.asText("").strip().isEmpty()) {
            throw new IOException("插件 " + jarPath.getFileName() + " 的 plugin.json 缺少必填字段「" + field + "」");
        }
        return n.asText().strip();
    }

    private static String optText(JsonNode root, String field, String defaultValue) {
        JsonNode n = root.get(field);
        return (n == null || n.asText("").strip().isEmpty()) ? defaultValue : n.asText().strip();
    }
}
