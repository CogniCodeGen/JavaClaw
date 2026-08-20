package com.javaclaw.ui.javafx.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService.EndpointConfiguration;
import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService.ResourceConfiguration;
import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService.ServicePluginInfo;
import com.javaclaw.plugin.api.PluginDescriptor.ConfigurationPage;
import com.javaclaw.plugin.api.PluginDescriptor.ConfigurationSection;
import com.javaclaw.plugin.api.PluginDescriptor.ConfigurationSectionType;
import com.javaclaw.inference.api.InferenceModelProfile;
import com.javaclaw.ui.javafx.settings.InferencePluginConfigurationFactory;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Converts validated descriptor metadata into host-owned standard JavaFX components. */
final class ServicePluginDeclarativePageRenderer {
    static final String HOST_RUNTIME_PAGE = "host-runtime";

    private final ObjectMapper json;
    private final InferencePluginConfigurationFactory inference;
    private final Actions actions;

    ServicePluginDeclarativePageRenderer(
            ObjectMapper json,
            InferencePluginConfigurationFactory inference,
            Actions actions) {
        this.json = Objects.requireNonNull(json, "json");
        this.inference = Objects.requireNonNull(inference, "inference");
        this.actions = Objects.requireNonNull(actions, "actions");
    }

    List<Page> pages(ServicePluginInfo plugin) {
        List<Page> result = new ArrayList<>();
        if (plugin.configurationUi() == null) {
            result.add(fallback(plugin));
        } else {
            plugin.configurationUi().pages().forEach(page -> result.add(new Page(
                    page.id(), page.title(), page.description(), page, false)));
        }
        boolean ownsRuntime = result.stream()
                .filter(page -> page.descriptor() != null)
                .flatMap(page -> page.descriptor().sections().stream())
                .anyMatch(section -> section.type() == ConfigurationSectionType.SERVICE_RUNTIME);
        if (!ownsRuntime) {
            result.add(new Page(HOST_RUNTIME_PAGE, "运行与日志",
                    "由 JavaClaw 管理的子进程资源、状态、错误与日志。", null, true));
        }
        return List.copyOf(result);
    }

    RenderedPage render(ServicePluginInfo plugin, Page page) {
        VBox root = new VBox(12);
        root.getStyleClass().add("service-plugin-page-sections");
        List<AutoCloseable> closeables = new ArrayList<>();
        try {
            if (page.runtime()) {
                ServicePluginConfigurationPane pane = new ServicePluginConfigurationPane(
                        plugin, actions::saveResources, actions::runtime);
                closeables.add(pane);
                root.getChildren().add(pane.root());
                return new RenderedPage(root, closeables);
            }
            boolean mergedRuntime = page.descriptor().sections().stream().anyMatch(
                    section -> section.type() == ConfigurationSectionType.INFERENCE_SERVICE)
                    && page.descriptor().sections().stream().anyMatch(
                    section -> section.type() == ConfigurationSectionType.SERVICE_RUNTIME);
            ServicePluginConfigurationPane runtimePane = mergedRuntime
                    ? new ServicePluginConfigurationPane(plugin, actions::saveResources,
                    actions::runtime, ServicePluginConfigurationPane.Mode.SETTINGS_DRAWER)
                    : null;
            if (runtimePane != null) closeables.add(runtimePane);
            for (ConfigurationSection section : page.descriptor().sections()) {
                if (mergedRuntime && section.type() == ConfigurationSectionType.SERVICE_RUNTIME) {
                    continue;
                }
                List<AutoCloseable> sectionCloseables = new ArrayList<>();
                try {
                    Node rendered = section(plugin, section, sectionCloseables, runtimePane);
                    root.getChildren().add(rendered);
                    closeables.addAll(sectionCloseables);
                } catch (RuntimeException failure) {
                    closeOwned(sectionCloseables, failure);
                    root.getChildren().add(failedSection(section, failure));
                    reportFailure("加载插件配置区块失败", failure);
                }
            }
            return new RenderedPage(root, closeables);
        } catch (RuntimeException failure) {
            closeOwned(closeables, failure);
            throw failure;
        }
    }

    private Node section(
            ServicePluginInfo plugin,
            ConfigurationSection section,
            List<AutoCloseable> closeables,
            ServicePluginConfigurationPane runtimePane) {
        VBox wrapper = new VBox(8);
        wrapper.setId("servicePluginSection-" + section.id());
        wrapper.getStyleClass().addAll("jc-card", "service-plugin-standard-section");
        boolean selfDescribing = section.type() == ConfigurationSectionType.INFERENCE_MODELS
                || section.type() == ConfigurationSectionType.INFERENCE_CATALOG
                || section.type() == ConfigurationSectionType.INFERENCE_SERVICE
                || section.type() == ConfigurationSectionType.SERVICE_RUNTIME;
        boolean inferenceWorkbench = section.type() == ConfigurationSectionType.INFERENCE_MODELS
                || section.type() == ConfigurationSectionType.INFERENCE_CATALOG
                || section.type() == ConfigurationSectionType.INFERENCE_SERVICE;
        if (inferenceWorkbench) wrapper.getStyleClass().add("service-plugin-model-workbench");
        if (!selfDescribing && !section.title().isBlank()) {
            wrapper.getChildren().add(title(section.title()));
        }
        if (!selfDescribing && !section.description().isBlank()) {
            wrapper.getChildren().add(hint(section.description()));
        }
        switch (section.type()) {
            case INFO -> { }
            case SCHEMA_FORM -> {
                wrapper.getStyleClass().add("service-plugin-compact-config-section");
                wrapper.setMaxHeight(Region.USE_PREF_SIZE);
                ServicePluginSchemaConfigurationPane pane =
                        new ServicePluginSchemaConfigurationPane(
                                plugin, section.fields(), json, actions::saveConfiguration);
                wrapper.getChildren().add(pane.root());
                closeables.add(pane);
            }
            case EXTERNAL_ENDPOINTS -> {
                ServicePluginEndpointConfigurationPane pane =
                        new ServicePluginEndpointConfigurationPane(
                                plugin, actions::saveEndpoints, actions::rotateKey);
                wrapper.getChildren().add(pane.root());
                closeables.add(pane);
            }
            case INFERENCE_MODELS -> addInference(
                    wrapper, inference.createModels(plugin, actions::runtimeConfigurationChanged,
                            actions::reportFailure), closeables);
            case INFERENCE_API -> addInference(
                    wrapper, inference.createApi(plugin, actions::reportFailure), closeables);
            case INFERENCE_CATALOG -> addInference(wrapper, inference.createCatalog(
                    plugin, actions::runtimeConfigurationChanged, actions::reportFailure), closeables);
            case INFERENCE_SERVICE -> {
                PendingInferenceAsset requested = actions.consumePendingInferenceAsset();
                addInference(wrapper, inference.createService(
                        plugin, actions::runtimeConfigurationChanged, actions::reportFailure,
                        requested == null ? null : requested.assetId(),
                        requested == null ? null : requested.kind(),
                        actions::openInferenceCatalog,
                        new InferencePluginConfigurationFactory.RuntimeControls(
                                runtimePane == null ? new VBox() : runtimePane.root(),
                                () -> actions.runtime(ServicePluginConfigurationPane.RuntimeAction.START),
                                () -> actions.runtime(ServicePluginConfigurationPane.RuntimeAction.STOP),
                                () -> actions.runtime(ServicePluginConfigurationPane.RuntimeAction.RESTART),
                                () -> actions.runtime(
                                        ServicePluginConfigurationPane.RuntimeAction.UNQUARANTINE))), closeables);
            }
            case SERVICE_RUNTIME -> {
                ServicePluginConfigurationPane pane = new ServicePluginConfigurationPane(
                        plugin, actions::saveResources, actions::runtime,
                        ServicePluginConfigurationPane.Mode.EMBEDDED_SERVICE);
                wrapper.getChildren().add(pane.root());
                closeables.add(pane);
            }
        }
        return wrapper;
    }

    private Node failedSection(ConfigurationSection section, Throwable failure) {
        VBox wrapper = new VBox(8);
        wrapper.setId("servicePluginSection-" + section.id());
        wrapper.getStyleClass().addAll("jc-card", "service-plugin-standard-section",
                "service-plugin-error-state");
        wrapper.getChildren().add(title(section.title().isBlank()
                ? "配置区块加载失败" : section.title()));
        Label detail = hint(PluginCenterCleanup.failureMessage(failure));
        detail.setId("servicePluginSectionError-" + section.id());
        detail.getStyleClass().add("service-plugin-error");
        Button retry = new Button("重试加载");
        retry.setId("servicePluginSectionRetry-" + section.id());
        retry.getStyleClass().addAll("jc-btn", "jc-btn-sm");
        retry.setOnAction(ignored -> actions.retryPage());
        wrapper.getChildren().addAll(detail, retry);
        return wrapper;
    }

    private static void closeOwned(List<AutoCloseable> closeables, Throwable primary) {
        for (int index = closeables.size() - 1; index >= 0; index--) {
            try {
                closeables.get(index).close();
            } catch (Exception closeFailure) {
                primary.addSuppressed(closeFailure);
            }
        }
    }

    private void reportFailure(String operation, Throwable failure) {
        try {
            actions.reportFailure(operation, failure);
        } catch (RuntimeException reportingFailure) {
            failure.addSuppressed(reportingFailure);
        }
    }

    private static void addInference(
            VBox wrapper,
            InferencePluginConfigurationFactory.Component component,
            List<AutoCloseable> closeables) {
        wrapper.getChildren().add(component.root());
        closeables.add(component);
        component.activate();
    }

    private Page fallback(ServicePluginInfo plugin) {
        List<ConfigurationSection> sections = new ArrayList<>();
        if (!plugin.configurationSchema().isBlank()) {
            sections.add(new ConfigurationSection("configuration", ConfigurationSectionType.SCHEMA_FORM,
                    "插件配置", "由插件 Schema 声明、由 JavaClaw 安全渲染。", List.of()));
        }
        if (!plugin.endpoints().isEmpty()) {
            sections.add(new ConfigurationSection("endpoints", ConfigurationSectionType.EXTERNAL_ENDPOINTS,
                    "外部接口", "监听、TLS、密钥与限额由宿主保存。", List.of()));
        }
        if (plugin.inference() != null) {
            sections.add(new ConfigurationSection("models", ConfigurationSectionType.INFERENCE_MODELS,
                    "模型", "模型资产、档案参数、实际探测与工作区档位。", List.of()));
            if (!plugin.endpoints().isEmpty()) {
                sections.add(new ConfigurationSection("inference-api", ConfigurationSectionType.INFERENCE_API,
                        "对外接口", "OpenAI 兼容路由、别名、TLS、API Key 与限额。", List.of()));
            }
        }
        if (sections.isEmpty()) {
            sections.add(new ConfigurationSection("information", ConfigurationSectionType.INFO,
                    "插件信息", "此插件没有声明可编辑配置。", List.of()));
        }
        ConfigurationPage descriptor = new ConfigurationPage(
                "configuration", "插件配置", "宿主根据插件能力生成的通用配置页。", sections);
        return new Page(descriptor.id(), descriptor.title(), descriptor.description(), descriptor, false);
    }

    private static Label title(String value) {
        Label label = new Label(value);
        label.getStyleClass().add("settings-group-title");
        return label;
    }

    private static Label hint(String value) {
        Label label = new Label(value);
        label.setWrapText(true);
        label.getStyleClass().add("settings-hint");
        return label;
    }

    record Page(
            String id,
            String title,
            String description,
            ConfigurationPage descriptor,
            boolean runtime) { }

    record RenderedPage(Node root, List<AutoCloseable> closeables) implements AutoCloseable {
        RenderedPage {
            closeables = List.copyOf(closeables);
        }

        @Override
        public void close() {
            RuntimeException failure = null;
            for (int index = closeables.size() - 1; index >= 0; index--) {
                try { closeables.get(index).close(); }
                catch (Exception closeFailure) {
                    RuntimeException wrapped = closeFailure instanceof RuntimeException runtime
                            ? runtime : new IllegalStateException(closeFailure);
                    if (failure == null) failure = wrapped; else failure.addSuppressed(wrapped);
                }
            }
            if (failure != null) throw failure;
        }
    }

    interface Actions {
        void saveResources(ResourceConfiguration resources);
        void saveEndpoints(List<EndpointConfiguration> endpoints);
        void saveConfiguration(Map<String, String> patch);
        void rotateKey(String endpointId);
        void runtime(ServicePluginConfigurationPane.RuntimeAction action);
        void runtimeConfigurationChanged();
        void openInferenceService(UUID assetId, InferenceModelProfile.Kind kind);
        void openInferenceCatalog();
        PendingInferenceAsset consumePendingInferenceAsset();
        void retryPage();
        void reportFailure(String operation, Throwable failure);
    }

    record PendingInferenceAsset(UUID assetId, InferenceModelProfile.Kind kind) { }
}
