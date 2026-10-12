package com.javaclaw.infrastructure.inference;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.application.inference.InferenceCatalogPort;
import com.javaclaw.application.plugin.v2.PluginManagementService;
import com.javaclaw.application.plugin.v2.PluginManagementService.ContributionInfo;
import com.javaclaw.config.WorkspaceManager;
import com.javaclaw.inference.api.InferenceRuntimeManifest;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskScope;
import com.javaclaw.platform.execution.TaskSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** 将已验证的 MODEL 贡献元数据投影到推理目录，不装载插件类或再次安装工件。 */
public final class RemoteInferenceRuntimeCatalog implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(RemoteInferenceRuntimeCatalog.class);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
    private final PluginManagementService plugins;
    private final WorkspaceManager workspaces;
    private final InferenceCatalogPort catalog;
    private final ObjectMapper json;
    private final ManagedTaskExecutor tasks;
    private final Object lifecycleLock = new Object();
    private final Set<String> pendingWorkspaces = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile TaskScope refreshTasks;
    private AutoCloseable subscription;

    public RemoteInferenceRuntimeCatalog(PluginManagementService plugins, WorkspaceManager workspaces,
                                         InferenceCatalogPort catalog, ObjectMapper json, ManagedTaskExecutor tasks) {
        this.plugins = plugins; this.workspaces = workspaces; this.catalog = catalog; this.json = json;
        this.tasks = tasks;
    }

    public void init() {
        synchronized (lifecycleLock) {
            if (closed.get()) throw new IllegalStateException("推理目录已关闭");
            if (subscription != null) return;
            refreshTasks = tasks.openScope("插件推理目录投影", 1);
            subscription = plugins.observeStatus(event -> queueRefresh(event.workspaceId()));
        }
        refreshSafely(workspaces.getCurrentWorkspaceId());
    }

    /** 状态回调可能持有插件管理器锁，只登记刷新请求，不同步读取门面或目录。 */
    private void queueRefresh(String workspace) {
        if (closed.get()) return;
        pendingWorkspaces.add(workspace == null ? "" : workspace);
        dispatchRefresh();
    }

    private void dispatchRefresh() {
        TaskScope scope = refreshTasks;
        if (scope == null || closed.get() || !refreshing.compareAndSet(false, true)) return;
        try {
            scope.submit(TaskSpec.io("刷新插件推理目录"), task -> {
                try {
                    while (!closed.get() && !pendingWorkspaces.isEmpty()) {
                        task.cancellation().throwIfCancellationRequested();
                        Set<String> batch = Set.copyOf(pendingWorkspaces);
                        batch.forEach(pendingWorkspaces::remove);
                        Set<String> workspacesToRefresh = new LinkedHashSet<>();
                        for (String workspace : batch) {
                            workspacesToRefresh.add(workspace.isBlank()
                                    ? workspaces.getCurrentWorkspaceId() : workspace);
                        }
                        for (String workspace : workspacesToRefresh) {
                            if (closed.get()) break;
                            task.cancellation().throwIfCancellationRequested();
                            refreshSafely(workspace);
                        }
                    }
                } finally {
                    refreshing.set(false);
                    if (!pendingWorkspaces.isEmpty()) dispatchRefresh();
                }
                return null;
            });
        } catch (RuntimeException failure) {
            refreshing.set(false);
            if (!closed.get()) log.warn("插件推理目录刷新任务提交失败: {}", failure.getMessage());
        }
    }

    private void refreshSafely(String workspace) {
        try { refresh(workspace); }
        catch (RuntimeException failure) { log.warn("插件推理目录刷新失败: {}", failure.getMessage()); }
    }

    public synchronized void refresh(String workspace) {
        var installed = plugins.list(workspace);
        Set<String> installedIds = installed.stream().map(PluginManagementService.PluginSummary::id)
                .collect(java.util.stream.Collectors.toSet());
        for (var summary : installed) {
            if (!summary.capabilities().contains("MODEL")) continue;
            try {
                var details = plugins.details(workspace, summary.id());
                ContributionInfo contribution = modelContribution(details.contributions());
                JsonNode metadata = json.readTree(contribution.metadataJson());
                JsonNode inference = metadata.has("inference") ? metadata.path("inference") : metadata;
                if (inference.path("engine").asText().isBlank()) continue;
                Set<String> capabilities = new LinkedHashSet<>();
                inference.path("capabilities").forEach(value -> capabilities.add(value.asText()));
                InferenceRuntimeManifest manifest = new InferenceRuntimeManifest(summary.id(),
                        inference.path("engine").asText(), inference.path("engineVersion").asText(summary.version()),
                        inference.path("adapterVersion").asText(contribution.contractVersion()),
                        new InferenceRuntimeManifest.ProtocolVersion(inference.path("protocol").path("major").asInt(1),
                                inference.path("protocol").path("minor").asInt(0)),
                        inference.path("platform").asText("any"), inference.path("architecture").asText("any"),
                        inference.path("minimumJavaVersion").asInt(25), capabilities,
                        inference.has("parameterSchema") ? json.convertValue(inference.path("parameterSchema"), MAP) : Map.of(),
                        List.of(), inference.path("pureJavaFallback").asBoolean(true));
                var previous = catalog.runtime(summary.id());
                boolean usable = summary.state() != PluginManagementService.State.FAILED
                        && summary.state() != PluginManagementService.State.QUARANTINED
                        && summary.state() != PluginManagementService.State.REVOKED
                        && summary.state() != PluginManagementService.State.UNINSTALLING;
                catalog.saveRuntime(new InferenceCatalogPort.RuntimeInstallation(manifest,
                        "plugin:" + summary.id() + "/" + summary.artifactHash(),
                        usable ? InferenceCatalogPort.RuntimeState.INSTALLED : InferenceCatalogPort.RuntimeState.FAILED,
                        previous.map(InferenceCatalogPort.RuntimeInstallation::active).orElse(false),
                        previous.map(InferenceCatalogPort.RuntimeInstallation::installedAt).orElseGet(Instant::now)));
                if (usable && catalog.runtimes().stream().noneMatch(runtime -> runtime.active()
                        && runtime.manifest().engine().equals(manifest.engine()))) {
                    catalog.setActiveRuntime(manifest.engine(), summary.id());
                }
            } catch (Exception failure) {
                log.warn("MODEL 贡献推理元数据无效: {}: {}", summary.id(), failure.getMessage());
            }
        }
        for (var runtime : catalog.runtimes()) {
            if (runtime.installPath().startsWith("plugin:") && !installedIds.contains(runtime.manifest().runtimeId())) {
                catalog.saveRuntime(new InferenceCatalogPort.RuntimeInstallation(runtime.manifest(), runtime.installPath(),
                        InferenceCatalogPort.RuntimeState.FAILED, false, runtime.installedAt()));
            }
        }
    }

    static ContributionInfo modelContribution(List<ContributionInfo> contributions) {
        List<ContributionInfo> models = contributions.stream().filter(value -> "MODEL".equals(value.capability())).toList();
        if (models.size() != 1) throw new IllegalArgumentException("推理插件必须声明唯一 MODEL 贡献");
        return models.getFirst();
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        AutoCloseable statusSubscription;
        TaskScope scope;
        synchronized (lifecycleLock) {
            statusSubscription = subscription; subscription = null;
            scope = refreshTasks; refreshTasks = null;
        }
        pendingWorkspaces.clear();
        try { if (statusSubscription != null) statusSubscription.close(); }
        catch (Exception failure) { throw new IllegalStateException("关闭推理目录订阅失败", failure); }
        finally { if (scope != null) scope.close(); }
    }
}
