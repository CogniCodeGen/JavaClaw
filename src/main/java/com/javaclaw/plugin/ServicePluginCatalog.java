package com.javaclaw.plugin;

import com.javaclaw.plugin.api.PluginDescriptor;
import com.javaclaw.util.PathGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tracks registered and approval-pending service-plugin artifacts without loading their classes. */
final class ServicePluginCatalog {
    private static final Logger log = LoggerFactory.getLogger(ServicePluginCatalog.class);

    private final Path pluginsDirectory;
    private final PluginDescriptorLoader descriptors;
    private final ServicePluginRegistrar registrar;
    private final Map<String, ServiceArtifact> registered = new LinkedHashMap<>();
    private final Map<String, ServiceArtifact> pending = new LinkedHashMap<>();

    ServicePluginCatalog(
            Path pluginsDirectory,
            PluginDescriptorLoader descriptors,
            ServicePluginRegistrar registrar) {
        this.pluginsDirectory = pluginsDirectory;
        this.descriptors = descriptors;
        this.registrar = registrar;
    }

    synchronized boolean contains(String pluginId) {
        return pluginId != null && (registered.containsKey(pluginId) || pending.containsKey(pluginId));
    }

    synchronized List<PluginInfo> pendingPlugins() {
        List<PluginInfo> result = new ArrayList<>(pending.size());
        pending.values().stream().map(ServicePluginCatalog::pendingInfo).forEach(result::add);
        return List.copyOf(result);
    }

    /** Revalidates the current artifact and moves it only after interactive registration succeeds. */
    synchronized boolean approve(String pluginId) {
        ServiceArtifact candidate = pending.get(pluginId);
        if (candidate == null) {
            if (registered.containsKey(pluginId)) return true;
            throw new IllegalArgumentException("未找到待批准服务插件：" + pluginId);
        }
        if (!registrar.available()) throw new IllegalStateException("服务插件进程管理器未装配");

        Path jar = candidate.jarPath().toAbsolutePath().normalize();
        Path directory = jar.getParent();
        Path expected = pluginsDirectory.resolve(pluginId).toAbsolutePath().normalize();
        if (directory == null || !directory.equals(expected)
                || !PathGuard.isInside(pluginsDirectory, directory)
                || Files.isSymbolicLink(jar)
                || !Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS)) {
            throw new SecurityException("待批准服务插件路径无效：" + pluginId);
        }
        try {
            PluginDescriptor current = descriptors.load(jar);
            if (!pluginId.equals(current.id())
                    || current.pluginType() != PluginDescriptor.PluginType.SERVICE_PLUGIN) {
                throw new SecurityException("待批准工件的插件身份已变化：" + pluginId);
            }
            if (!PluginApiCompatibility.isCompatible(current)) {
                throw new IllegalStateException("服务插件 API 与当前宿主不兼容：" + pluginId);
            }
            if (!registrar.approvePending(current, jar, directory)) return false;
            registered.put(pluginId, new ServiceArtifact(jar, current));
            pending.remove(pluginId, candidate);
            return true;
        } catch (RuntimeException failure) {
            log.error("服务插件[{}]批准或注册失败：{}", pluginId, failure.toString(), failure);
            throw failure;
        } catch (Exception failure) {
            log.error("服务插件[{}]批准或注册失败：{}", pluginId, failure.toString(), failure);
            throw new IllegalStateException("服务插件批准或注册失败：" + pluginId, failure);
        }
    }

    synchronized Path install(
            Path source, PluginDescriptor descriptor, Path destination) throws Exception {
        Path installed = registrar.install(source, descriptor, destination);
        if (installed != null) {
            registered.put(descriptor.id(), new ServiceArtifact(installed, descriptor));
            pending.remove(descriptor.id());
        }
        return installed;
    }

    synchronized void discoverPassive(
            PluginDescriptor descriptor, Path jar, Path directory) throws Exception {
        ServiceArtifact artifact = new ServiceArtifact(jar, descriptor);
        ServicePluginRegistrar.DiscoveryState state =
                registrar.discoverPassive(descriptor, jar, directory);
        if (state == ServicePluginRegistrar.DiscoveryState.REGISTERED) {
            registered.put(descriptor.id(), artifact);
            pending.remove(descriptor.id());
        } else {
            pending.put(descriptor.id(), artifact);
            registered.remove(descriptor.id());
        }
    }

    synchronized UninstallResult uninstall(String pluginId) {
        ServiceArtifact awaitingApproval = pending.get(pluginId);
        if (awaitingApproval != null) return uninstallPending(pluginId, awaitingApproval);
        ServiceArtifact service = registered.get(pluginId);
        if (service == null) return UninstallResult.NOT_SERVICE;
        return uninstallRegistered(pluginId, service);
    }

    private UninstallResult uninstallPending(String pluginId, ServiceArtifact artifact) {
        Path directory = artifact.jarPath().getParent();
        if (!PathGuard.isInside(pluginsDirectory, directory)) return UninstallResult.FAILED;
        try {
            deleteRecursively(directory);
            pending.remove(pluginId, artifact);
            return UninstallResult.SUCCEEDED;
        } catch (IOException failure) {
            log.error("删除待批准服务插件[{}]失败：{}", pluginId, failure.toString(), failure);
            return UninstallResult.FAILED;
        }
    }

    private UninstallResult uninstallRegistered(String pluginId, ServiceArtifact artifact) {
        Path directory = artifact.jarPath().getParent();
        if (!PathGuard.isInside(pluginsDirectory, directory)) return UninstallResult.FAILED;
        try {
            registrar.unregister(pluginId);
            deleteRecursively(directory);
            registered.remove(pluginId, artifact);
            return UninstallResult.SUCCEEDED;
        } catch (Exception failure) {
            log.error("卸载服务插件[{}]失败：{}", pluginId, failure.toString(), failure);
            restoreRegistration(pluginId, artifact, directory, failure);
            return UninstallResult.FAILED;
        }
    }

    private void restoreRegistration(
            String pluginId, ServiceArtifact artifact, Path directory, Exception original) {
        if (!Files.isRegularFile(artifact.jarPath())) return;
        try {
            registrar.discover(artifact.descriptor(), artifact.jarPath(), directory);
        } catch (Exception rollbackFailure) {
            original.addSuppressed(rollbackFailure);
            log.error("恢复服务插件[{}]注册失败：{}",
                    pluginId, rollbackFailure.toString(), rollbackFailure);
        }
    }

    synchronized void pruneMissing() {
        List<String> missing = registered.entrySet().stream()
                .filter(entry -> !Files.exists(entry.getValue().jarPath()))
                .map(Map.Entry::getKey).toList();
        for (String pluginId : missing) {
            try {
                registrar.unregister(pluginId);
                registered.remove(pluginId);
            } catch (RuntimeException failure) {
                log.warn("移除已删除服务插件[{}]失败：{}", pluginId, failure.toString());
            }
        }
        pending.entrySet().removeIf(entry -> !Files.exists(entry.getValue().jarPath()));
    }

    synchronized int registeredCount() {
        return registered.size();
    }

    synchronized int pendingCount() {
        return pending.size();
    }

    private static PluginInfo pendingInfo(ServiceArtifact artifact) {
        PluginDescriptor descriptor = artifact.descriptor();
        return new PluginInfo(descriptor.id(), descriptor.name(), descriptor.version(),
                descriptor.description(), descriptor.capabilities(), java.util.Set.of(),
                descriptor.config(), List.of(), List.of(), PluginState.PENDING_APPROVAL,
                "服务插件已验证，等待用户批准并注册");
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    enum UninstallResult { NOT_SERVICE, SUCCEEDED, FAILED }

    private record ServiceArtifact(Path jarPath, PluginDescriptor descriptor) { }
}
