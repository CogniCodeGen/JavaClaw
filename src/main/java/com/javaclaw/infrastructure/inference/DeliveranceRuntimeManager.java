package com.javaclaw.infrastructure.inference;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.application.inference.InferenceCatalogPort;
import com.javaclaw.application.inference.InferenceRuntimePort;
import com.javaclaw.framework.spi.JsonSchemaValidator;
import com.javaclaw.inference.api.InferenceModelProfile;
import com.javaclaw.inference.api.InferenceRuntimeManifest;
import com.javaclaw.infrastructure.inference.serviceplugin.DeliveranceServicePluginGateway;
import com.javaclaw.platform.data.DataRoot;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.plugin.ServicePluginContributionRegistry;
import com.javaclaw.plugin.api.PluginDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Bridges a verified Deliverance service-plugin descriptor into JavaClaw's existing inference
 * catalog. Deliverance upgrades are plugin JAR upgrades; this class never installs a second
 * runtime archive and never loads or executes plugin classes in Desktop.
 */
public final class DeliveranceRuntimeManager
        implements InferenceRuntimePort, ServicePluginContributionRegistry {

    /** Stable ID preserves existing model profiles and workspace bindings across plugin upgrades. */
    public static final String BUILTIN_RUNTIME_ID = "deliverance-0.0.12-service-plugin-2";
    private static final Logger log = LoggerFactory.getLogger(DeliveranceRuntimeManager.class);
    private static final InferenceRuntimeManifest.ProtocolVersion HOST_PROTOCOL =
            new InferenceRuntimeManifest.ProtocolVersion(1, 2);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
    private static final JsonSchemaValidator SCHEMAS = new JsonSchemaValidator();
    private static final Set<String> GENERATION_MODEL_TYPES = Set.of(
            "llama", "qwen2", "qwen3", "qwen3_moe", "gemma2", "gemma3_text",
            "gemma4", "mistral", "mixtral", "gpt2", "granitemoehybrid");
    private static final Set<String> EMBEDDING_MODEL_TYPES = Set.of("bert");

    private final DeliverancePluginLayout pluginLayout;
    private final InferenceCatalogPort catalog;
    private final RuntimeController runtimeController;
    private final ObjectMapper json;

    public DeliveranceRuntimeManager(
            DataRoot dataRoot, InferenceCatalogPort catalog,
            DeliveranceServicePluginGateway gateway, ManagedTaskExecutor tasks, ObjectMapper json) {
        this(dataRoot, DeliverancePluginLayout.production(), catalog,
                new ServicePluginController(gateway), tasks, json);
    }

    /** Test seam retained while the catalog fixtures migrate away from runtime ZIPs. */
    DeliveranceRuntimeManager(
            DataRoot dataRoot, InferenceCatalogPort catalog,
            ManagedTaskExecutor tasks, ObjectMapper json, PublicKey ignoredLegacySigningKey) {
        this(dataRoot, new DeliverancePluginLayout(dataRoot.path().resolveSibling("plugins")),
                catalog, new TestRuntimeController(), tasks, json);
    }

    private DeliveranceRuntimeManager(
            DataRoot dataRoot, DeliverancePluginLayout pluginLayout, InferenceCatalogPort catalog,
            RuntimeController runtimeController, ManagedTaskExecutor tasks, ObjectMapper json) {
        java.util.Objects.requireNonNull(dataRoot, "dataRoot");
        java.util.Objects.requireNonNull(tasks, "tasks");
        this.pluginLayout = java.util.Objects.requireNonNull(pluginLayout, "pluginLayout");
        this.catalog = java.util.Objects.requireNonNull(catalog, "catalog");
        this.runtimeController = java.util.Objects.requireNonNull(runtimeController, "runtimeController");
        this.json = java.util.Objects.requireNonNull(json, "json");
    }

    /** Only prepares/migrates the canonical plugin directory; discovery owns registration. */
    public void init() {
        try {
            pluginLayout.prepare();
        } catch (Exception failure) {
            log.warn("Deliverance 插件目录准备失败，本地推理保持禁用: {}", safeMessage(failure));
        }
    }

    /** Called after the plugin scanner has verified the artifact and registered its process metadata. */
    @Override
    public synchronized void register(
            PluginDescriptor descriptor, Path pluginJar, String artifactSha256) throws Exception {
        PluginDescriptor.Inference inference = descriptor.inference();
        if (inference == null) return;
        if (!"deliverance".equalsIgnoreCase(inference.engine())) {
            // Future engines are handled by additional registries in a composite, not by guessing here.
            return;
        }
        if (!DeliveranceServicePluginGateway.PLUGIN_ID.equals(descriptor.id())) {
            throw new IllegalArgumentException("Deliverance 推理声明只能由内置 Deliverance 插件提供");
        }
        Path jar = pluginJar.toAbsolutePath().normalize();
        pluginLayout.requireCanonicalJar(jar);
        if (!Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(jar)) {
            throw new IOException("Deliverance 插件 JAR 不是普通文件");
        }
        String actualHash = sha256(jar);
        if (!actualHash.equalsIgnoreCase(artifactSha256)) {
            throw new SecurityException("Deliverance 插件在签名校验后发生变化");
        }
        Map<String, Object> parameterSchema = json.readValue(inference.parameterSchema(), MAP);
        InferenceRuntimeManifest manifest = new InferenceRuntimeManifest(
                BUILTIN_RUNTIME_ID, inference.engine(), inference.engineVersion(),
                inference.adapterVersion(),
                new InferenceRuntimeManifest.ProtocolVersion(
                        inference.protocolMajor(), inference.protocolMinor()),
                platform(), architecture(), 25, inference.capabilities(), parameterSchema,
                List.of(new InferenceRuntimeManifest.RuntimeFile(
                        "deliverance.jar", actualHash, Files.size(jar))), true);
        validateManifest(manifest);

        Optional<InferenceCatalogPort.RuntimeInstallation> previous = catalog.runtime(BUILTIN_RUNTIME_ID);
        Instant installedAt = previous.map(InferenceCatalogPort.RuntimeInstallation::installedAt)
                .orElseGet(Instant::now);
        var installed = new InferenceCatalogPort.RuntimeInstallation(
                manifest, jar.toString(), InferenceCatalogPort.RuntimeState.INSTALLED,
                false, installedAt);
        try {
            catalog.saveRuntime(installed);
            catalog.setActiveRuntime(inference.engine(), BUILTIN_RUNTIME_ID);
            var active = catalog.runtime(BUILTIN_RUNTIME_ID).orElseThrow();
            runtimeController.registerRuntime(active);
            runtimeController.restoreConfiguredServices();
            log.info("已从签名 plugin.json 注册 Deliverance 推理能力: plugin={}, runtime={}, sha256={}",
                    descriptor.version(), BUILTIN_RUNTIME_ID, actualHash);
        } catch (Exception failure) {
            restorePrevious(previous, failure);
            throw failure;
        }
    }

    @Override
    public synchronized void unregister(String pluginId) throws Exception {
        if (!DeliveranceServicePluginGateway.PLUGIN_ID.equals(pluginId)) return;
        boolean referenced = catalog.profiles().stream()
                .anyMatch(profile -> BUILTIN_RUNTIME_ID.equals(profile.runtimeId()));
        if (referenced) {
            throw new IllegalStateException("Deliverance 仍被模型档案引用，不能卸载插件");
        }
        runtimeController.clearRuntime();
        if (catalog.runtime(BUILTIN_RUNTIME_ID).isPresent()) {
            catalog.deleteRuntime(BUILTIN_RUNTIME_ID);
        }
    }

    private void restorePrevious(
            Optional<InferenceCatalogPort.RuntimeInstallation> previous, Exception original) {
        try {
            if (previous.isPresent()) {
                catalog.saveRuntime(previous.get());
                if (previous.get().active()) {
                    catalog.setActiveRuntime(previous.get().manifest().engine(),
                            previous.get().manifest().runtimeId());
                    runtimeController.registerRuntime(previous.get());
                }
            } else if (catalog.runtime(BUILTIN_RUNTIME_ID).isPresent()
                    && catalog.profiles().stream().noneMatch(
                            profile -> BUILTIN_RUNTIME_ID.equals(profile.runtimeId()))) {
                catalog.deleteRuntime(BUILTIN_RUNTIME_ID);
            }
        } catch (Exception rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    @Override
    public void validateProfile(InferenceModelProfile profile) {
        validateDraft(profile.kind(), profile.runtimeId(), profile.loadParameters(),
                profile.defaultParameters(), false);
    }

    @Override
    public Set<String> supportedModelTypes(
            InferenceRuntimeManifest manifest, InferenceModelProfile.Kind kind) {
        Set<String> declared = InferenceRuntimePort.super.supportedModelTypes(manifest, kind);
        if (!declared.isEmpty()) return declared;
        if (manifest != null && "deliverance".equalsIgnoreCase(manifest.engine())
                && "0.0.12".equals(manifest.engineVersion())) {
            return kind == InferenceModelProfile.Kind.EMBEDDING
                    ? EMBEDDING_MODEL_TYPES : GENERATION_MODEL_TYPES;
        }
        return Set.of();
    }

    private InferenceCatalogPort.RuntimeInstallation validateDraft(
            InferenceModelProfile.Kind kind, String runtimeId, Map<String, Object> loadParameters,
            Map<String, Object> defaultParameters, boolean requireProbeProtocol) {
        var runtime = catalog.runtime(runtimeId)
                .orElseThrow(() -> new IllegalArgumentException("档案引用的推理插件不存在"));
        if (runtime.state() == InferenceCatalogPort.RuntimeState.STAGED
                || runtime.state() == InferenceCatalogPort.RuntimeState.FAILED) {
            throw new IllegalArgumentException("档案引用的推理插件尚不可用");
        }
        if (requireProbeProtocol && runtime.manifest().protocol().minor() < 1) {
            throw new IllegalStateException("自动能力探测需要 inference protocol 1.1 插件");
        }
        String capability = kind == InferenceModelProfile.Kind.EMBEDDING ? "embeddings" : "chat";
        if (!runtime.manifest().capabilities().contains(capability)) {
            throw new IllegalArgumentException("推理插件不支持该模型类型: " + capability);
        }
        var parameters = json.createObjectNode();
        parameters.set("load", json.valueToTree(loadParameters));
        parameters.set("generation", json.valueToTree(defaultParameters));
        var issues = SCHEMAS.validate(
                json.valueToTree(runtime.manifest().parameterSchema()), parameters, "/parameters");
        if (!issues.isEmpty()) {
            var first = issues.getFirst();
            throw new IllegalArgumentException("推理参数不符合插件 Schema: "
                    + first.path() + " " + first.message());
        }
        return runtime;
    }

    @Override public void start(InferenceModelProfile profile) throws Exception {
        runtimeController.start(profile);
    }
    @Override public void stop(UUID profileId) { runtimeController.stop(profileId); }
    @Override public Optional<RuntimeProfileStatus> status(UUID profileId) {
        return runtimeController.status(profileId);
    }

    @Override
    public ProfileProbeResult probeDraft(
            ProfileProbeCommand command, BooleanSupplier cancelled) throws Exception {
        requireActive(cancelled);
        validateDraft(command.kind(), command.runtimeId(), command.loadParameters(),
                command.defaultParameters(), true);
        ProfileProbeResult result = runtimeController.probeDraft(command, cancelled);
        requireActive(cancelled);
        return result;
    }

    @Override public List<String> recentLogs(UUID profileId, int maxLines) {
        return runtimeController.recentLogs(profileId, maxLines);
    }

    private void validateManifest(InferenceRuntimeManifest manifest) {
        if (!"deliverance".equals(manifest.engine())) {
            throw new IllegalArgumentException("推理插件引擎不是 Deliverance");
        }
        if (!manifest.protocol().compatibleWith(HOST_PROTOCOL)) {
            throw new IllegalArgumentException("推理插件协议不兼容");
        }
        if (Runtime.version().feature() < manifest.minimumJavaVersion()) {
            throw new IllegalArgumentException("当前 Java 版本低于推理插件要求");
        }
        if (!manifest.capabilities().containsAll(
                Set.of("exact_usage", "terminal_usage", "cancellation", "service_plugin"))) {
            throw new IllegalArgumentException("推理插件缺少必需能力");
        }
        SCHEMAS.requireValidSchema(json.valueToTree(manifest.parameterSchema()),
                "inference plugin " + manifest.runtimeId());
        if (manifest.files().size() != 1
                || !"deliverance.jar".equals(manifest.files().getFirst().path())) {
            throw new IllegalArgumentException("Deliverance 必须是单个自包含插件 JAR");
        }
    }

    private void verifyFiles(InferenceCatalogPort.RuntimeInstallation runtime) throws IOException {
        Path jar = runtimeJar(runtime);
        var expected = runtime.manifest().files().getFirst();
        pluginLayout.requireCanonicalJar(jar);
        if (!Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(jar)
                || Files.size(jar) != expected.size()
                || !sha256(jar).equalsIgnoreCase(expected.sha256())) {
            throw new IOException("已安装 Deliverance 插件完整性校验失败");
        }
    }

    private static Path runtimeJar(InferenceCatalogPort.RuntimeInstallation runtime) {
        Path root = Path.of(runtime.installPath()).toAbsolutePath().normalize();
        return Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                ? root.resolve("deliverance.jar") : root;
    }

    private static String sha256(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) if (read > 0) digest.update(buffer, 0, read);
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String platform() {
        String value = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (value.contains("mac")) return "macos";
        if (value.contains("win")) return "windows";
        if (value.contains("linux")) return "linux";
        return value.replaceAll("[^a-z0-9]", "");
    }

    private static String architecture() {
        return switch (System.getProperty("os.arch", "").toLowerCase(Locale.ROOT)) {
            case "aarch64", "arm64" -> "arm64";
            case "amd64", "x86_64", "x64" -> "x64";
            default -> System.getProperty("os.arch", "").toLowerCase(Locale.ROOT)
                    .replaceAll("[^a-z0-9]", "");
        };
    }

    private static void requireActive(BooleanSupplier cancelled) throws InterruptedException {
        if (Thread.currentThread().isInterrupted() || (cancelled != null && cancelled.getAsBoolean())) {
            throw new InterruptedException("模型探测已取消");
        }
    }

    private static String safeMessage(Throwable failure) {
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }

    private interface RuntimeController {
        default void registerRuntime(InferenceCatalogPort.RuntimeInstallation runtime) { }
        default void clearRuntime() { }
        default void restoreConfiguredServices() { }
        void start(InferenceModelProfile profile) throws Exception;
        void stop(UUID profileId);
        Optional<RuntimeProfileStatus> status(UUID profileId);
        ProfileProbeResult probeDraft(ProfileProbeCommand command, BooleanSupplier cancelled)
                throws Exception;
        List<String> recentLogs(UUID profileId, int maxLines);
    }

    private record ServicePluginController(DeliveranceServicePluginGateway gateway)
            implements RuntimeController {
        @Override public void registerRuntime(InferenceCatalogPort.RuntimeInstallation runtime) {
            if (!runtime.manifest().capabilities().contains("service_plugin")) {
                throw new IllegalArgumentException("Deliverance 运行时不是服务插件贡献");
            }
            gateway.registerRuntime(runtime);
        }
        @Override public void clearRuntime() { gateway.unregisterRuntime(); }
        @Override public void restoreConfiguredServices() {
            try { gateway.syncPublishedModels(); }
            catch (Exception failure) {
                throw new IllegalStateException("无法恢复 Deliverance 模型目录", failure);
            }
        }
        @Override public void start(InferenceModelProfile profile) throws Exception { gateway.start(profile); }
        @Override public void stop(UUID profileId) { gateway.stop(profileId); }
        @Override public Optional<RuntimeProfileStatus> status(UUID profileId) { return gateway.status(profileId); }
        @Override public ProfileProbeResult probeDraft(
                ProfileProbeCommand command, BooleanSupplier cancelled) throws Exception {
            return gateway.probeDraft(command, cancelled);
        }
        @Override public List<String> recentLogs(UUID profileId, int maxLines) {
            return gateway.recentLogs(profileId, maxLines);
        }
    }

    private static final class TestRuntimeController implements RuntimeController {
        @Override public void start(InferenceModelProfile profile) {
            throw new UnsupportedOperationException("测试控制器不执行模型");
        }
        @Override public void stop(UUID profileId) { }
        @Override public Optional<RuntimeProfileStatus> status(UUID profileId) { return Optional.empty(); }
        @Override public ProfileProbeResult probeDraft(
                ProfileProbeCommand command, BooleanSupplier cancelled) {
            throw new UnsupportedOperationException("测试控制器不执行探测");
        }
        @Override public List<String> recentLogs(UUID profileId, int maxLines) { return List.of(); }
    }
}
