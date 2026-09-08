package com.javaclaw.server;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Objects;

import com.javaclaw.extension.spi.LoginStartupPort;
import com.javaclaw.nativehost.credential.MasterKeyProtector;
import com.javaclaw.nativehost.sandbox.PlatformSandboxExecutor;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.instructions.ProjectInstructionResolver;
import com.javaclaw.server.lifecycle.ApprovalExpirationCoordinator;
import com.javaclaw.server.lifecycle.ApprovalLifecycleCoordinator;
import com.javaclaw.server.lifecycle.InputLifecycleCoordinator;
import com.javaclaw.server.lifecycle.LifecycleCoordinator;
import com.javaclaw.server.mcp.McpBuiltinExtensionDescriptor;
import com.javaclaw.server.persistence.AgentRoleService;
import com.javaclaw.server.persistence.ApprovalService;
import com.javaclaw.server.persistence.AttachmentService;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.EmbeddingBindingService;
import com.javaclaw.server.persistence.ExecutionConfigurationService;
import com.javaclaw.server.persistence.ExtensionCatalogRepository;
import com.javaclaw.server.persistence.ExtensionJobInputCoordinator;
import com.javaclaw.server.persistence.ExtensionJobService;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.InputRequestService;
import com.javaclaw.server.persistence.LifecycleLeaseRepository;
import com.javaclaw.server.persistence.ManagedWorktreeService;
import com.javaclaw.server.persistence.PermissionProfileService;
import com.javaclaw.server.persistence.ProviderCredentialService;
import com.javaclaw.server.persistence.ProviderService;
import com.javaclaw.server.security.grant.PrivateNetworkGrantService;
import com.javaclaw.server.security.grant.SecurityGrantAuditService;
import com.javaclaw.server.security.grant.UnattendedToolGrantService;
import com.javaclaw.server.security.vault.SecretVaultService;

/** 创建 App Server 基础服务并执行启动恢复，避免主组合根堆积具体初始化细节。 */
final class PlatformFoundationFactory {
    private PlatformFoundationFactory() {}

    /**
     * 按依赖顺序创建 data-v6 基础服务。
     *
     * @param dataRoot data-v6 根目录
     * @param clock 平台时钟
     * @param masterKeys 系统主密钥封装端口
     * @param loginStartup 用户登录启动项端口
     * @return 已完成启动恢复的基础服务
     */
    static AppServerBootstrap.Foundation create(
            Path dataRoot, Clock clock, MasterKeyProtector masterKeys, LoginStartupPort loginStartup) {
        Clock requiredClock = Objects.requireNonNull(clock, "clock");
        CanonicalJson json = new CanonicalJson();
        H2Database database = new H2Database(dataRoot);
        database.initialize();
        CoreServices core = coreServices(database, json, requiredClock, masterKeys);
        ManagementServices management = managementServices(database, json, requiredClock, core);
        return foundation(
                database, json, requiredClock, Objects.requireNonNull(loginStartup, "loginStartup"), core, management);
    }

    private static CoreServices coreServices(
            H2Database database, CanonicalJson json, Clock clock, MasterKeyProtector masterKeys) {
        SecretVaultService vault = new SecretVaultService(
                database, Objects.requireNonNull(masterKeys, "masterKeys"), json, clock, new SecureRandom());
        PermissionProfileService permissions = new PermissionProfileService(database, json, clock);
        permissions.installStandardProfile();
        CoreCommandService commands = new CoreCommandService(database, json, clock);
        LifecycleCoordinator lifecycle = new LifecycleCoordinator(new LifecycleLeaseRepository(database, clock));
        ApprovalService approvals = new ApprovalService(database, json, clock);
        ApprovalLifecycleCoordinator approvalLifecycle = new ApprovalLifecycleCoordinator(approvals, lifecycle, clock);
        ApprovalExpirationCoordinator approvalExpiration = new ApprovalExpirationCoordinator(approvals, clock);
        AttachmentService attachments = new AttachmentService(database, json, clock);
        return new CoreServices(
                vault, permissions, commands, approvals, approvalExpiration, approvalLifecycle, lifecycle, attachments);
    }

    private static ManagementServices managementServices(
            H2Database database, CanonicalJson json, Clock clock, CoreServices core) {
        ProviderService providers = new ProviderService(database, core.vault(), json, clock);
        ProviderCredentialService credentials =
                new ProviderCredentialService(providers, core.vault().providerCredentials(), json, clock);
        EmbeddingBindingService embeddingBinding = new EmbeddingBindingService(database, providers, json, clock);
        AgentRoleService profiles = new AgentRoleService(database, providers, json, clock);
        ExecutionConfigurationService bindings =
                new ExecutionConfigurationService(database, core.commands(), profiles, json, clock);
        ManagedWorktreeService worktrees =
                new ManagedWorktreeService(database, core.attachments(), json, clock, new PlatformSandboxExecutor());
        worktrees.reconcileProvisioning();
        InputRequestService inputs = new InputRequestService(database, json, clock);
        InputLifecycleCoordinator inputLifecycle = new InputLifecycleCoordinator(inputs, core.lifecycle(), clock);
        ExtensionJobService jobs = new ExtensionJobService(database, json, clock);
        ExtensionJobInputCoordinator jobInputs = new ExtensionJobInputCoordinator(database, inputs, clock);
        ExtensionCatalogRepository extensions = new ExtensionCatalogRepository(database, json, clock);
        extensions.installBuiltIn(McpBuiltinExtensionDescriptor.create());
        return new ManagementServices(
                providers,
                credentials,
                embeddingBinding,
                profiles,
                bindings,
                worktrees,
                inputs,
                inputLifecycle,
                jobs,
                jobInputs,
                extensions,
                securityServices(database, json, clock));
    }

    private static SecurityServices securityServices(H2Database database, CanonicalJson json, Clock clock) {
        PrivateNetworkGrantService privateGrants = new PrivateNetworkGrantService(database, json, clock);
        UnattendedToolGrantService unattendedGrants = new UnattendedToolGrantService(database, json, clock);
        unattendedGrants.recoverUnknownOutcomes();
        return new SecurityServices(privateGrants, unattendedGrants, new SecurityGrantAuditService(database, json));
    }

    private static AppServerBootstrap.Foundation foundation(
            H2Database database,
            CanonicalJson json,
            Clock clock,
            LoginStartupPort loginStartup,
            CoreServices core,
            ManagementServices management) {
        SecurityServices security = management.security();
        return new AppServerBootstrap.Foundation(
                json,
                database,
                new com.javaclaw.server.persistence.TurnStreamService(database, json),
                previews(database, json, clock, core, management),
                core.commands(),
                core.permissions(),
                core.approvals(),
                core.approvalExpiration(),
                core.approvalLifecycle(),
                core.attachments(),
                management.providers(),
                management.credentials(),
                management.embeddingBinding(),
                management.profiles(),
                management.bindings(),
                core.vault(),
                management.worktrees(),
                management.inputs(),
                management.inputLifecycle(),
                management.jobs(),
                management.jobInputs(),
                management.extensions(),
                security.privateGrants(),
                security.unattendedGrants(),
                security.audit(),
                new ProjectInstructionResolver(database.dataRoot().getParent(), clock),
                core.lifecycle(),
                loginStartup,
                clock,
                clock.instant());
    }

    private static com.javaclaw.server.preview.DocumentPreviewService previews(
            H2Database database, CanonicalJson json, Clock clock, CoreServices core, ManagementServices management) {
        var items = new com.javaclaw.server.persistence.CoreItemReader(database, json);
        var authority = new com.javaclaw.server.turn.PreviewReadAuthority(
                core.commands(), items, management.worktrees(), core.permissions(), json);
        try {
            return new com.javaclaw.server.preview.DocumentPreviewService(
                    database.dataRoot(), items, core.attachments(), authority, json, clock);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("文档预览缓存初始化失败", failure);
        }
    }

    private record CoreServices(
            SecretVaultService vault,
            PermissionProfileService permissions,
            CoreCommandService commands,
            ApprovalService approvals,
            ApprovalExpirationCoordinator approvalExpiration,
            ApprovalLifecycleCoordinator approvalLifecycle,
            LifecycleCoordinator lifecycle,
            AttachmentService attachments) {}

    private record ManagementServices(
            ProviderService providers,
            ProviderCredentialService credentials,
            EmbeddingBindingService embeddingBinding,
            AgentRoleService profiles,
            ExecutionConfigurationService bindings,
            ManagedWorktreeService worktrees,
            InputRequestService inputs,
            InputLifecycleCoordinator inputLifecycle,
            ExtensionJobService jobs,
            ExtensionJobInputCoordinator jobInputs,
            ExtensionCatalogRepository extensions,
            SecurityServices security) {}

    private record SecurityServices(
            PrivateNetworkGrantService privateGrants,
            UnattendedToolGrantService unattendedGrants,
            SecurityGrantAuditService audit) {}
}
