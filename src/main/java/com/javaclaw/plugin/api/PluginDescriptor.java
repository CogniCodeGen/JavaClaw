package com.javaclaw.plugin.api;

import java.util.List;
import java.util.Set;

/**
 * 插件描述符 —— 对应插件 jar 根目录下的 {@code plugin.json}，是宿主认识一个插件的全部元信息。
 *
 * <p>由 {@code PluginDescriptorLoader} 从 jar 内解析得到。{@link #capabilities()} 即插件声明
 * 需要的能力清单（= 权限申请），宿主据此在运行期装配 {@code PluginContext}。</p>
 *
 * @param id           插件唯一标识（小写短横线，如 {@code feishu-listener}），同时作日志/线程命名前缀
 * @param name         显示名称（中文友好）
 * @param version      插件版本（语义化，如 {@code 1.0.0}）
 * @param apiVersion   插件编译所依赖的 plugin-api 主版本，加载时与宿主比对兼容性
 * @param mainClass    插件入口类全限定名，须实现 {@link JavaClawPlugin}
 * @param description  插件用途简述
 * @param capabilities 声明所需能力集合（空集表示不申请任何宿主能力）
 * @param config       插件自有配置项声明（宿主据此渲染配置表单、加密 secret 项）
 * @param configurationSchema Draft 2020-12 配置 Schema 的规范 JSON；空串表示仅使用旧 config 列表
 * @param inference    可选的本地推理引擎声明
 * @param configurationUi 可选的宿主声明式配置界面；{@code null} 时由宿主生成通用页面
 * @author JavaClaw
 */
public record PluginDescriptor(
        String id,
        String name,
        String version,
        String apiVersion,
        String mainClass,
        String description,
        Set<Capability> capabilities,
        List<ConfigField> config,
        PluginType pluginType,
        Service service,
        String configurationSchema,
        Inference inference,
        ConfigurationUi configurationUi) {

    /** 当前宿主支持的 plugin-api 主版本号；插件 {@link #apiVersion()} 不匹配则拒载 */
    public static final String HOST_API_VERSION = "3.0";

    /** 防御性拷贝 + 空值兜底，保证不可变与非空集合 */
    public PluginDescriptor {
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        config = config == null ? List.of() : List.copyOf(config);
        pluginType = pluginType == null ? PluginType.IN_PROCESS : pluginType;
        configurationSchema = configurationSchema == null ? "" : configurationSchema.strip();
        if (pluginType == PluginType.SERVICE_PLUGIN && service == null) {
            throw new IllegalArgumentException("服务插件缺少 service 配置");
        }
        if (pluginType == PluginType.IN_PROCESS && (mainClass == null || mainClass.isBlank())) {
            throw new IllegalArgumentException("进程内插件缺少入口类");
        }
    }

    /** 保留 3.0 既有构造器，源码插件和测试无需因新增进程类型而重新编译。 */
    public PluginDescriptor(
            String id, String name, String version, String apiVersion, String mainClass,
            String description, Set<Capability> capabilities, List<ConfigField> config) {
        this(id, name, version, apiVersion, mainClass, description, capabilities, config,
                PluginType.IN_PROCESS, null, "", null, null);
    }

    /** 保留服务插件初版构造器，避免目录重构破坏现有宿主测试与适配器。 */
    public PluginDescriptor(
            String id, String name, String version, String apiVersion, String mainClass,
            String description, Set<Capability> capabilities, List<ConfigField> config,
            PluginType pluginType, Service service) {
        this(id, name, version, apiVersion, mainClass, description, capabilities, config,
                pluginType, service, "", null, null);
    }

    /** 保留推理服务插件初版构造器。 */
    public PluginDescriptor(
            String id, String name, String version, String apiVersion, String mainClass,
            String description, Set<Capability> capabilities, List<ConfigField> config,
            PluginType pluginType, Service service, String configurationSchema,
            Inference inference) {
        this(id, name, version, apiVersion, mainClass, description, capabilities, config,
                pluginType, service, configurationSchema, inference, null);
    }

    public enum PluginType {
        IN_PROCESS,
        SERVICE_PLUGIN
    }

    public enum StartupPolicy {
        MANUAL,
        AUTO_START
    }

    public enum EndpointProtocol {
        HTTP,
        HTTPS,
        TCP,
        SSE,
        WEBSOCKET
    }

    /** 服务插件独立进程声明；所有集合均为不可变快照。 */
    public record Service(
            String mainClass,
            String apiVersion,
            StartupPolicy startupPolicy,
            ResourceHints resourceHints,
            List<ExternalEndpoint> externalEndpoints,
            Set<String> desktopServices) {
        public Service {
            if (mainClass == null || mainClass.isBlank()) {
                throw new IllegalArgumentException("服务插件入口类不能为空");
            }
            if (apiVersion == null || apiVersion.isBlank()) {
                throw new IllegalArgumentException("服务插件 API 版本不能为空");
            }
            startupPolicy = startupPolicy == null ? StartupPolicy.MANUAL : startupPolicy;
            resourceHints = resourceHints == null ? ResourceHints.defaults() : resourceHints;
            externalEndpoints = externalEndpoints == null
                    ? List.of() : List.copyOf(externalEndpoints);
            desktopServices = desktopServices == null ? Set.of() : Set.copyOf(desktopServices);
            if (desktopServices.stream().anyMatch(value -> value == null || value.isBlank()
                    || !value.matches("[a-z0-9][a-z0-9./_-]{0,127}"))) {
                throw new IllegalArgumentException("Desktop 服务权限格式无效");
            }
        }

        public Service(
                String mainClass, String apiVersion, StartupPolicy startupPolicy,
                ResourceHints resourceHints, List<ExternalEndpoint> externalEndpoints) {
            this(mainClass, apiVersion, startupPolicy, resourceHints, externalEndpoints, Set.of());
        }
    }

    /** 仅用于准入预留；不是容器级硬隔离。 */
    public record ResourceHints(
            int heapMiB,
            int nativeMemoryMiB,
            int computeThreads,
            int ioConcurrency,
            int fileDescriptors,
            boolean usesNativeCode) {
        public static final int MIN_PROCESS_MEMORY_MIB = 256;
        public static final int MIN_COMPUTE_THREADS = 1;
        public static final int MIN_FILE_DESCRIPTORS = 64;

        public ResourceHints {
            heapMiB = Math.max(MIN_PROCESS_MEMORY_MIB, heapMiB);
            nativeMemoryMiB = Math.max(0, nativeMemoryMiB);
            computeThreads = Math.max(MIN_COMPUTE_THREADS, computeThreads);
            ioConcurrency = Math.max(1, ioConcurrency);
            fileDescriptors = Math.max(MIN_FILE_DESCRIPTORS, fileDescriptors);
        }

        public static ResourceHints defaults() {
            return new ResourceHints(256, 0, 1, 64, 64, false);
        }

        public long reservedMemoryMiB() {
            return Math.addExact((long) heapMiB, nativeMemoryMiB);
        }
    }

    /** 描述符只声明端点能力，地址、端口、TLS 和密钥由宿主持久化配置。 */
    public record ExternalEndpoint(
            String id,
            EndpointProtocol protocol,
            Set<String> capabilities) {
        public ExternalEndpoint {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("外部端点 id 不能为空");
            }
            protocol = protocol == null ? EndpointProtocol.HTTP : protocol;
            capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        }
    }

    /** 服务插件在静态描述符中发布的推理引擎契约。 */
    public record Inference(
            String engine,
            String engineVersion,
            String adapterVersion,
            int protocolMajor,
            int protocolMinor,
            Set<String> capabilities,
            String parameterSchema) {
        public Inference {
            if (engine == null || engine.isBlank()) {
                throw new IllegalArgumentException("推理引擎 id 不能为空");
            }
            if (engineVersion == null || engineVersion.isBlank()) {
                throw new IllegalArgumentException("推理引擎版本不能为空");
            }
            adapterVersion = adapterVersion == null ? "" : adapterVersion.strip();
            if (protocolMajor < 1 || protocolMinor < 0) {
                throw new IllegalArgumentException("推理协议版本无效");
            }
            capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
            parameterSchema = parameterSchema == null ? "" : parameterSchema.strip();
            if (parameterSchema.isEmpty()) {
                throw new IllegalArgumentException("推理参数 Schema 不能为空");
            }
        }
    }

    /**
     * 宿主渲染的声明式配置界面。这里只允许文本、字段引用和标准区块类型，
     * 不接受任何 JavaFX 类、FXML、CSS、脚本或视觉样式声明。
     */
    public record ConfigurationUi(int schemaVersion, List<ConfigurationPage> pages) {
        public ConfigurationUi {
            pages = pages == null ? List.of() : List.copyOf(pages);
        }
    }

    /** 一个按声明顺序展示的配置页面。 */
    public record ConfigurationPage(
            String id,
            String title,
            String description,
            List<ConfigurationSection> sections) {
        public ConfigurationPage {
            description = description == null ? "" : description.strip();
            sections = sections == null ? List.of() : List.copyOf(sections);
        }
    }

    /** 标准配置区块；{@code fields} 仅供 {@link ConfigurationSectionType#SCHEMA_FORM} 使用。 */
    public record ConfigurationSection(
            String id,
            ConfigurationSectionType type,
            String title,
            String description,
            List<String> fields) {
        public ConfigurationSection {
            title = title == null ? "" : title.strip();
            description = description == null ? "" : description.strip();
            fields = fields == null ? List.of() : List.copyOf(fields);
        }
    }

    public enum ConfigurationSectionType {
        INFO,
        SCHEMA_FORM,
        EXTERNAL_ENDPOINTS,
        INFERENCE_MODELS,
        INFERENCE_API,
        INFERENCE_CATALOG,
        INFERENCE_SERVICE,
        SERVICE_RUNTIME
    }

    /**
     * 插件自有配置项声明。
     *
     * @param key    配置键
     * @param label  中文标签（UI 显示）
     * @param secret 是否敏感（true 则宿主加密存储、UI 以密码框呈现）
     */
    public record ConfigField(String key, String label, boolean secret) {
    }
}
