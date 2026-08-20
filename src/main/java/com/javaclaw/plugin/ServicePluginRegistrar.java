package com.javaclaw.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.api.interaction.ConfirmKind;
import com.javaclaw.api.interaction.ConfirmRequest;
import com.javaclaw.api.interaction.UserInteractionPort;
import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService.EndpointConfiguration;
import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService.Protocol;
import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService.ResourceConfiguration;
import com.javaclaw.application.serviceplugin.DeliveranceResourceRecommendations;
import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService.StartupPolicy;
import com.javaclaw.infrastructure.serviceplugin.ServicePluginDefinition;
import com.javaclaw.infrastructure.serviceplugin.ServicePluginProcessManager;
import com.javaclaw.plugin.api.PluginDescriptor;
import com.javaclaw.util.AtomicFileWriter;
import com.javaclaw.util.PathGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Signed service-plugin installation and registration boundary. */
final class ServicePluginRegistrar {
    private static final Logger log = LoggerFactory.getLogger(ServicePluginRegistrar.class);
    static final String ALLOW_UNSIGNED_DEVELOPMENT_PROPERTY =
            "javaclaw.service-plugin.allow-unsigned-development";

    private final Path pluginsDirectory;
    private final ServicePluginProcessManager processes;
    private final UserInteractionPort interaction;
    private final PluginDescriptorLoader descriptors;
    private final ObjectMapper json;
    private final ServicePluginContributionRegistry contributions;
    private final ServicePluginJarSignatureVerifier signatures =
            new ServicePluginJarSignatureVerifier();
    private final SecureRandom random = new SecureRandom();

    enum DiscoveryState { REGISTERED, PENDING_APPROVAL }

    ServicePluginRegistrar(
            Path pluginsDirectory,
            ServicePluginProcessManager processes,
            UserInteractionPort interaction,
            PluginDescriptorLoader descriptors,
            ObjectMapper json,
            ServicePluginContributionRegistry contributions) {
        this.pluginsDirectory = pluginsDirectory;
        this.processes = processes;
        this.interaction = interaction;
        this.descriptors = descriptors;
        this.json = json;
        this.contributions = contributions;
    }

    boolean available() {
        return processes != null;
    }

    Path install(Path source, PluginDescriptor descriptor, Path destination) throws Exception {
        if (!available()) {
            log.warn("从文件安装失败：当前宿主未装配服务插件进程管理器");
            return null;
        }
        requireDestination(descriptor, destination);
        ArtifactVerification verified = verifyArtifact(source, isSampleBuild(source));
        PluginDescriptor verifiedDescriptor = descriptors.load(source);
        if (!descriptor.equals(verifiedDescriptor)) {
            throw new SecurityException("服务插件在描述符读取与验签之间发生变化");
        }
        if (!requestGrant(descriptor, verified)) return null;
        Path staging = Files.createTempDirectory(pluginsDirectory, ".service-install-");
        try {
            String artifactName = artifactName(descriptor, source);
            Path stagedJar = staging.resolve(artifactName);
            Files.copy(source, stagedJar);
            ArtifactVerification stagedVerification = verifyArtifact(stagedJar, !verified.signed());
            PluginDescriptor stagedDescriptor = descriptors.load(stagedJar);
            if (!verified.equals(stagedVerification) || !descriptor.equals(stagedDescriptor)) {
                throw new SecurityException("服务插件在校验与复制之间发生变化");
            }
            writeApproval(staging, stagedDescriptor, stagedVerification);
            return Files.exists(destination, LinkOption.NOFOLLOW_LINKS)
                    ? replaceInstalled(staging, destination, stagedJar.getFileName(),
                            stagedDescriptor)
                    : installNew(staging, destination, stagedJar.getFileName(), stagedDescriptor);
        } finally {
            deleteRecursively(staging);
        }
    }

    private Path installNew(
            Path staging, Path destination, Path artifactName,
            PluginDescriptor descriptor) throws Exception {
        atomicMove(staging, destination, false);
        Path installed = destination.resolve(artifactName);
        try {
            if (!discover(descriptor, installed, destination)) {
                throw new IllegalStateException("服务插件安装后未能注册");
            }
            return installed;
        } catch (Exception failure) {
            deleteRecursively(destination);
            throw failure;
        }
    }

    /**
     * Replaces only the top-level artifact and approval record. The plugin-owned data directory is
     * deliberately left in place. Registration is part of the same operation so a failed upgrade
     * can put the old artifact and process definition back before returning to the caller.
     */
    private Path replaceInstalled(
            Path staging, Path destination, Path artifactName,
            PluginDescriptor replacement) throws Exception {
        if (!Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(destination)) {
            throw new IOException("服务插件安装目录不是普通目录");
        }
        Path oldJar = requireSingleTopLevelJar(destination);
        PluginDescriptor previous = descriptors.load(oldJar);
        if (!previous.id().equals(replacement.id())
                || previous.pluginType() != PluginDescriptor.PluginType.SERVICE_PLUGIN) {
            throw new SecurityException("目标目录属于另一个插件，不能原位替换");
        }

        Path backup = Files.createTempDirectory(pluginsDirectory, ".service-backup-");
        Path oldApproval = destination.resolve(".service-plugin-approved");
        Path backupJar = backup.resolve(oldJar.getFileName());
        Path backupApproval = backup.resolve(".service-plugin-approved");
        boolean hadApproval = Files.isRegularFile(oldApproval, LinkOption.NOFOLLOW_LINKS)
                && !Files.isSymbolicLink(oldApproval);
        boolean knownProcess = processes.definition(replacement.id()).isPresent();
        if (knownProcess) processes.stop(replacement.id());
        try {
            atomicMove(oldJar, backupJar, false);
            if (hadApproval) atomicMove(oldApproval, backupApproval, false);
            Path installed = destination.resolve(artifactName);
            atomicMove(staging.resolve(artifactName), installed, true);
            atomicMove(staging.resolve(".service-plugin-approved"), oldApproval, true);
            try {
                if (!discover(replacement, installed, destination)) {
                    throw new IllegalStateException("服务插件升级后未能注册");
                }
                return installed;
            } catch (Exception upgradeFailure) {
                rollbackReplacement(destination, installed, oldApproval, backupJar,
                        backupApproval, hadApproval, previous, upgradeFailure);
                throw upgradeFailure;
            }
        } finally {
            deleteRecursively(backup);
        }
    }

    private void rollbackReplacement(
            Path destination, Path installed, Path approval, Path backupJar,
            Path backupApproval, boolean hadApproval, PluginDescriptor previous,
            Exception original) {
        try {
            Files.deleteIfExists(installed);
            Files.deleteIfExists(approval);
            Path restoredJar = destination.resolve(backupJar.getFileName());
            atomicMove(backupJar, restoredJar, false);
            if (hadApproval) atomicMove(backupApproval, approval, false);
            if (!discover(previous, restoredJar, destination)) {
                throw new IllegalStateException("旧服务插件未能恢复注册");
            }
        } catch (Exception rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    boolean discover(PluginDescriptor descriptor, Path jar, Path pluginDirectory) throws Exception {
        return discover(descriptor, jar, pluginDirectory, true, true, false)
                == DiscoveryState.REGISTERED;
    }

    DiscoveryState discoverPassive(
            PluginDescriptor descriptor, Path jar, Path pluginDirectory) throws Exception {
        return discover(descriptor, jar, pluginDirectory, false, true, false);
    }

    /** 显式批准被动扫描发现的工件；注册成功后也不会在本次操作中启动进程。 */
    boolean approvePending(
            PluginDescriptor descriptor, Path jar, Path pluginDirectory) throws Exception {
        return discover(descriptor, jar, pluginDirectory, true, false, true)
                == DiscoveryState.REGISTERED;
    }

    private DiscoveryState discover(
            PluginDescriptor descriptor, Path jar, Path pluginDirectory,
            boolean requestApproval, boolean activateAutoStart,
            boolean manualOnFirstApproval) throws Exception {
        if (!available()) {
            log.warn("服务插件[{}]已跳过：进程管理器未装配", descriptor.id());
            return DiscoveryState.PENDING_APPROVAL;
        }
        ServiceApproval approval = readApproval(pluginDirectory);
        ArtifactVerification verification = verifyArtifact(jar,
                (approval != null && !approval.signed())
                        || "builtin-deliverance".equals(descriptor.id()));
        PluginDescriptor verifiedDescriptor = descriptors.load(jar);
        if (!descriptor.equals(verifiedDescriptor)) {
            throw new SecurityException("服务插件在描述符读取与验签之间发生变化");
        }
        if (!approved(approval, descriptor, verification)) {
            if (trustedPreinstalledBuiltin(descriptor, pluginDirectory, verification)) {
                writeApproval(pluginDirectory, descriptor, verification);
            } else if (!requestApproval) {
                log.info("服务插件[{}]已验证，等待用户显式批准", descriptor.id());
                return DiscoveryState.PENDING_APPROVAL;
            } else if (!requestGrant(descriptor, verification,
                    manualOnFirstApproval ? "批准服务插件：" : "安装服务插件：")) {
                log.warn("服务插件[{}]尚未获得用户显式批准，已拒绝注册", descriptor.id());
                return DiscoveryState.PENDING_APPROVAL;
            } else {
                writeApproval(pluginDirectory, descriptor, verification);
            }
            approval = readApproval(pluginDirectory);
        }
        if (approval == null
                || !approval.pluginId().equals(descriptor.id())
                || !approval.pluginVersion().equals(descriptor.version())
                || !approval.artifactSha256().equalsIgnoreCase(verification.artifactSha256())
                || !approval.signerKeySha256().equalsIgnoreCase(verification.signerKeySha256())
                || !approval.publisher().equals(verification.publisher())
                || approval.signed() != verification.signed()) {
            log.warn("服务插件[{}]批准记录与当前工件不匹配，已拒绝注册", descriptor.id());
            return DiscoveryState.PENDING_APPROVAL;
        }
        PluginDescriptor.ResourceHints hints = descriptor.service().resourceHints();
        ResourceConfiguration resources = new ResourceConfiguration(hints.heapMiB(),
                hints.nativeMemoryMiB(), hints.computeThreads(), hints.ioConcurrency(),
                hints.fileDescriptors());
        if ("builtin-deliverance".equals(descriptor.id())) {
            resources = DeliveranceResourceRecommendations.balanced(resources);
        }
        List<EndpointConfiguration> endpoints = descriptor.service().externalEndpoints().stream()
                .map(endpoint -> new EndpointConfiguration(endpoint.id(),
                        Protocol.valueOf(endpoint.protocol().name()), "127.0.0.1", 0,
                        endpoint.protocol() == PluginDescriptor.EndpointProtocol.HTTPS,
                        false, null, "", randomApiKey(), 60, 100_000,
                        1, 64, 16L * 1024 * 1024))
                .toList();
        Optional<ServicePluginDefinition> previousDefinition = processes.definition(descriptor.id());
        StartupPolicy startupPolicy = manualOnFirstApproval && previousDefinition.isEmpty()
                ? StartupPolicy.MANUAL
                : StartupPolicy.valueOf(descriptor.service().startupPolicy().name());
        ServicePluginDefinition definition = new ServicePluginDefinition(
                descriptor.id(), descriptor.name(), descriptor.version(),
                descriptor.service().apiVersion(), descriptor.service().mainClass(),
                verification.publisher(), verification.signed(), verification.artifactSha256(), jar,
                pluginDirectory.resolve("data").toAbsolutePath().normalize(),
                startupPolicy, resources,
                endpoints, true, descriptor.service().desktopServices(),
                defaultConfiguration(descriptor),
                "builtin-deliverance".equals(descriptor.id()),
                descriptor.description(), descriptor.configurationSchema(), descriptor.inference(),
                descriptor.configurationUi(), descriptor.service().externalEndpoints().stream()
                        .collect(Collectors.toUnmodifiableMap(
                                PluginDescriptor.ExternalEndpoint::id,
                                PluginDescriptor.ExternalEndpoint::capabilities)),
                !verification.signed());
        processes.register(definition);
        try {
            contributions.register(descriptor, jar, verification.artifactSha256());
        } catch (Exception failure) {
            if (previousDefinition.isPresent()) {
                processes.register(previousDefinition.get());
            } else {
                processes.unregister(descriptor.id());
            }
            throw failure;
        }
        if (activateAutoStart) processes.activateAutoStart(descriptor.id());
        log.info("发现服务插件：{}（{}），未向 Desktop JVM 加载任何插件类",
                descriptor.name(), descriptor.id());
        return DiscoveryState.REGISTERED;
    }

    private Map<String, String> defaultConfiguration(PluginDescriptor descriptor) {
        if (descriptor.configurationSchema().isBlank()) return Map.of();
        try {
            var properties = json.readTree(descriptor.configurationSchema()).path("properties");
            Map<String, String> values = new java.util.LinkedHashMap<>();
            properties.fields().forEachRemaining(entry -> {
                var value = entry.getValue().get("default");
                if (value != null) {
                    values.put(entry.getKey(), value.isContainerNode()
                            ? value.toString() : value.asText());
                }
            });
            return Map.copyOf(values);
        } catch (IOException invalid) {
            throw new IllegalArgumentException("插件配置 Schema 无法读取", invalid);
        }
    }

    void unregister(String pluginId) {
        try {
            contributions.unregister(pluginId);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("注销服务插件静态能力失败", failure);
        }
        if (processes != null) processes.unregister(pluginId);
    }

    private boolean requestGrant(
            PluginDescriptor descriptor,
            ArtifactVerification verification) {
        return requestGrant(descriptor, verification, "安装服务插件：");
    }

    private boolean requestGrant(
            PluginDescriptor descriptor,
            ArtifactVerification verification,
            String titlePrefix) {
        if (interaction == null || !interaction.isAvailable()) {
            log.warn("无交互端口，服务插件[{}]安装已安全拒绝", descriptor.id());
            return false;
        }
        PluginDescriptor.ResourceHints hints = descriptor.service().resourceHints();
        String endpoints = descriptor.service().externalEndpoints().isEmpty() ? "无"
                : descriptor.service().externalEndpoints().stream()
                .map(value -> value.id() + "(" + value.protocol() + ")")
                .collect(Collectors.joining("、"));
        String permissions = descriptor.capabilities().isEmpty() ? "无"
                : descriptor.capabilities().stream().map(Enum::name).sorted()
                .collect(Collectors.joining("、"));
        String action = titlePrefix.startsWith("批准") ? "批准并注册" : "安装";
        String key = verification.signerKeySha256();
        String description = "发布者：" + verification.publisher()
                + (verification.signed()
                ? "\n签名公钥：" + key.substring(0, Math.min(16, key.length())) + "…"
                : "\n⚠ 未签名源码开发构建：仅允许本次手动启动，禁止自动启动")
                + "\n独立进程内存预留：" + hints.reservedMemoryMiB() + " MiB"
                + "\n计算线程：" + hints.computeThreads()
                + "\nDesktop 反向调用权限：" + permissions
                + "\n外部监听声明：" + endpoints
                + (hints.usesNativeCode() ? "\n⚠ 该插件包含 native 代码，崩溃将由独立进程隔离。" : "")
                + "\n\n仅在信任该发布者和上述风险时" + action + "。";
        return interaction.confirm(new ConfirmRequest(
                titlePrefix + descriptor.name(), "服务插件", description,
                ConfirmKind.CONFIRM, 90, "", false));
    }

    private void writeApproval(
            Path directory,
            PluginDescriptor descriptor,
            ArtifactVerification verification) throws IOException {
        AtomicFileWriter.writeJson(json, directory.resolve(".service-plugin-approved").toFile(),
                new ServiceApproval(descriptor.id(), descriptor.version(),
                        verification.artifactSha256(), verification.signerKeySha256(),
                        verification.publisher(), System.currentTimeMillis(), verification.signed()));
    }

    private ServiceApproval readApproval(Path directory) {
        Path file = directory.resolve(".service-plugin-approved");
        try {
            return Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    ? json.readValue(file.toFile(), ServiceApproval.class) : null;
        } catch (Exception failure) {
            log.warn("服务插件批准记录无效 {}：{}", directory.getFileName(), failure.toString());
            return null;
        }
    }

    private String randomApiKey() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return "jcs_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private boolean approved(
            ServiceApproval approval, PluginDescriptor descriptor, ArtifactVerification verification) {
        return approval != null
                && approval.pluginId().equals(descriptor.id())
                && approval.pluginVersion().equals(descriptor.version())
                && approval.artifactSha256().equalsIgnoreCase(verification.artifactSha256())
                && approval.signerKeySha256().equalsIgnoreCase(verification.signerKeySha256())
                && approval.publisher().equals(verification.publisher())
                && approval.signed() == verification.signed();
    }

    private ArtifactVerification verifyArtifact(Path path, boolean allowUnsigned) throws IOException {
        try {
            var value = signatures.verify(path);
            return new ArtifactVerification(value.publisher(), value.signerKeySha256(),
                    value.artifactSha256(), true);
        } catch (IOException failure) {
            if (!allowUnsigned || !Boolean.getBoolean(ALLOW_UNSIGNED_DEVELOPMENT_PROPERTY)
                    || signatures.hasSignatureMetadata(path)) {
                throw failure;
            }
            return new ArtifactVerification("未签名本地开发构建", "development-unsigned",
                    sha256(path), false);
        }
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[1024 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static boolean isSampleBuild(Path path) {
        if (!Boolean.getBoolean(ALLOW_UNSIGNED_DEVELOPMENT_PROPERTY)) return false;
        Path samples = Path.of(System.getProperty("user.dir"), "sample-plugins")
                .toAbsolutePath().normalize();
        return path.toAbsolutePath().normalize().startsWith(samples);
    }

    private void requireDestination(PluginDescriptor descriptor, Path destination) {
        Path normalized = destination.toAbsolutePath().normalize();
        Path expected = pluginsDirectory.resolve(descriptor.id()).toAbsolutePath().normalize();
        if (!normalized.equals(expected) || !PathGuard.isInside(pluginsDirectory, normalized)) {
            throw new SecurityException("服务插件安装目标路径越界");
        }
    }

    private static String artifactName(PluginDescriptor descriptor, Path source) {
        return "builtin-deliverance".equals(descriptor.id())
                ? "deliverance.jar" : source.getFileName().toString();
    }

    private static Path requireSingleTopLevelJar(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            List<Path> jars = files.filter(path -> Files.isRegularFile(
                            path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".jar"))
                    .toList();
            if (jars.size() != 1) {
                throw new IOException("服务插件目录必须且只能包含一个顶层 JAR");
            }
            return jars.getFirst();
        }
    }

    private static void atomicMove(Path source, Path target, boolean replace) throws IOException {
        StandardCopyOption[] atomic = replace
                ? new StandardCopyOption[]{StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING}
                : new StandardCopyOption[]{StandardCopyOption.ATOMIC_MOVE};
        StandardCopyOption[] fallback = replace
                ? new StandardCopyOption[]{StandardCopyOption.REPLACE_EXISTING}
                : new StandardCopyOption[0];
        try {
            Files.move(source, target, atomic);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target, fallback);
        }
    }

    private boolean trustedPreinstalledBuiltin(
            PluginDescriptor descriptor, Path pluginDirectory, ArtifactVerification verification) {
        if (!verification.signed() || !"builtin-deliverance".equals(descriptor.id())) return false;
        Path expected = pluginsDirectory.resolve(descriptor.id()).toAbsolutePath().normalize();
        if (!pluginDirectory.toAbsolutePath().normalize().equals(expected)) return false;
        try (var input = ServicePluginRegistrar.class.getResourceAsStream(
                "/service-plugin/trusted-builtin-signers.txt")) {
            if (input == null) return false;
            String trusted = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.US_ASCII);
            return trusted.lines().map(String::strip)
                    .filter(value -> value.matches("[0-9a-fA-F]{64}"))
                    .anyMatch(value -> value.equalsIgnoreCase(verification.signerKeySha256()));
        } catch (IOException failure) {
            log.warn("读取内置服务插件发布者列表失败: {}", failure.toString());
            return false;
        }
    }

    private static void deleteRecursively(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        try (var files = Files.walk(directory)) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private record ArtifactVerification(
            String publisher, String signerKeySha256, String artifactSha256, boolean signed) { }

    private record ServiceApproval(String pluginId, String pluginVersion,
                                   String artifactSha256, String signerKeySha256,
                                   String publisher, long approvedAt, boolean signed) { }
}
