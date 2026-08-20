package com.javaclaw.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginDescriptorLoaderTest {

    private static final PluginDescriptorLoader LOADER =
            new PluginDescriptorLoader(new ObjectMapper());

    @Test
    void 接受安全插件id(@TempDir Path dir) throws Exception {
        Path jar = pluginJar(dir, "demo-plugin-2");
        assertEquals("demo-plugin-2", LOADER.load(jar).id());
    }

    @Test
    void 拒绝可导致目录穿越的插件id(@TempDir Path dir) throws Exception {
        Path jar = pluginJar(dir, "../../outside");
        assertThrows(IOException.class, () -> LOADER.load(jar));
    }

    @Test
    void 拒绝非规范大小写和首尾短横线(@TempDir Path dir) throws Exception {
        assertThrows(IOException.class, () -> LOADER.load(pluginJar(dir, "Demo")));
        assertThrows(IOException.class, () -> LOADER.load(pluginJar(dir, "-demo")));
        assertThrows(IOException.class, () -> LOADER.load(pluginJar(dir, "demo-")));
    }

    @Test
    void 缺失或空白apiVersion直接拒绝(@TempDir Path dir) throws Exception {
        assertThrows(IOException.class, () -> LOADER.load(
                pluginJar(dir, "legacy-missing", null)));
        assertThrows(IOException.class, () -> LOADER.load(
                pluginJar(dir, "legacy-blank", "")));
    }

    @Test
    void 显式三点零插件保持兼容(@TempDir Path dir) throws Exception {
        var descriptor = LOADER.load(
                pluginJar(dir, "current-plugin", "3.0"));

        assertEquals("3.0", descriptor.apiVersion());
        org.junit.jupiter.api.Assertions.assertTrue(
                PluginManager.isApiCompatible(descriptor.apiVersion()));
    }

    @Test
    void 仓库示例插件描述符始终兼容当前宿主(@TempDir Path dir) throws Exception {
        for (String sample : List.of("hello", "feishu")) {
            Path descriptor = Path.of("sample-plugins", sample, "plugin.json");
            Path jar = Files.createTempFile(dir, sample + "-", ".jar");
            try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
                out.putNextEntry(new JarEntry("plugin.json"));
                Files.copy(descriptor, out);
                out.closeEntry();
            }
            var loaded = LOADER.load(jar);
            org.junit.jupiter.api.Assertions.assertTrue(
                    PluginManager.isApiCompatible(loaded.apiVersion()),
                    sample + " 示例插件 API " + loaded.apiVersion()
                            + " 与宿主 " + com.javaclaw.plugin.api.PluginDescriptor.HOST_API_VERSION
                            + " 不兼容");
        }
    }

    @Test
    void 旧描述符默认作为进程内插件(@TempDir Path dir) throws Exception {
        var descriptor = LOADER.load(pluginJar(dir, "legacy-default"));

        assertEquals(com.javaclaw.plugin.api.PluginDescriptor.PluginType.IN_PROCESS,
                descriptor.pluginType());
    }

    @Test
    void 服务插件从service块读取入口和资源下限(@TempDir Path dir) throws Exception {
        Path jar = descriptorJar(dir, """
                {
                  "id":"model-service",
                  "name":"Model Service",
                  "version":"1.2.0",
                  "pluginType":"SERVICE_PLUGIN",
                  "service":{
                    "mainClass":"example.ModelService",
                    "apiVersion":"1.0",
                    "startupPolicy":"AUTO_START",
                    "resourceHints":{"heapMiB":128,"computeThreads":0},
                    "externalEndpoints":[
                      {"id":"openai","protocol":"HTTP","capabilities":["chat"]}
                    ]
                  }
                }
                """);

        var descriptor = LOADER.load(jar);
        assertEquals(com.javaclaw.plugin.api.PluginDescriptor.PluginType.SERVICE_PLUGIN,
                descriptor.pluginType());
        assertEquals("example.ModelService", descriptor.mainClass());
        assertEquals("1.0", descriptor.apiVersion());
        assertEquals(256, descriptor.service().resourceHints().heapMiB());
        assertEquals(1, descriptor.service().resourceHints().computeThreads());
        assertTrue(descriptor.service().externalEndpoints().getFirst()
                .capabilities().contains("chat"));
    }

    @Test
    void 服务插件缺少service块直接拒绝(@TempDir Path dir) throws Exception {
        Path jar = descriptorJar(dir, """
                {"id":"broken-service","pluginType":"SERVICE_PLUGIN"}
                """);
        assertThrows(IOException.class, () -> LOADER.load(jar));
    }

    @Test
    void Deliverance示例的说明配置和推理能力全部来自唯一描述符(@TempDir Path dir)
            throws Exception {
        Path source = Path.of("sample-plugins/deliverance/src/main/resources/plugin.json");
        Path jar = Files.createTempFile(dir, "deliverance-", ".jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("plugin.json"));
            Files.copy(source, out);
            out.closeEntry();
        }

        var descriptor = LOADER.load(jar);
        assertEquals("builtin-deliverance", descriptor.id());
        assertEquals("0.0.12-7", descriptor.version());
        assertEquals(com.javaclaw.plugin.api.PluginDescriptor.PluginType.SERVICE_PLUGIN,
                descriptor.pluginType());
        assertTrue(descriptor.description().contains("本地对话"));
        assertTrue(descriptor.configurationSchema().contains("maxGenerationResident"));
        assertEquals("deliverance", descriptor.inference().engine());
        assertEquals(1, descriptor.inference().protocolMajor());
        assertEquals(2, descriptor.inference().protocolMinor());
        assertTrue(descriptor.inference().capabilities().contains("terminal_usage"));
        assertTrue(descriptor.service().desktopServices().contains("inference.api-usage"));
        assertEquals(1, descriptor.configurationUi().schemaVersion());
        assertEquals(List.of("models", "service"), descriptor.configurationUi().pages().stream()
                .map(com.javaclaw.plugin.api.PluginDescriptor.ConfigurationPage::id).toList());
        assertEquals(List.of(com.javaclaw.plugin.api.PluginDescriptor.ConfigurationSectionType
                        .INFERENCE_CATALOG),
                descriptor.configurationUi().pages().getFirst().sections().stream()
                        .map(com.javaclaw.plugin.api.PluginDescriptor.ConfigurationSection::type)
                        .toList());
        assertEquals(List.of(
                        com.javaclaw.plugin.api.PluginDescriptor.ConfigurationSectionType.SERVICE_RUNTIME,
                        com.javaclaw.plugin.api.PluginDescriptor.ConfigurationSectionType.INFERENCE_SERVICE),
                descriptor.configurationUi().pages().get(1).sections().stream()
                        .map(com.javaclaw.plugin.api.PluginDescriptor.ConfigurationSection::type)
                        .toList());
        assertTrue(descriptor.configurationSchema().contains("运行时忽略该字段"));
    }

    @Test
    void 接受严格的声明式配置页并保留顺序(@TempDir Path dir) throws Exception {
        var descriptor = LOADER.load(descriptorJar(dir, serviceDescriptor("""
                "configurationSchema":{"type":"object","properties":{
                  "visible":{"type":"string","default":"ok"},
                  "managed":{"type":"string","x-javaclaw-host-managed":true}
                },"additionalProperties":false},
                "configurationUi":{"schemaVersion":1,"pages":[{
                  "id":"general","title":"常规","description":"安全配置",
                  "sections":[
                    {"id":"about","type":"INFO","description":"宿主渲染"},
                    {"id":"form","type":"SCHEMA_FORM","fields":["visible"]},
                    {"id":"endpoint","type":"EXTERNAL_ENDPOINTS"}
                  ]
                }]},
                "service":%s
                """.formatted(serviceBlock(true)))));

        var page = descriptor.configurationUi().pages().getFirst();
        assertEquals("general", page.id());
        assertEquals(List.of("about", "form", "endpoint"), page.sections().stream()
                .map(com.javaclaw.plugin.api.PluginDescriptor.ConfigurationSection::id).toList());
    }

    @Test
    void 未声明配置界面时保留通用页面回退(@TempDir Path dir) throws Exception {
        var descriptor = LOADER.load(descriptorJar(dir,
                serviceDescriptor("\"service\":" + serviceBlock(false))));
        org.junit.jupiter.api.Assertions.assertNull(descriptor.configurationUi());
    }

    @Test
    void 未知配置界面版本和区块类型直接拒绝(@TempDir Path dir) throws Exception {
        assertThrows(IOException.class, () -> LOADER.load(descriptorJar(dir, serviceDescriptor("""
                "configurationUi":{"schemaVersion":2,"pages":[{
                  "id":"general","title":"常规","sections":[
                    {"id":"info","type":"INFO","description":"text"}
                  ]}]},"service":%s
                """.formatted(serviceBlock(false))))));
        assertThrows(IOException.class, () -> LOADER.load(descriptorJar(dir, serviceDescriptor("""
                "configurationUi":{"schemaVersion":1,"pages":[{
                  "id":"general","title":"常规","sections":[
                    {"id":"custom","type":"CUSTOM_FXML"}
                  ]}]},"service":%s
                """.formatted(serviceBlock(false))))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"className", "controller", "fxml", "css", "font", "color",
            "style", "script", "imageUrl", "externalPage"})
    void 配置区块拒绝所有代码和样式注入字段(String field, @TempDir Path dir)
            throws Exception {
        String ui = """
                "configurationUi":{"schemaVersion":1,"pages":[{
                  "id":"general","title":"常规","sections":[
                    {"id":"info","type":"INFO","description":"text","%s":"attack"}
                  ]}]},"service":%s
                """.formatted(field, serviceBlock(false));
        assertThrows(IOException.class,
                () -> LOADER.load(descriptorJar(dir, serviceDescriptor(ui))));
    }

    @Test
    void 表单拒绝隐藏宿主管理和不存在字段(@TempDir Path dir) throws Exception {
        for (String field : List.of("hidden", "managed", "missing")) {
            String value = """
                    "configurationSchema":{"type":"object","properties":{
                      "hidden":{"type":"string","x-javaclaw-hidden":true},
                      "managed":{"type":"string","x-javaclaw-host-managed":true}
                    }},
                    "configurationUi":{"schemaVersion":1,"pages":[{
                      "id":"general","title":"常规","sections":[
                        {"id":"form","type":"SCHEMA_FORM","fields":["%s"]}
                      ]}]},"service":%s
                    """.formatted(field, serviceBlock(false));
            assertThrows(IOException.class,
                    () -> LOADER.load(descriptorJar(dir, serviceDescriptor(value))), field);
        }
    }

    @Test
    void 推理区块严格校验能力依赖(@TempDir Path dir) throws Exception {
        String models = """
                "configurationUi":{"schemaVersion":1,"pages":[{
                  "id":"models","title":"模型","sections":[
                    {"id":"models","type":"INFERENCE_MODELS"}
                  ]}]},"service":%s
                """.formatted(serviceBlock(true));
        assertThrows(IOException.class,
                () -> LOADER.load(descriptorJar(dir, serviceDescriptor(models))));

        String apiWithoutEndpoint = """
                "inference":%s,
                "configurationUi":{"schemaVersion":1,"pages":[{
                  "id":"api","title":"API","sections":[
                    {"id":"api","type":"INFERENCE_API"}
                  ]}]},"service":%s
                """.formatted(inferenceBlock(), serviceBlock(false));
        assertThrows(IOException.class,
                () -> LOADER.load(descriptorJar(dir, serviceDescriptor(apiWithoutEndpoint))));

        String catalogWithoutInference = """
                "configurationUi":{"schemaVersion":1,"pages":[{
                  "id":"models","title":"模型","sections":[
                    {"id":"catalog","type":"INFERENCE_CATALOG"}
                  ]}]},"service":%s
                """.formatted(serviceBlock(true));
        assertThrows(IOException.class,
                () -> LOADER.load(descriptorJar(dir, serviceDescriptor(catalogWithoutInference))));

        String serviceWithoutEndpoint = """
                "inference":%s,
                "configurationUi":{"schemaVersion":1,"pages":[{
                  "id":"service","title":"服务","sections":[
                    {"id":"console","type":"INFERENCE_SERVICE"}
                  ]}]},"service":%s
                """.formatted(inferenceBlock(), serviceBlock(false));
        assertThrows(IOException.class,
                () -> LOADER.load(descriptorJar(dir, serviceDescriptor(serviceWithoutEndpoint))));
    }

    @Test
    void 服务运行区块只允许声明一次(@TempDir Path dir) throws Exception {
        String value = """
                "configurationUi":{"schemaVersion":1,"pages":[
                  {"id":"one","title":"一","sections":[
                    {"id":"runtime-one","type":"SERVICE_RUNTIME"}]},
                  {"id":"two","title":"二","sections":[
                    {"id":"runtime-two","type":"SERVICE_RUNTIME"}]}
                ]},"service":%s
                """.formatted(serviceBlock(false));
        assertThrows(IOException.class,
                () -> LOADER.load(descriptorJar(dir, serviceDescriptor(value))));
    }

    @Test
    void 配置页面和区块数量受限(@TempDir Path dir) throws Exception {
        String pages = java.util.stream.IntStream.range(0, 9).mapToObj(index -> """
                {"id":"page-%d","title":"Page %d","sections":[
                  {"id":"info","type":"INFO","description":"text"}]}
                """.formatted(index, index)).collect(java.util.stream.Collectors.joining(","));
        String value = "\"configurationUi\":{\"schemaVersion\":1,\"pages\":[" + pages
                + "]},\"service\":" + serviceBlock(false);
        assertThrows(IOException.class,
                () -> LOADER.load(descriptorJar(dir, serviceDescriptor(value))));
    }

    private static String serviceDescriptor(String body) {
        return """
                {"id":"declarative-service","name":"Declarative","version":"1.0.0",
                 "pluginType":"SERVICE_PLUGIN",%s}
                """.formatted(body);
    }

    private static String serviceBlock(boolean endpoint) {
        return """
                {"mainClass":"example.Service","apiVersion":"1.0",
                 "externalEndpoints":%s}
                """.formatted(endpoint
                ? "[{\"id\":\"openai\",\"protocol\":\"HTTP\",\"capabilities\":[\"chat\"]}]"
                : "[]");
    }

    private static String inferenceBlock() {
        return """
                {"engine":"test","engineVersion":"1.0","adapterVersion":"1",
                 "protocol":{"major":1,"minor":0},"capabilities":["chat"],
                 "parameterSchema":{"type":"object","properties":{}}}
                """;
    }

    private static Path pluginJar(Path dir, String id) throws Exception {
        return pluginJar(dir, id, "3.0");
    }

    private static Path pluginJar(Path dir, String id, String apiVersion) throws Exception {
        Path jar = Files.createTempFile(dir, "plugin-", ".jar");
        String apiVersionField = apiVersion == null
                ? ""
                : ",\"apiVersion\":" + jsonString(apiVersion);
        String json = """
                {"id":%s,"main":"example.Plugin"%s}
                """.formatted(jsonString(id), apiVersionField);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("plugin.json"));
            out.write(json.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }

    private static Path descriptorJar(Path dir, String descriptor) throws Exception {
        Path jar = Files.createTempFile(dir, "plugin-", ".jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("plugin.json"));
            out.write(descriptor.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }

    private static String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
