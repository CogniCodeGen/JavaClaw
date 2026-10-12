package com.javaclaw.application.plugin.v2;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** 插件中心与业务调用共享的 v2 入口；所有身份均由宿主传入。 */
public interface PluginManagementService extends AutoCloseable {
    List<PluginSummary> list(String workspaceId);
    PluginDetails details(String workspaceId, String pluginId);
    AutoCloseable observeStatus(Consumer<PluginStatusEvent> listener);
    AutoCloseable observeSecurityEvents(Consumer<PluginSecurityEvent> listener);
    PackagePreview inspectPackage(Path source);
    PackagePreview inspectPackage(URI source);
    PackagePreview inspectPackage(Path source, Consumer<InstallProgress> progress, BooleanSupplier cancelled);
    PackagePreview inspectPackage(URI source, Consumer<InstallProgress> progress, BooleanSupplier cancelled);
    default void discardPackagePreview(String previewId) { }
    PluginDetails install(String workspaceId, String previewId);
    List<PluginVersion> versions(String workspaceId, String pluginId);
    PluginDetails selectVersion(String workspaceId, String pluginId, String artifactHash, long expectedRevision);
    PluginDetails selectVersion(String workspaceId, String pluginId, String artifactHash, ConfigurationUpdate update);
    UninstallPreview previewUninstall(String workspaceId, String pluginId);
    void uninstall(String workspaceId, String pluginId, boolean deleteData);
    ConfigurationSnapshot configuration(String workspaceId, String pluginId);
    PluginDetails updateConfiguration(String workspaceId, String pluginId, ConfigurationUpdate update);
    void setEnabled(String workspaceId, String pluginId, boolean enabled);
    void start(String workspaceId, String pluginId);
    void stop(String workspaceId, String pluginId);
    void forceStop(String workspaceId, String pluginId);
    void restart(String workspaceId, String pluginId);
    void setStartupPolicy(String workspaceId, String pluginId, StartupPolicy policy);
    Diagnostics diagnostics(String workspaceId, String pluginId);
    void revoke(String workspaceId, String pluginId);
    void unquarantine(String workspaceId, String pluginId);
    void activateWorkspace(String workspaceId);
    void suspendWorkspace(String workspaceId);
    RunLease acquireRun(String workspaceId, String pluginId);
    RunLease acquireRun(String workspaceId, String pluginId, String artifactHash, long configurationRevision);
    PluginInvocation invoke(InvocationRequest request, Consumer<PluginEvent> events);
    default PluginInvocation stream(InvocationRequest request, Consumer<PluginEvent> events) {
        return invoke(request, events);
    }
    PluginInvocation invoke(RunLease lease, String capability, String contributionId,
                            String operation, String payloadJson, Duration timeout,
                            Consumer<PluginEvent> events);
    @Override void close();

    enum State { INSTALLED, STOPPED, STARTING, READY, DEGRADED, DRAINING, FAILED, QUARANTINED, REVOKED, UNINSTALLING }
    enum StartupPolicy { MANUAL, AUTO_START }
    enum SecretAction { KEEP, SET, CLEAR }
    enum SecurityReason { STOP, FORCE_STOP, REVOKE, PERMISSIONS_REDUCED, WORKSPACE_SUSPEND, UNINSTALL, UNINSTALL_DELETE_DATA }
    record SecretChange(SecretAction action, String value) {
        public SecretChange { action = action == null ? SecretAction.KEEP : action; value = value == null ? "" : value; }
        public static SecretChange keep() { return new SecretChange(SecretAction.KEEP, ""); }
        public static SecretChange set(String value) { return new SecretChange(SecretAction.SET, value); }
        public static SecretChange clear() { return new SecretChange(SecretAction.CLEAR, ""); }
    }
    record PluginSummary(String id, String name, String version, String description,
                         String publisher, String artifactHash, boolean signatureVerified,
                         boolean enabled, StartupPolicy startupPolicy, State state,
                         long configurationRevision, Set<String> capabilities, String lastError) {
        public PluginSummary { capabilities = Set.copyOf(capabilities); }
    }
    record PluginDetails(PluginSummary summary, String manifestJson,
                         ConfigurationSnapshot configuration, Set<String> requiredPermissions,
                         List<ContributionInfo> contributions, String uiSchemaJson) {
        public PluginDetails { requiredPermissions = Set.copyOf(requiredPermissions); contributions = List.copyOf(contributions); }
        public PluginDetails(PluginSummary summary, String manifestJson, ConfigurationSnapshot configuration,
                             Set<String> requiredPermissions, List<ContributionInfo> contributions) {
            this(summary, manifestJson, configuration, requiredPermissions, contributions, "{}");
        }
    }
    record ContributionInfo(String id, String capability, String contractVersion,
                            String name, String description, String inputSchemaJson,
                            String metadataJson, String outputSchemaJson) {
        public ContributionInfo(String id, String capability, String contractVersion,
                                String name, String description, String inputSchemaJson) {
            this(id, capability, contractVersion, name, description, inputSchemaJson, "{}", "{}");
        }
    }
    record ConfigurationSnapshot(long revision, String schemaJson, String configurationJson,
                                 Map<String, Boolean> configuredSecrets, Set<String> grantedPermissions) {
        public ConfigurationSnapshot { configuredSecrets = Map.copyOf(configuredSecrets); grantedPermissions = Set.copyOf(grantedPermissions); }
    }
    record ConfigurationUpdate(long expectedRevision, String configurationJson,
                               Map<String, SecretChange> secretChanges, Set<String> grantedPermissions) {
        public ConfigurationUpdate { secretChanges = Map.copyOf(secretChanges == null ? Map.of() : secretChanges); grantedPermissions = Set.copyOf(grantedPermissions == null ? Set.of() : grantedPermissions); }
    }
    record PackageFile(String path, long sizeBytes, String sha256) { }
    record PackagePreview(String previewId, String id, String name, String version,
                          String description, String publisher, String signerFingerprint,
                          String artifactHash, String manifestJson, String configurationSchemaJson,
                          Set<String> requiredPermissions, Set<String> capabilities,
                          List<PackageFile> files, boolean signatureVerified,
                          long sizeBytes, String source) {
        public PackagePreview { requiredPermissions = Set.copyOf(requiredPermissions); capabilities = Set.copyOf(capabilities); files = List.copyOf(files); }
    }
    record WorkspaceReference(String workspaceId, boolean enabled, long revision, int activeRuns) { }
    record PluginVersion(String id, String version, String artifactHash, String publisher,
                         boolean signatureVerified, boolean selected) { }
    record UninstallPreview(String pluginId, List<WorkspaceReference> workspaceReferences,
                            List<String> blockers, boolean dataRetainedByDefault) {
        public UninstallPreview { workspaceReferences = List.copyOf(workspaceReferences); blockers = List.copyOf(blockers); }
    }
    record Diagnostics(State state, String instanceId, long pid, String lastError,
                       List<String> logs, Map<String, String> health) {
        public Diagnostics { logs = List.copyOf(logs); health = Map.copyOf(health); }
    }
    record InstallProgress(String phase, long completedBytes, long totalBytes, String message) { }
    record PluginStatusEvent(String workspaceId, String pluginId, State state,
                             long revision, String message, Instant timestamp) { }
    record PluginSecurityEvent(String workspaceId, String pluginId, SecurityReason reason,
                               Set<String> revokedPermissions, Instant timestamp) {
        public PluginSecurityEvent { revokedPermissions = Set.copyOf(revokedPermissions); }
    }
    record InvocationRequest(String workspaceId, String pluginId, String capability,
                             String contributionId, String operation, String payloadJson, Duration timeout) { }
    record PluginEvent(String requestId, long sequence, String type, String payloadJson) { }
    record InvocationResult(String requestId, String payloadJson) { }
    interface PluginInvocation {
        String requestId();
        CompletableFuture<InvocationResult> completion();
        boolean cancel();
    }
    /** 租约固定工件、配置与实例；关闭前不被新配置替换。 */
    interface RunLease extends AutoCloseable {
        String runId();
        String workspaceId();
        String pluginId();
        String artifactHash();
        long configurationRevision();
        String instanceId();
        Set<String> grantedPermissions();
        List<ContributionInfo> contributions();
        String manifestJson();
        @Override void close();
    }
    final class PluginException extends RuntimeException {
        private final String code;
        public PluginException(String code, String message) { super(message); this.code = code; }
        public PluginException(String code, String message, Throwable cause) { super(message, cause); this.code = code; }
        public String code() { return code; }
    }
}
