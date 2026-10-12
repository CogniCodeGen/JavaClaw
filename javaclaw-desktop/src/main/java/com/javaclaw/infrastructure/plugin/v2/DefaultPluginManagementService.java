package com.javaclaw.infrastructure.plugin.v2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.application.plugin.v2.PluginManagementService;
import com.javaclaw.config.CredentialCipher;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.platform.execution.TriggerHandle;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** 插件包、工作区配置与进程租约的统一应用门面。 */
public final class DefaultPluginManagementService implements PluginManagementService {
    private final Path artifactsRoot;
    private final Path stagingRoot;
    private final ObjectMapper json;
    private final ManagedTaskExecutor tasks;
    private final PluginStateStore store;
    private final PluginPackageVerifier verifier;
    private final PluginHostServiceRegistry hostServices = new PluginHostServiceRegistry();
    private final PluginProcessSupervisor processes;
    private final Map<String, PluginPackageVerifier.VerifiedPackage> previews = new ConcurrentHashMap<>();
    private final Map<String, Installed> installed = new HashMap<>();
    private final Map<String, Installed> artifactCache = new HashMap<>();
    private final Map<Key, PluginProcessSupervisor.Session> current = new HashMap<>();
    private final Map<String, PluginProcessSupervisor.Session> instances = new HashMap<>();
    private final Set<String> activeWorkspaces = new HashSet<>();
    private final Set<String> uninstalling = new HashSet<>();
    private final Set<String> uninstallCleanupComplete = new HashSet<>();
    private final Map<String, String> uninstallErrors = new HashMap<>();
    private final Map<Key, PendingLaunch> transitions = new HashMap<>();
    private final Set<Key> manuallyStopped = new HashSet<>();
    private final Map<Key, ArrayDeque<Instant>> failures = new HashMap<>();
    private final Map<Key, String> lastErrors = new HashMap<>();
    private final CopyOnWriteArrayList<Consumer<PluginStatusEvent>> observers = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<PluginSecurityEvent>> securityObservers = new CopyOnWriteArrayList<>();
    private final TriggerHandle heartbeat;
    private boolean closed;

    public DefaultPluginManagementService(Path artifactsRoot, Path workspaceRoot, Path stagingRoot,
                                          List<Path> runnerClasspath, ManagedTaskExecutor tasks,
                                          ObjectMapper json, CredentialCipher credentials, HttpClient http) {
        this.artifactsRoot = artifactsRoot.toAbsolutePath().normalize(); this.stagingRoot = stagingRoot.toAbsolutePath().normalize();
        this.tasks = tasks; this.json = json; this.store = new PluginStateStore(workspaceRoot, json, credentials);
        try {
            PluginPaths.directory(this.artifactsRoot, this.artifactsRoot);
            verifier = new PluginPackageVerifier(this.stagingRoot, json, http);
            recoverArtifacts();
        } catch (Exception failure) { throw new PluginException("PLUGIN_STORAGE_FAILED", "插件存储无法初始化", failure); }
        processes = new PluginProcessSupervisor(json, tasks, runnerClasspath, hostServices, this::processExited, this::backgroundReleased);
        heartbeat = tasks.scheduleTriggerAtFixedRate(Duration.ofSeconds(5), Duration.ofSeconds(5), () -> {
            try { tasks.submit(TaskSpec.io("plugin-health"), context -> { checkHealth(); return null; }); }
            catch (RuntimeException ignored) { /* 执行器关闭后不再提交健康检查，由统一关闭路径回收进程。 */ }
        });
    }

    public PluginHostServiceRegistry hostServices() { return hostServices; }

    /** 发行版与开发环境都只使用独立 Runner 工件。 */
    public static List<Path> discoverRunnerClasspath(Path applicationHome) {
        String override = System.getProperty("javaclaw.plugin.runner.classpath", "").strip();
        if (!override.isEmpty()) return java.util.Arrays.stream(override.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)))
                .filter(value -> !value.isBlank()).map(value -> Path.of(value).toAbsolutePath().normalize()).toList();
        Path development = applicationHome.resolve("plugin-runner-java/target");
        if (Files.isDirectory(development)) {
            try (var files = Files.list(development)) {
                List<Path> jars = files.filter(path -> path.getFileName().toString().endsWith("-all.jar")).sorted().toList();
                if (!jars.isEmpty()) return List.of(jars.getLast());
            } catch (IOException ignored) { /* 开发工件不可读时继续检查发行版独立 Runner。 */ }
        }
        Path release = applicationHome.resolve("runtime/plugin-runner.jar");
        return Files.isRegularFile(release) ? List.of(release) : List.of();
    }

    @Override public synchronized List<PluginSummary> list(String workspaceId) {
        requireWorkspace(workspaceId);
        return installed.values().stream().map(value -> details(workspaceId, value.manifest.id()).summary())
                .sorted(Comparator.comparing(PluginSummary::name).thenComparing(PluginSummary::id)).toList();
    }

    @Override public synchronized PluginDetails details(String workspaceId, String pluginId) {
        requireWorkspace(workspaceId); Binding binding = bound(workspaceId, pluginId);
        Installed artifact = binding.artifact; PluginStateStore.StoredConfiguration config = binding.configuration;
        PluginStateStore.Preferences preferences = store.preferences(workspaceId, pluginId);
        PluginProcessSupervisor.Session session = visibleSession(new Key(workspaceId, pluginId));
        String lastError = session == null ? lastErrors.getOrDefault(new Key(workspaceId, pluginId), "") : session.lastError;
        String uninstallError = uninstallErrors.get(pluginId);
        if (uninstallError != null) lastError = uninstallError;
        State state = preferences.revoked() ? State.REVOKED : preferences.quarantined() ? State.QUARANTINED
                : session == null ? lastError.isEmpty() ? State.STOPPED : State.FAILED : session.state;
        if (uninstalling.contains(pluginId)) state = uninstallError == null ? State.UNINSTALLING : State.FAILED;
        PluginSummary summary = new PluginSummary(pluginId, artifact.manifest.name(), artifact.manifest.version(), artifact.manifest.description(), artifact.manifest.publisher(),
                artifact.hash, artifact.signatureVerified, preferences.enabled(), preferences.startupPolicy(), state, config.revision(), artifact.manifest.capabilities(), lastError);
        return new PluginDetails(summary, artifact.manifest.json(), store.snapshot(artifact.manifest, config), artifact.manifest.permissions(), artifact.manifest.contributions(), artifact.manifest.uiSchemaJson());
    }

    @Override public AutoCloseable observeStatus(Consumer<PluginStatusEvent> listener) { observers.add(listener); return () -> observers.remove(listener); }
    @Override public AutoCloseable observeSecurityEvents(Consumer<PluginSecurityEvent> listener) { securityObservers.add(listener); return () -> securityObservers.remove(listener); }
    @Override public PackagePreview inspectPackage(Path source) { return inspectPackage(source, event -> { }, () -> false); }
    @Override public PackagePreview inspectPackage(URI source) { return inspectPackage(source, event -> { }, () -> false); }
    @Override public PackagePreview inspectPackage(Path source, Consumer<InstallProgress> progress, BooleanSupplier cancelled) { return remember(verifier.inspect(source, progress, cancelled)); }
    @Override public PackagePreview inspectPackage(URI source, Consumer<InstallProgress> progress, BooleanSupplier cancelled) { return remember(verifier.inspect(source, progress, cancelled)); }
    @Override public void discardPackagePreview(String previewId) {
        var preview = previews.remove(previewId); if (preview != null) deleteStaging(preview);
    }

    private PackagePreview remember(PluginPackageVerifier.VerifiedPackage preview) {
        synchronized (this) { requireOpen(); }
        previews.put(preview.preview().previewId(), preview); cleanupPreviews(); return preview.preview();
    }

    @Override public synchronized PluginDetails install(String workspaceId, String previewId) {
        requireWorkspace(workspaceId); requireOpen();
        PluginPackageVerifier.VerifiedPackage preview = previews.remove(previewId);
        if (preview == null) throw new PluginException("PREVIEW_EXPIRED", "安装预览已失效，请重新预览");
        if (preview.createdAt().plus(Duration.ofMinutes(30)).isBefore(Instant.now())) { deleteStaging(preview); throw new PluginException("PREVIEW_EXPIRED", "安装预览已失效，请重新预览"); }
        String id = preview.manifest().id();
        if (uninstalling.contains(id)) throw new PluginException("UNINSTALL_IN_PROGRESS", "插件正在全局卸载");
        try {
            PluginPackageVerifier.VerifiedContent verified = verifier.verifyContents(preview.contents(), () -> false);
            if (!verified.hash().equals(preview.preview().artifactHash())) throw new PluginException("PACKAGE_CHANGED", "预览后插件包发生变化");
            Path plugin = PluginPaths.directory(artifactsRoot, artifactsRoot.resolve(id));
            Path versions = PluginPaths.directory(artifactsRoot, plugin.resolve("versions"));
            Path target = PluginPaths.managed(artifactsRoot, versions.resolve(verified.hash()));
            if (!Files.exists(target)) PluginPaths.move(preview.contents(), target);
            else {
                PluginPackageVerifier.VerifiedContent existing = verifier.verifyContents(target, () -> false);
                if (!existing.hash().equals(verified.hash())) throw new PluginException("PACKAGE_CHANGED", "现有工件校验失败");
                verified = existing;
            }
            // 工件目录原子就绪后才发布指针；崩溃留下的未引用版本可安全保留。
            Installed candidate = new Installed(verified.hash(), verified.manifest(), target, verified.signatureVerified());
            artifactCache.put(id + "/" + verified.hash(), candidate);
            var old = store.current(workspaceId, verified.manifest(), verified.hash());
            if (!old.artifactHash().equals(candidate.hash)) {
                selectBinding(workspaceId, candidate, new ConfigurationUpdate(old.revision(), old.configurationJson(), Map.of(), intersection(old.permissions(), candidate.manifest.permissions())));
            }
            PluginStateStore.atomicWrite(plugin.resolve("active.json"), json.writeValueAsBytes(new ActiveArtifact(verified.hash())), true);
            installed.put(id, candidate);
            publish(workspaceId, id, State.INSTALLED, "插件已安装，启用后方可执行");
            return details(workspaceId, id);
        } catch (PluginException failure) { throw failure; }
        catch (Exception failure) { throw new PluginException("INSTALL_FAILED", "插件安装失败", failure); }
        finally { deleteStaging(preview); }
    }

    @Override public synchronized List<PluginVersion> versions(String workspaceId, String pluginId) {
        requireWorkspace(workspaceId); Installed fallback = installed(pluginId);
        String selected = store.current(workspaceId, fallback.manifest, fallback.hash).artifactHash();
        try (var directories = Files.list(PluginPaths.managed(artifactsRoot, artifactsRoot.resolve(pluginId).resolve("versions")))) {
            List<PluginVersion> versions = new ArrayList<>();
            for (Path directory : directories.filter(Files::isDirectory).toList()) {
                String hash = directory.getFileName().toString();
                if (!hash.matches("[0-9a-f]{64}")) continue;
                Installed artifact = historical(pluginId, hash);
                versions.add(new PluginVersion(pluginId, artifact.manifest.version(), hash, artifact.manifest.publisher(), artifact.signatureVerified, selected.equals(hash)));
            }
            return versions.stream().sorted(Comparator.comparing(PluginVersion::version).reversed().thenComparing(PluginVersion::artifactHash)).toList();
        } catch (IOException failure) { throw new PluginException("VERSION_LIST_FAILED", "无法读取已安装插件版本", failure); }
    }

    @Override public synchronized PluginDetails selectVersion(String workspaceId, String pluginId, String hash, long expectedRevision) {
        requireWorkspace(workspaceId); requireAvailable(pluginId); Installed artifact = historical(pluginId, hash);
        var old = store.current(workspaceId, installed(pluginId).manifest, installed(pluginId).hash);
        return selectVersion(workspaceId, pluginId, hash, new ConfigurationUpdate(expectedRevision, old.configurationJson(), Map.of(), intersection(old.permissions(), artifact.manifest.permissions())));
    }

    @Override public synchronized PluginDetails selectVersion(String workspaceId, String pluginId, String hash, ConfigurationUpdate update) {
        requireWorkspace(workspaceId); requireAvailable(pluginId);
        selectBinding(workspaceId, historical(pluginId, hash), update); return details(workspaceId, pluginId);
    }

    private void selectBinding(String workspace, Installed artifact, ConfigurationUpdate update) {
        String plugin = artifact.manifest.id();
        Installed fallback = installed.getOrDefault(plugin, artifact);
        var old = store.current(workspace, fallback.manifest, fallback.hash);
        var next = store.candidate(artifact.manifest, artifact.hash, old, update);
        Key key = new Key(workspace, plugin); var previous = current.get(key);
        PluginProcessSupervisor.Session candidate = null;
        if (previous != null && activeWorkspaces.contains(workspace) && !manuallyStopped.contains(key)) candidate = launch(workspace, artifact, next, previous);
        try { store.commit(workspace, plugin, next); }
        catch (RuntimeException failure) { if (candidate != null) retire(candidate, true); throw failure; }
        if (!next.permissions().containsAll(old.permissions())) {
            for (var session : List.copyOf(instances.values())) if (matches(session, key) && session != candidate) { processes.invalidate(session); retire(session, true); }
            emitSecurity(workspace, plugin, SecurityReason.PERMISSIONS_REDUCED, difference(old.permissions(), next.permissions()));
        }
        if (candidate != null) switchCurrent(key, candidate);
        lastErrors.remove(key);
        publish(workspace, plugin, candidate == null ? State.STOPPED : State.READY, "工作区插件版本已切换");
    }

    @Override public synchronized UninstallPreview previewUninstall(String workspaceId, String pluginId) {
        requireWorkspace(workspaceId); Installed artifact = installed(pluginId);
        List<WorkspaceReference> references = new ArrayList<>();
        for (String workspace : store.referencingWorkspaces(pluginId)) {
            var config = store.current(workspace, artifact.manifest, artifact.hash);
            references.add(new WorkspaceReference(workspace, store.preferences(workspace, pluginId).enabled(), config.revision(), activeRuns(workspace, pluginId)));
        }
        List<String> blockers = references.stream().filter(value -> value.activeRuns() > 0).map(value -> value.workspaceId() + " 中有 " + value.activeRuns() + " 个运行租约，卸载将等待排空").toList();
        return new UninstallPreview(pluginId, references, blockers, true);
    }

    @Override public synchronized void uninstall(String workspaceId, String pluginId, boolean deleteData) {
        requireWorkspace(workspaceId); Installed artifact = installed(pluginId);
        try {
            PluginStateStore.atomicWrite(artifactsRoot.resolve(pluginId).resolve("uninstall.json"),
                    json.writeValueAsBytes(new UninstallMarker(deleteData, artifact.hash, artifact.manifest.json())), true);
        } catch (IOException failure) { throw new PluginException("UNINSTALL_FAILED", "无法记录卸载意图", failure); }
        uninstalling.add(pluginId);
        uninstallErrors.remove(pluginId);
        transitions.forEach((key, pending) -> { if (key.plugin.equals(pluginId)) pending.cancelled = true; });
        uninstallCleanupComplete.remove(pluginId);
        for (var session : List.copyOf(instances.values())) if (session.identity.getPluginId().equals(pluginId)) {
            session.accepting.set(false); session.state = State.DRAINING;
            retire(session, false);
        }
        for (String workspace : store.referencingWorkspaces(pluginId)) emitSecurity(workspace, pluginId, SecurityReason.UNINSTALL, Set.of());
        uninstallCleanupComplete.add(pluginId);
        publish(workspaceId, pluginId, State.UNINSTALLING, "已停止接受新租约，正在全局排空");
        finishUninstall(pluginId);
    }

    @Override public synchronized ConfigurationSnapshot configuration(String workspaceId, String pluginId) { return details(workspaceId, pluginId).configuration(); }

    @Override public synchronized PluginDetails updateConfiguration(String workspaceId, String pluginId, ConfigurationUpdate update) {
        requireWorkspace(workspaceId); requireAvailable(pluginId); Binding binding = bound(workspaceId, pluginId);
        Installed artifact = binding.artifact; var old = binding.configuration;
        var next = store.candidate(artifact.manifest, artifact.hash, old, update);
        Key key = new Key(workspaceId, pluginId); var previous = current.get(key);
        boolean reduction = !next.permissions().containsAll(old.permissions());
        if (reduction) {
            cancelTransition(key);
            // 撤权立即生效，旧实例不能以旧租约继续使用被撤销的回调能力。
            for (var session : List.copyOf(instances.values())) if (matches(session, key)) { processes.invalidate(session); retire(session, true); }
            store.commit(workspaceId, pluginId, next); previous = null;
            emitSecurity(workspaceId, pluginId, SecurityReason.PERMISSIONS_REDUCED, difference(old.permissions(), next.permissions()));
        }
        PluginProcessSupervisor.Session candidate = null;
        if (activeWorkspaces.contains(workspaceId) && !manuallyStopped.contains(key) && next.permissions().containsAll(artifact.manifest.permissions())
                && (previous != null || reduction && store.preferences(workspaceId, pluginId).enabled())) {
            candidate = launch(workspaceId, artifact, next, previous);
        }
        if (!reduction) {
            try { store.commit(workspaceId, pluginId, next); }
            catch (RuntimeException failure) { if (candidate != null) retire(candidate, true); throw failure; }
        }
        if (candidate != null) switchCurrent(key, candidate);
        publish(workspaceId, pluginId, candidate == null ? State.STOPPED : State.READY, "配置已保存");
        return details(workspaceId, pluginId);
    }

    @Override public synchronized void setEnabled(String workspaceId, String pluginId, boolean enabled) {
        requireWorkspace(workspaceId); installed(pluginId);
        var preferences = store.preferences(workspaceId, pluginId);
        if (enabled) manuallyStopped.remove(new Key(workspaceId, pluginId));
        store.preferences(workspaceId, pluginId, new PluginStateStore.Preferences(enabled, preferences.startupPolicy(), enabled ? false : preferences.revoked(), preferences.quarantined()));
        if (!enabled) stop(workspaceId, pluginId);
        else if (activeWorkspaces.contains(workspaceId)) ensureStarted(workspaceId, pluginId);
        publish(workspaceId, pluginId, details(workspaceId, pluginId).summary().state(), enabled ? "已启用" : "已禁用");
    }

    @Override public synchronized void start(String workspaceId, String pluginId) {
        manuallyStopped.remove(new Key(workspaceId, pluginId));
        ensureStarted(workspaceId, pluginId);
    }

    private void ensureStarted(String workspaceId, String pluginId) {
        requireWorkspace(workspaceId); requireAvailable(pluginId); requireActive(workspaceId);
        if (manuallyStopped.contains(new Key(workspaceId, pluginId))) throw new PluginException("PLUGIN_STOPPED", "插件已手动停止，请显式启动后再接收任务");
        var preferences = store.preferences(workspaceId, pluginId);
        if (!preferences.enabled()) throw new PluginException("PLUGIN_DISABLED", "请先启用插件");
        if (preferences.revoked()) throw new PluginException("PLUGIN_REVOKED", "插件权限已撤销");
        if (preferences.quarantined()) throw new PluginException("PLUGIN_QUARANTINED", "插件已隔离，请查看诊断后解除隔离");
        Binding binding = bound(workspaceId, pluginId); Installed artifact = binding.artifact; Key key = new Key(workspaceId, pluginId);
        var previous = current.get(key);
        var config = binding.configuration;
        if (previous != null && previous.live.get() && previous.accepting.get() && previous.identity.getArtifactHash().equals(artifact.hash) && previous.identity.getConfigurationRevision() == config.revision()) return;
        var candidate = launch(workspaceId, artifact, config, previous);
        try { store.commit(workspaceId, pluginId, config); }
        catch (RuntimeException failure) { retire(candidate, true); throw failure; }
        switchCurrent(key, candidate);
        publish(workspaceId, pluginId, State.READY, "插件实例就绪");
    }

    @Override public synchronized void stop(String workspaceId, String pluginId) {
        requireWorkspace(workspaceId); installed(pluginId); Key key = new Key(workspaceId, pluginId);
        cancelTransition(key);
        manuallyStopped.add(key);
        for (var session : List.copyOf(instances.values())) if (matches(session, key)) {
            session.accepting.set(false); session.state = State.DRAINING;
            retire(session, false);
        }
        current.remove(key); emitSecurity(workspaceId, pluginId, SecurityReason.STOP, Set.of());
        publish(workspaceId, pluginId, visibleSession(key) == null ? State.STOPPED : State.DRAINING, "已停止接入，现有租约与后台任务排空后退出");
    }

    @Override public synchronized void forceStop(String workspaceId, String pluginId) {
        requireWorkspace(workspaceId); installed(pluginId); Key key = new Key(workspaceId, pluginId);
        cancelTransition(key);
        manuallyStopped.add(key);
        for (var session : List.copyOf(instances.values())) if (matches(session, key)) {
            processes.invalidate(session); session.runs.clear(); retire(session, true);
        }
        current.remove(key); emitSecurity(workspaceId, pluginId, SecurityReason.FORCE_STOP, Set.of());
        publish(workspaceId, pluginId, State.STOPPED, "已强制取消运行并使实例身份失效；授权配置已保留");
        if (uninstalling.contains(pluginId)) finishUninstall(pluginId);
    }

    @Override public synchronized void restart(String workspaceId, String pluginId) {
        requireWorkspace(workspaceId); requireAvailable(pluginId); requireActive(workspaceId);
        manuallyStopped.remove(new Key(workspaceId, pluginId));
        var preferences = store.preferences(workspaceId, pluginId);
        if (!preferences.enabled() || preferences.revoked() || preferences.quarantined()) throw new PluginException("PLUGIN_UNAVAILABLE", "插件未启用、已撤权或已隔离");
        Binding binding = bound(workspaceId, pluginId); Installed artifact = binding.artifact; var config = binding.configuration;
        Key key = new Key(workspaceId, pluginId); var candidate = launch(workspaceId, artifact, config, current.get(key));
        switchCurrent(key, candidate); publish(workspaceId, pluginId, State.READY, "新实例就绪，旧实例正在排空");
    }

    @Override public synchronized void setStartupPolicy(String workspaceId, String pluginId, StartupPolicy policy) {
        requireWorkspace(workspaceId); installed(pluginId); var value = store.preferences(workspaceId, pluginId);
        store.preferences(workspaceId, pluginId, new PluginStateStore.Preferences(value.enabled(), java.util.Objects.requireNonNull(policy), value.revoked(), value.quarantined()));
        if (policy == StartupPolicy.AUTO_START && value.enabled() && activeWorkspaces.contains(workspaceId) && !manuallyStopped.contains(new Key(workspaceId, pluginId))) ensureStarted(workspaceId, pluginId);
    }

    @Override public synchronized Diagnostics diagnostics(String workspaceId, String pluginId) {
        PluginDetails details = details(workspaceId, pluginId); Key key = new Key(workspaceId, pluginId); var session = visibleSession(key);
        if (session == null) return new Diagnostics(details.summary().state(), "", 0, details.summary().lastError(), List.of(), Map.of("activeRuns", "0", "activeRequests", "0"));
        Diagnostics selected = processes.diagnostics(session);
        List<PluginProcessSupervisor.Session> relevant = instances.values().stream().filter(value -> matches(value, key)).sorted(Comparator.comparing(value -> value.startedAt)).toList();
        List<String> logs = relevant.stream().flatMap(value -> value.logs().stream()).toList();
        if (logs.size() > 200) logs = logs.subList(logs.size() - 200, logs.size());
        Map<String, String> health = new HashMap<>(selected.health());
        health.put("activeRuns", Integer.toString(relevant.stream().mapToInt(value -> value.runs.size() + value.backgrounds.size()).sum()));
        health.put("backgroundLeases", Integer.toString(relevant.stream().mapToInt(value -> value.backgrounds.size()).sum()));
        health.put("activeRequests", Integer.toString(relevant.stream().mapToInt(value -> value.calls.size()).sum()));
        health.put("drainingInstances", Long.toString(relevant.stream().filter(value -> !value.accepting.get()).count()));
        return new Diagnostics(details.summary().state(), selected.instanceId(), selected.pid(), selected.lastError(), logs, health);
    }

    @Override public synchronized void revoke(String workspaceId, String pluginId) {
        requireWorkspace(workspaceId); Binding binding = bound(workspaceId, pluginId);
        Installed artifact = binding.artifact; var old = binding.configuration;
        cancelTransition(new Key(workspaceId, pluginId));
        store.commit(workspaceId, pluginId, new PluginStateStore.StoredConfiguration(old.revision() + 1, old.artifactHash(), old.configurationJson(), old.encryptedSecrets(), Set.of()));
        var value = store.preferences(workspaceId, pluginId);
        store.preferences(workspaceId, pluginId, new PluginStateStore.Preferences(false, value.startupPolicy(), true, value.quarantined()));
        Key key = new Key(workspaceId, pluginId);
        for (var session : List.copyOf(instances.values())) if (matches(session, key)) { processes.invalidate(session); retire(session, true); }
        current.remove(key); emitSecurity(workspaceId, pluginId, SecurityReason.REVOKE, old.permissions());
        publish(workspaceId, pluginId, State.REVOKED, "所有实例回调凭据已立即失效");
    }

    @Override public synchronized void unquarantine(String workspaceId, String pluginId) {
        requireWorkspace(workspaceId); installed(pluginId); var value = store.preferences(workspaceId, pluginId);
        store.preferences(workspaceId, pluginId, new PluginStateStore.Preferences(value.enabled(), value.startupPolicy(), value.revoked(), false));
        failures.remove(new Key(workspaceId, pluginId)); publish(workspaceId, pluginId, State.STOPPED, "已解除隔离，可手动启动");
    }

    @Override public synchronized void activateWorkspace(String workspaceId) {
        requireWorkspace(workspaceId); requireOpen();
        resumeUninstalls();
        try {
            for (var result : new BundledPluginInstaller(this, json, artifactsRoot).importPackages(artifactsRoot.getParent(), workspaceId)) {
                if (result.status().equals("FAILED")) publish(workspaceId, result.pluginId(), State.FAILED, "发行插件导入失败: " + result.message());
            }
        } catch (RuntimeException failure) { publish(workspaceId, "", State.FAILED, "发行插件清单无效: " + failure.getMessage()); }
        activeWorkspaces.add(workspaceId);
        for (String pluginId : List.copyOf(installed.keySet())) {
            var value = store.preferences(workspaceId, pluginId);
            if (value.enabled() && value.startupPolicy() == StartupPolicy.AUTO_START && !value.revoked() && !value.quarantined() && !uninstalling.contains(pluginId) && !manuallyStopped.contains(new Key(workspaceId, pluginId))) {
                try { ensureStarted(workspaceId, pluginId); } catch (RuntimeException failure) { publish(workspaceId, pluginId, State.FAILED, failure.getMessage()); }
            }
        }
    }

    @Override public synchronized void suspendWorkspace(String workspaceId) {
        requireWorkspace(workspaceId); activeWorkspaces.remove(workspaceId);
        transitions.forEach((key, pending) -> { if (key.workspace.equals(workspaceId)) pending.cancelled = true; });
        for (var session : List.copyOf(instances.values())) if (session.identity.getWorkspaceId().equals(workspaceId)) { processes.invalidate(session); retire(session, true); }
        current.keySet().removeIf(key -> key.workspace.equals(workspaceId));
        for (String plugin : installed.keySet()) if (store.referencingWorkspaces(plugin).contains(workspaceId)) emitSecurity(workspaceId, plugin, SecurityReason.WORKSPACE_SUSPEND, Set.of());
    }

    @Override public synchronized RunLease acquireRun(String workspaceId, String pluginId) {
        requireWorkspace(workspaceId); requireAvailable(pluginId); requireActive(workspaceId);
        ensureStarted(workspaceId, pluginId); var session = current.get(new Key(workspaceId, pluginId));
        if (session == null || !session.accepting.get() || !session.live.get()) throw new PluginException("PLUGIN_NOT_READY", "插件没有就绪实例");
        return lease(session);
    }

    @Override public synchronized RunLease acquireRun(String workspaceId, String pluginId, String artifactHash, long configurationRevision) {
        requireWorkspace(workspaceId); requireAvailable(pluginId); requireActive(workspaceId);
        if (manuallyStopped.contains(new Key(workspaceId, pluginId))) throw new PluginException("RECOVERY_BLOCKED", "插件已手动停止，无法接入恢复运行");
        var preferences = store.preferences(workspaceId, pluginId);
        if (!preferences.enabled() || preferences.revoked() || preferences.quarantined()) throw new PluginException("RECOVERY_BLOCKED", "插件已禁用、撤权或隔离，无法恢复旧运行");
        Installed artifact = historical(pluginId, artifactHash);
        var config = store.historical(workspaceId, pluginId, artifactHash, configurationRevision);
        var latest = store.current(workspaceId, installed(pluginId).manifest, installed(pluginId).hash);
        if (!latest.permissions().containsAll(config.permissions())) throw new PluginException("RECOVERY_BLOCKED", "恢复所需权限已被撤销");
        var session = instances.values().stream().filter(value -> value.live.get() && value.callbacksAllowed.get() && !value.retiring.get()
                && value.identity.getWorkspaceId().equals(workspaceId) && value.identity.getPluginId().equals(pluginId)
                && value.identity.getArtifactHash().equals(artifactHash) && value.identity.getConfigurationRevision() == configurationRevision).findFirst().orElse(null);
        if (session == null) {
            try { session = launch(workspaceId, artifact, config, null); session.accepting.set(false); }
            catch (RuntimeException failure) { throw new PluginException("RECOVERY_BLOCKED", "恢复固定插件快照失败", failure); }
        }
        return lease(session);
    }

    @Override public PluginInvocation invoke(InvocationRequest request, Consumer<PluginEvent> events) {
        RunLease lease = acquireRun(request.workspaceId(), request.pluginId());
        try {
            PluginInvocation call = invoke(lease, request.capability(), request.contributionId(), request.operation(), request.payloadJson(), request.timeout(), events);
            call.completion().whenComplete((value, failure) -> lease.close()); return call;
        } catch (RuntimeException failure) { lease.close(); throw failure; }
    }

    @Override public synchronized PluginInvocation invoke(RunLease runLease, String capability, String contributionId, String operation, String payloadJson, Duration timeout, Consumer<PluginEvent> events) {
        if (!(runLease instanceof Lease lease) || lease.owner != this || lease.closed.get()) throw new PluginException("INVALID_LEASE", "插件租约不属于当前宿主或已关闭");
        requireActive(lease.workspaceId());
        return processes.invoke(lease.session, lease.runId, capability.toUpperCase(java.util.Locale.ROOT), contributionId, operation, payloadJson, timeout, events);
    }

    private PluginProcessSupervisor.Session launch(String workspace, Installed artifact, PluginStateStore.StoredConfiguration config, PluginProcessSupervisor.Session previous) {
        Key key = new Key(workspace, artifact.manifest.id());
        if (transitions.containsKey(key)) throw new PluginException("RESOURCE_BUSY", "插件已有配置或实例切换正在进行");
        PendingLaunch pending = new PendingLaunch(store.current(workspace, artifact.manifest, artifact.hash).revision());
        transitions.put(key, pending);
        try {
            Runnable admission = () -> verifyLaunchAdmission(key, artifact, pending);
            admission.run();
            store.validate(artifact.manifest, config);
            if (!artifact.manifest.permissions().containsAll(config.permissions())) throw new PluginException("PERMISSION_DENIED", "配置授予了插件清单以外的权限");
            if (!config.permissions().containsAll(artifact.manifest.permissions())) throw new PluginException("PERMISSION_REQUIRED", "请先授予清单要求的全部权限");
            awaitCapacity(artifact.manifest, previous, admission);
            // 等待会释放管理锁，旧实例退出后仍须核验，不能让已取消的切换重新发布实例。
            admission.run();
            publish(workspace, artifact.manifest.id(), State.STARTING, "正在启动独立插件进程");
            String jarHash = signedJarHash(artifact);
            var session = processes.start(artifact.manifest, artifact.hash, artifact.directory, jarHash, workspace, config, store.runtime(config), store.data(workspace, artifact.manifest.id()));
            instances.put(session.identity.getInstanceId(), session); lastErrors.remove(key); return session;
        } catch (RuntimeException failure) {
            if (failure instanceof PluginException problem && problem.code().equals("CANCELLED")) throw failure;
            lastErrors.put(key, failure.getMessage());
            publish(workspace, artifact.manifest.id(), State.FAILED, failure.getMessage()); throw failure;
        } finally { transitions.remove(key, pending); notifyAll(); }
    }

    private void cancelTransition(Key key) {
        PendingLaunch pending = transitions.get(key);
        if (pending != null) pending.cancelled = true;
        notifyAll();
    }

    private void verifyLaunchAdmission(Key key, Installed artifact, PendingLaunch pending) {
        if (closed || pending.cancelled || !activeWorkspaces.contains(key.workspace) || uninstalling.contains(key.plugin)
                || manuallyStopped.contains(key)) throw new PluginException("CANCELLED", "插件切换已因工作区或接入策略变化取消");
        var preferences = store.preferences(key.workspace, key.plugin);
        if (!preferences.enabled() || preferences.revoked() || preferences.quarantined()
                || store.current(key.workspace, artifact.manifest, artifact.hash).revision() != pending.revision)
            throw new PluginException("CANCELLED", "插件切换已因权限或配置版本变化取消");
    }

    private String signedJarHash(Installed artifact) {
        try {
            var verified = verifier.verifyContents(artifact.directory, () -> false);
            if (!verified.hash().equals(artifact.hash)) throw new IOException("工件清单哈希不匹配");
            return verified.files().stream().filter(file -> file.path().equals(artifact.manifest.artifact())).findFirst().orElseThrow().sha256();
        } catch (Exception failure) { throw new PluginException("ARTIFACT_TAMPERED", "插件启动前完整性校验失败", failure); }
    }

    private void awaitCapacity(PluginManifest manifest, PluginProcessSupervisor.Session previous, Runnable admission) {
        if (previous != null && !previous.live.get()) { retire(previous, true); previous = null; }
        int budget = pluginHeapBudgetMiB();
        boolean insufficient = instances.values().stream().filter(value -> value.live.get()).mapToInt(value -> value.manifest.heapMiB()).sum() + manifest.heapMiB() > budget;
        if (manifest.exclusive() && previous == null && instances.values().stream().anyMatch(value -> value.live.get() && value.identity.getPluginId().equals(manifest.id()))) throw new PluginException("RESOURCE_BUSY", "插件独占资源仍被其他固定快照使用");
        if (previous != null && (manifest.exclusive() || insufficient)) {
            previous.accepting.set(false); previous.state = State.DRAINING;
            retire(previous, false);
            long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
            while (instances.containsKey(previous.identity.getInstanceId())) {
                admission.run();
                if (System.nanoTime() >= deadline) throw new PluginException("RESOURCE_BUSY", "独占资源正在排空，配置尚未切换");
                try { wait(250); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new PluginException("CANCELLED", "等待旧租约排空已取消", interrupted); }
            }
        }
        int heap = instances.values().stream().filter(value -> value.live.get()).mapToInt(value -> value.manifest.heapMiB()).sum();
        if (heap + manifest.heapMiB() > budget) throw new PluginException("RESOURCE_BUSY", "插件进程内存预算不足");
    }

    /** 默认预算保留一半物理内存给宿主与系统，显式配置可以覆盖该策略。 */
    private static int pluginHeapBudgetMiB() {
        Integer override = Integer.getInteger("javaclaw.plugin.maxHeapMiB");
        if (override != null && override > 0) return override;
        var operatingSystem = ManagementFactory.getOperatingSystemMXBean();
        long bytes = operatingSystem instanceof com.sun.management.OperatingSystemMXBean system ? system.getTotalMemorySize() : 0;
        return defaultHeapBudgetMiB(bytes);
    }

    static int defaultHeapBudgetMiB(long physicalMemoryBytes) {
        return (int) Math.max(2048, Math.min(16_384, physicalMemoryBytes / (2 * 1024 * 1024)));
    }

    private void switchCurrent(Key key, PluginProcessSupervisor.Session candidate) {
        var previous = current.put(key, candidate);
        if (previous != null && previous != candidate) {
            previous.accepting.set(false); previous.state = State.DRAINING;
            retire(previous, false);
        }
    }

    private Lease lease(PluginProcessSupervisor.Session session) {
        String runId = UUID.randomUUID().toString(); session.runs.add(runId); return new Lease(session, runId);
    }

    private void retire(PluginProcessSupervisor.Session session, boolean force) {
        session.accepting.set(false);
        if (force || !session.live.get()) {
            session.retiring.set(true); processes.shutdown(session, force); detach(session); return;
        }
        session.state = State.DRAINING;
        if (!session.drainChecking.compareAndSet(false, true)) return;
        try { tasks.submit(TaskSpec.io("plugin-drain-" + session.identity.getInstanceId()), context -> {
            try {
                boolean drained = session.retiring.get() || processes.drain(session);
                synchronized (this) {
                    if (closed || !instances.containsKey(session.identity.getInstanceId())) return null;
                    if (!drained || !session.runs.isEmpty() || !session.backgrounds.isEmpty()) return null;
                    session.retiring.set(true);
                }
                // 控制 RPC 和进程退出等待不占用管理门面的锁。
                boolean stopped = processes.shutdown(session, false);
                synchronized (this) {
                    if (stopped) detach(session);
                }
                return null;
            } finally { session.drainChecking.set(false); }
        }); } catch (RuntimeException failure) { session.drainChecking.set(false); }
    }

    private void detach(PluginProcessSupervisor.Session session) {
        instances.remove(session.identity.getInstanceId(), session);
        Key key = new Key(session.identity.getWorkspaceId(), session.identity.getPluginId());
        current.remove(key, session); notifyAll();
        if (uninstalling.contains(session.identity.getPluginId())) finishUninstall(session.identity.getPluginId());
        else if (!closed && installed.containsKey(key.plugin) && visibleSession(key) == null) publish(key.workspace, key.plugin, details(key.workspace, key.plugin).summary().state(), "插件实例已退出");
    }

    private void processExited(PluginProcessSupervisor.Session session) {
        try { tasks.submit(TaskSpec.io("plugin-crash-" + session.identity.getInstanceId()), context -> {
            synchronized (this) {
                if (closed || !instances.containsKey(session.identity.getInstanceId())) return null;
                if (session.retiring.get()) { processes.shutdown(session, false); detach(session); return null; }
                Key key = new Key(session.identity.getWorkspaceId(), session.identity.getPluginId());
                ArrayDeque<Instant> recent = failures.computeIfAbsent(key, ignored -> new ArrayDeque<>());
                Instant now = Instant.now(); recent.addLast(now); while (!recent.isEmpty() && recent.getFirst().isBefore(now.minus(Duration.ofMinutes(5)))) recent.removeFirst();
                boolean wasCurrent = current.get(key) == session;
                lastErrors.put(key, session.lastError);
                var preferences = store.preferences(key.workspace, key.plugin);
                if (recent.size() >= 3) {
                    store.preferences(key.workspace, key.plugin, new PluginStateStore.Preferences(preferences.enabled(), preferences.startupPolicy(), preferences.revoked(), true));
                    session.state = State.QUARANTINED;
                }
                publish(key.workspace, key.plugin, session.state, session.lastError);
                if (session.runs.isEmpty()) retire(session, true);
                if (wasCurrent && recent.size() < 3 && preferences.enabled() && preferences.startupPolicy() == StartupPolicy.AUTO_START && activeWorkspaces.contains(key.workspace) && !uninstalling.contains(key.plugin)) {
                    scheduleRestart(key, recent.size());
                }
            }
            return null;
        }); } catch (RuntimeException ignored) { /* 宿主关闭时不再处理退出事件，统一关闭路径已回收进程。 */ }
    }

    private void scheduleRestart(Key key, int count) {
        tasks.scheduleTrigger(Duration.ofSeconds(Math.min(30, 1L << count)), () -> {
            try { tasks.submit(TaskSpec.io("plugin-restart-" + key.plugin), context -> {
                synchronized (this) {
                    if (closed || !activeWorkspaces.contains(key.workspace) || uninstalling.contains(key.plugin)) return null;
                    var preferences = store.preferences(key.workspace, key.plugin);
                    if (preferences.enabled() && preferences.startupPolicy() == StartupPolicy.AUTO_START && !preferences.revoked() && !preferences.quarantined() && !manuallyStopped.contains(key)) {
                        try { ensureStarted(key.workspace, key.plugin); } catch (RuntimeException failure) { publish(key.workspace, key.plugin, State.FAILED, failure.getMessage()); }
                    }
                }
                return null;
            }); } catch (RuntimeException ignored) { /* 执行器已关闭，禁止继续安排自动重启。 */ }
        });
    }

    private void checkHealth() {
        List<PluginProcessSupervisor.Session> check;
        synchronized (this) { if (closed) return; check = List.copyOf(current.values()); }
        for (var session : check) {
            if (session.state == State.DRAINING) continue;
            Diagnostics diagnostics = processes.diagnostics(session);
            if (diagnostics.state() == State.DEGRADED) publish(session.identity.getWorkspaceId(), session.identity.getPluginId(), State.DEGRADED, "插件健康检查未响应");
            if (session.healthFailures.get() >= 3 && session.live.get()) processes.terminateUnhealthy(session);
        }
        synchronized (this) {
            for (var session : List.copyOf(instances.values())) {
                processes.expireBackgroundLeases(session);
                if (session.state == State.DRAINING) retire(session, false);
            }
        }
        cleanupPreviews();
    }

    private void backgroundReleased(PluginProcessSupervisor.Session session) {
        synchronized (this) {
            if (!closed && instances.containsKey(session.identity.getInstanceId()) && session.state == State.DRAINING) retire(session, false);
        }
    }

    private void finishUninstall(String pluginId) {
        if (!uninstalling.contains(pluginId) || !uninstallCleanupComplete.contains(pluginId) || uninstallErrors.containsKey(pluginId)
                || instances.values().stream().anyMatch(value -> value.identity.getPluginId().equals(pluginId))) return;
        try {
            Path directory = artifactsRoot.resolve(pluginId);
            UninstallMarker marker = json.readValue(Files.readAllBytes(directory.resolve("uninstall.json")), UninstallMarker.class);
            if (marker.deleteData) {
                List<String> workspaces = store.referencingWorkspaces(pluginId);
                for (String workspace : workspaces) emitSecurity(workspace, pluginId, SecurityReason.UNINSTALL_DELETE_DATA, Set.of());
                for (String workspace : workspaces) store.delete(workspace, pluginId);
            }
            PluginPaths.deleteTree(artifactsRoot, directory); installed.remove(pluginId); uninstalling.remove(pluginId);
            uninstallCleanupComplete.remove(pluginId);
            uninstallErrors.remove(pluginId);
            artifactCache.keySet().removeIf(key -> key.startsWith(pluginId + "/"));
            publish("", pluginId, State.STOPPED, "插件已全局卸载" + (marker.deleteData ? "，私有数据已清理" : "，工作区配置与私有数据已保留"));
        } catch (IOException | RuntimeException failure) {
            String message = "卸载最终清理失败，请重试卸载；插件接入仍关闭";
            // 排空完成后的清理运行在后台，必须主动通知界面，同时保留标记供显式重试或重启恢复。
            uninstallErrors.put(pluginId, message);
            publish("", pluginId, State.FAILED, message);
            throw new PluginException("UNINSTALL_FAILED", message, failure);
        }
    }

    /** 宿主清理监听在构造后注册，恢复卸载时必须先重放授权清理事件。 */
    private void resumeUninstalls() {
        for (String pluginId : List.copyOf(uninstalling)) {
            try {
                if (uninstallErrors.containsKey(pluginId)) {
                    publish("", pluginId, State.FAILED, uninstallErrors.get(pluginId)); continue;
                }
                if (uninstallCleanupComplete.contains(pluginId)) { finishUninstall(pluginId); continue; }
                json.readValue(Files.readAllBytes(artifactsRoot.resolve(pluginId).resolve("uninstall.json")), UninstallMarker.class);
                for (String workspace : store.referencingWorkspaces(pluginId)) emitSecurity(workspace, pluginId, SecurityReason.UNINSTALL, Set.of());
                uninstallCleanupComplete.add(pluginId);
                finishUninstall(pluginId);
            } catch (IOException | RuntimeException failure) {
                // 单个卸载恢复失败不能阻断工作区绑定或其他插件；失败项保持可见并等待显式重试。
                if (!uninstallErrors.containsKey(pluginId)) uninstallErrors.put(pluginId, "卸载恢复失败，请重试卸载；插件接入仍关闭");
                publish("", pluginId, State.FAILED, uninstallErrors.get(pluginId));
            }
        }
    }

    private void recoverArtifacts() throws Exception {
        try (var directories = Files.list(artifactsRoot)) {
            for (Path directory : directories.filter(Files::isDirectory).toList()) {
                PluginPaths.managed(artifactsRoot, directory);
                String id = PluginManifest.identifier(directory.getFileName().toString());
                Path marker = directory.resolve("uninstall.json");
                if (Files.exists(marker)) {
                    uninstalling.add(id);
                    try {
                        UninstallMarker intent = json.readValue(Files.readAllBytes(marker), UninstallMarker.class);
                        installed.put(id, recoverUninstallArtifact(id, directory, intent));
                    } catch (IOException | RuntimeException failure) {
                        // 损坏的卸载标记只影响该插件，展示占位项仍允许用户重试清理。
                        installed.put(id, unavailableUninstallArtifact(id, directory));
                        uninstallErrors.put(id, "卸载恢复失败，请重试卸载；插件接入仍关闭");
                    }
                    continue;
                }
                Path active = directory.resolve("active.json");
                if (!Files.isRegularFile(active)) continue;
                ActiveArtifact pointer = json.readValue(Files.readAllBytes(active), ActiveArtifact.class);
                installed.put(id, loadArtifact(id, pointer.hash));
            }
        }
    }

    private Installed recoverUninstallArtifact(String id, Path directory, UninstallMarker marker) throws IOException {
        String hash = marker.artifactHash;
        if (hash == null || !hash.matches("[0-9a-f]{64}")) {
            hash = json.readValue(Files.readAllBytes(directory.resolve("active.json")), ActiveArtifact.class).hash;
        }
        try { return loadArtifact(id, hash); }
        catch (PluginException failure) {
            PluginManifest snapshot = null;
            if (marker.manifestJson != null && !marker.manifestJson.isBlank()) snapshot = PluginManifest.parse(json, marker.manifestJson);
            else for (String workspace : store.referencingWorkspaces(id)) {
                try { snapshot = store.manifestSnapshot(workspace, id, hash); break; }
                catch (PluginException ignored) { /* 旧格式标记可从其他工作区快照继续恢复展示元数据。 */ }
            }
            if (snapshot == null || !snapshot.id().equals(id) || hash == null || !hash.matches("[0-9a-f]{64}")) throw failure;
            // 清单快照只用于待卸载项展示；卸载门闩始终阻止该工件取得运行租约。
            return new Installed(hash, snapshot, directory.resolve("versions").resolve(hash), false);
        }
    }

    private Installed unavailableUninstallArtifact(String id, Path directory) throws IOException {
        String manifest = json.writeValueAsString(Map.of("id", id, "name", id, "version", "unknown",
                "description", "卸载元数据不可用，请重试卸载", "protocol", Map.of("major", 2),
                "entrypoint", "unavailable.UninstallOnly", "artifact", "unavailable.jar", "contributions", List.of()));
        return new Installed("0".repeat(64), PluginManifest.parse(json, manifest), directory, false);
    }

    private Installed loadArtifact(String pluginId, String hash) {
        if (hash == null || !hash.matches("[0-9a-f]{64}")) throw new PluginException("RECOVERY_BLOCKED", "工件哈希无效");
        try {
            Path directory = PluginPaths.managed(artifactsRoot, artifactsRoot.resolve(PluginManifest.identifier(pluginId)).resolve("versions").resolve(hash));
            if (!Files.isDirectory(directory)) throw new IOException("工件目录不存在");
            var verified = verifier.verifyContents(directory, () -> false);
            if (!verified.hash().equals(hash) || !verified.manifest().id().equals(pluginId)) throw new IOException("工件身份不匹配");
            return new Installed(hash, verified.manifest(), directory, verified.signatureVerified());
        } catch (Exception failure) { throw new PluginException("RECOVERY_BLOCKED", "固定版本插件工件缺失或完整性验证失败", failure); }
    }

    private Installed historical(String id, String hash) {
        Installed artifact = artifactCache.computeIfAbsent(id + "/" + hash, ignored -> loadArtifact(id, hash));
        if (!Files.isDirectory(artifact.directory)) throw new PluginException("RECOVERY_BLOCKED", "固定插件工件已不可用");
        return artifact;
    }
    private Binding bound(String workspace, String plugin) {
        Installed fallback = installed(plugin); var configuration = store.current(workspace, fallback.manifest, fallback.hash);
        if (uninstalling.contains(plugin)) return new Binding(fallback, configuration);
        Installed artifact;
        try {
            artifact = configuration.artifactHash().equals(fallback.hash) ? fallback : historical(plugin, configuration.artifactHash());
            store.rememberManifest(workspace, artifact.manifest, artifact.hash);
        } catch (PluginException failure) {
            if (!failure.code().equals("RECOVERY_BLOCKED")) throw failure;
            // 展示快照仅供修复缺失绑定，启动与恢复仍必须通过真实工件完整性验证。
            PluginManifest snapshot = store.manifestSnapshot(workspace, plugin, configuration.artifactHash());
            Path missing = artifactsRoot.resolve(plugin).resolve("versions").resolve(configuration.artifactHash());
            artifact = new Installed(configuration.artifactHash(), snapshot, missing, false);
            lastErrors.put(new Key(workspace, plugin), "工作区绑定的插件版本已不可用，请选择已安装版本");
        }
        return new Binding(artifact, configuration);
    }
    private Installed installed(String pluginId) { requireOpen(); PluginManifest.identifier(pluginId); Installed value = installed.get(pluginId); if (value == null) throw new PluginException("PLUGIN_NOT_FOUND", "插件未安装: " + pluginId); return value; }
    private void requireAvailable(String pluginId) { installed(pluginId); if (uninstalling.contains(pluginId)) throw new PluginException("UNINSTALL_IN_PROGRESS", "插件正在全局卸载，不接受新租约"); }
    private void requireActive(String workspace) { requireOpen(); if (!activeWorkspaces.contains(workspace)) throw new PluginException("WORKSPACE_INACTIVE", "工作区未激活或已暂停"); }
    private void requireOpen() { if (closed) throw new PluginException("SERVICE_CLOSED", "插件服务已关闭"); }
    private static void requireWorkspace(String workspace) { PluginManifest.identifier(workspace); }
    private int activeRuns(String workspace, String plugin) { return instances.values().stream().filter(value -> matches(value, new Key(workspace, plugin))).mapToInt(value -> value.runs.size() + value.backgrounds.size()).sum(); }
    private PluginProcessSupervisor.Session visibleSession(Key key) {
        var selected = current.get(key);
        return selected != null ? selected : instances.values().stream().filter(value -> matches(value, key)).max(Comparator.comparing(value -> value.startedAt)).orElse(null);
    }
    private static boolean matches(PluginProcessSupervisor.Session session, Key key) { return session.identity.getWorkspaceId().equals(key.workspace) && session.identity.getPluginId().equals(key.plugin); }
    private static Set<String> intersection(Set<String> left, Set<String> right) { Set<String> result = new HashSet<>(left); result.retainAll(right); return Set.copyOf(result); }
    private static Set<String> difference(Set<String> left, Set<String> right) { Set<String> result = new HashSet<>(left); result.removeAll(right); return Set.copyOf(result); }

    private void emitSecurity(String workspace, String plugin, SecurityReason reason, Set<String> revoked) {
        PluginSecurityEvent event = new PluginSecurityEvent(workspace, plugin, reason, revoked, Instant.now());
        RuntimeException failure = null;
        for (var listener : securityObservers) {
            try { listener.accept(event); }
            catch (RuntimeException problem) {
                if (failure == null) failure = problem; else failure.addSuppressed(problem);
            }
        }
        if (failure != null) {
            cancelTransition(new Key(workspace, plugin));
            manuallyStopped.add(new Key(workspace, plugin));
            for (var session : List.copyOf(instances.values())) if (matches(session, new Key(workspace, plugin))) { processes.invalidate(session); retire(session, true); }
            throw new PluginException("HOST_CLEANUP_FAILED", "插件接入已关闭，宿主副作用清理失败", failure);
        }
    }

    private void publish(String workspace, String plugin, State state, String message) {
        long revision = 0;
        synchronized (this) {
            Installed artifact = installed.get(plugin);
            if (artifact != null && workspace != null && !workspace.isBlank()) {
                try { revision = store.current(workspace, artifact.manifest, artifact.hash).revision(); } catch (RuntimeException ignored) { /* 诊断事件不能因配置损坏阻止生命周期清理。 */ }
            }
        }
        PluginStatusEvent event = new PluginStatusEvent(workspace, plugin, state, revision, message == null ? "" : message, Instant.now());
        for (var listener : observers) try { listener.accept(event); } catch (RuntimeException ignored) { /* 单个展示订阅失败不影响其余订阅和管理操作。 */ }
    }

    private void cleanupPreviews() {
        for (var entry : previews.entrySet()) if (entry.getValue().createdAt().plus(Duration.ofMinutes(30)).isBefore(Instant.now()) && previews.remove(entry.getKey(), entry.getValue())) deleteStaging(entry.getValue());
    }
    private void deleteStaging(PluginPackageVerifier.VerifiedPackage preview) { try { PluginPaths.deleteTree(stagingRoot, preview.staging()); } catch (IOException ignored) { /* 未清理的静态暂存目录会在下次初始化时再次回收。 */ } }

    @Override public synchronized void close() {
        if (closed) return; closed = true; heartbeat.close();
        processes.close(); current.clear(); instances.clear(); activeWorkspaces.clear();
        previews.values().forEach(this::deleteStaging); previews.clear(); observers.clear(); securityObservers.clear(); notifyAll();
    }

    private final class Lease implements RunLease {
        final DefaultPluginManagementService owner = DefaultPluginManagementService.this;
        final PluginProcessSupervisor.Session session;
        final String runId;
        final AtomicBoolean closed = new AtomicBoolean();
        Lease(PluginProcessSupervisor.Session session, String runId) { this.session = session; this.runId = runId; }
        public String runId() { return runId; }
        public String workspaceId() { return session.identity.getWorkspaceId(); }
        public String pluginId() { return session.identity.getPluginId(); }
        public String artifactHash() { return session.identity.getArtifactHash(); }
        public long configurationRevision() { return session.identity.getConfigurationRevision(); }
        public String instanceId() { return session.identity.getInstanceId(); }
        public Set<String> grantedPermissions() { return session.permissions; }
        public List<ContributionInfo> contributions() { return session.contributions; }
        public String manifestJson() { return session.manifest.json(); }
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            synchronized (owner) {
                processes.releaseRun(session, runId);
                if (session.runs.isEmpty() && (!session.accepting.get() || !session.live.get())) retire(session, false);
                owner.notifyAll(); if (uninstalling.contains(pluginId())) finishUninstall(pluginId());
            }
        }
    }

    private record Key(String workspace, String plugin) { }
    /** 每次切换独立持有取消状态，后续显式启动不会重新授权旧请求。 */
    private static final class PendingLaunch {
        final long revision;
        boolean cancelled;
        PendingLaunch(long revision) { this.revision = revision; }
    }
    private record Installed(String hash, PluginManifest manifest, Path directory, boolean signatureVerified) { }
    private record Binding(Installed artifact, PluginStateStore.StoredConfiguration configuration) { }
    private record ActiveArtifact(String hash) { }
    private record UninstallMarker(boolean deleteData, String artifactHash, String manifestJson) { }
}
