package com.javaclaw.client.sdk;

import java.io.IOException;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import com.javaclaw.client.RpcClientConnection;
import com.javaclaw.client.ServerNotification;
import com.javaclaw.client.extension.BuiltinExtensionClients;
import com.javaclaw.client.extension.ExtensionClient;
import com.javaclaw.client.facade.AgentClient;
import com.javaclaw.client.facade.AgentRoleClient;
import com.javaclaw.client.facade.ApprovalClient;
import com.javaclaw.client.facade.AttachmentClient;
import com.javaclaw.client.facade.BuiltinExtensionManagementClient;
import com.javaclaw.client.facade.CredentialClient;
import com.javaclaw.client.facade.DiagnosticsClient;
import com.javaclaw.client.facade.ExecutionClient;
import com.javaclaw.client.facade.ExtensionBundleClient;
import com.javaclaw.client.facade.ExtensionJobClient;
import com.javaclaw.client.facade.InputClient;
import com.javaclaw.client.facade.InstructionClient;
import com.javaclaw.client.facade.ItemClient;
import com.javaclaw.client.facade.McpClient;
import com.javaclaw.client.facade.PermissionProfileClient;
import com.javaclaw.client.facade.PromptManifestClient;
import com.javaclaw.client.facade.PromptOptimizationClient;
import com.javaclaw.client.facade.ProviderClient;
import com.javaclaw.client.facade.RolloutClient;
import com.javaclaw.client.facade.SecurityGrantClient;
import com.javaclaw.client.facade.ThreadClient;
import com.javaclaw.client.facade.ToolClient;
import com.javaclaw.client.facade.TurnClient;
import com.javaclaw.client.facade.WorkspaceClient;
import com.javaclaw.client.facade.WorktreeClient;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CapabilityAdvertisement;
import com.javaclaw.protocol.ClientInfo;
import com.javaclaw.protocol.InitializeParams;
import com.javaclaw.protocol.InitializeResult;
import com.javaclaw.protocol.LocalTransport;
import com.javaclaw.protocol.ProtocolVersion;
import com.javaclaw.protocol.SealedSecret;
import com.javaclaw.protocol.SessionSecretSealer;
import com.javaclaw.protocol.StableCapabilities;

/** JavaClaw Protocol v3 的强类型 Java SDK 入口。 */
public final class JavaClawClient implements AutoCloseable {
    private static final Set<String> STABLE_CAPABILITIES = StableCapabilities.withMcp();

    private final RpcClientConnection connection;
    private final InitializeResult server;
    private final WorkspaceClient workspaces;
    private final ThreadClient threads;
    private final TurnClient turns;
    private final com.javaclaw.client.facade.TurnStreamClient streams;
    private final com.javaclaw.client.facade.DocumentPreviewClient documents;
    private final ItemClient items;
    private final InputClient inputs;
    private final AttachmentClient attachments;
    private final CredentialClient credentials;
    private final AgentRoleClient roles;
    private final AgentClient agents;
    private final ExecutionClient executions;
    private final PromptManifestClient prompts;
    private final ProviderClient providers;
    private final PromptOptimizationClient promptOptimizations;
    private final PermissionProfileClient permissionProfiles;
    private final ApprovalClient approvals;
    private final SecurityGrantClient securityGrants;
    private final ToolClient tools;
    private final McpClient mcp;
    private final RolloutClient rollouts;
    private final WorktreeClient worktrees;
    private final InstructionClient instructions;
    private final DiagnosticsClient diagnostics;
    private final ExtensionClient extensions;
    private final ExtensionBundleClient extensionBundles;
    private final ExtensionJobClient extensionJobs;
    private final BuiltinExtensionManagementClient builtinExtensionManagement;
    private final BuiltinExtensionClients builtins;

    private JavaClawClient(RpcClientConnection connection, InitializeResult server) {
        this.connection = connection;
        this.server = server;
        workspaces = new WorkspaceClient(connection);
        threads = new ThreadClient(connection);
        turns = new TurnClient(connection);
        streams = new com.javaclaw.client.facade.TurnStreamClient(connection, server.capabilities());
        documents = new com.javaclaw.client.facade.DocumentPreviewClient(connection);
        items = new ItemClient(connection);
        inputs = new InputClient(connection);
        attachments = new AttachmentClient(connection);
        credentials = new CredentialClient(connection, server.secretKey());
        roles = new AgentRoleClient(connection);
        agents = new AgentClient(connection);
        executions = new ExecutionClient(connection);
        prompts = new PromptManifestClient(connection);
        providers = new ProviderClient(connection, server.secretKey());
        promptOptimizations = new PromptOptimizationClient(connection);
        permissionProfiles = new PermissionProfileClient(connection);
        approvals = new ApprovalClient(connection);
        securityGrants = new SecurityGrantClient(connection);
        tools = new ToolClient(connection);
        mcp = new McpClient(connection);
        rollouts = new RolloutClient(connection);
        worktrees = new WorktreeClient(connection);
        instructions = new InstructionClient(connection);
        diagnostics = new DiagnosticsClient(connection);
        extensions = new ExtensionClient(connection);
        extensionBundles = new ExtensionBundleClient(connection);
        extensionJobs = new ExtensionJobClient(connection);
        builtinExtensionManagement = new BuiltinExtensionManagementClient(connection);
        builtins = new BuiltinExtensionClients(extensions);
    }

    /**
     * 连接本地 App Server 并完成 Protocol v3 协商。
     *
     * @param transport stdio、UDS 或 Named Pipe 实现
     * @param clientInfo 客户端身份
     * @param experimental 主动请求的实验能力
     * @param notifications 强类型服务端通知消费者
     * @return 已初始化 SDK
     * @throws IOException 建立连接失败
     */
    public static JavaClawClient connect(
            LocalTransport transport,
            ClientInfo clientInfo,
            Set<String> experimental,
            Consumer<ServerNotification> notifications)
            throws IOException {
        Objects.requireNonNull(transport, "transport");
        Objects.requireNonNull(notifications, "notifications");
        CanonicalJson json = new CanonicalJson();
        RpcClientConnection connection = new RpcClientConnection(
                transport.connect(),
                json,
                notification -> notifications.accept(ServerNotificationDecoder.decode(json, notification)));
        try {
            InitializeParams params = new InitializeParams(
                    ProtocolVersion.CURRENT,
                    clientInfo,
                    new CapabilityAdvertisement(STABLE_CAPABILITIES, Set.copyOf(experimental)));
            InitializeResult result = connection.query("initialize/session", params, InitializeResult.class);
            return new JavaClawClient(connection, result);
        } catch (RuntimeException failure) {
            try {
                connection.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    /**
     * 返回服务端协商结果。
     *
     * @return 初始化快照
     */
    public InitializeResult server() {
        return server;
    }

    /**
     * 在进入 JSON-RPC 前使用当前连接公钥封装 Secret。
     *
     * @param purpose 目标写方法定义的精确用途
     * @param secret PasswordField 等敏感字符；调用方应在返回后清零
     * @return 可安全序列化的密文 envelope
     */
    public SealedSecret sealSecret(String purpose, char[] secret) {
        return SessionSecretSealer.seal(server.secretKey(), purpose, secret);
    }

    /** @return Workspace facade */
    public WorkspaceClient workspaces() {
        return workspaces;
    }

    /** @return Thread facade */
    public ThreadClient threads() {
        return threads;
    }

    /** @return Turn facade */
    public TurnClient turns() {
        return turns;
    }

    /** @return 复用当前连接并保留恢复快照的公开聊天流 */
    public com.javaclaw.client.facade.TurnStreamClient streams() {
        return streams;
    }

    /** @return 当前连接拥有的受控文档预览 */
    public com.javaclaw.client.facade.DocumentPreviewClient documents() {
        return documents;
    }

    /** @return Item facade */
    public ItemClient items() {
        return items;
    }

    /** @return Turn 用户输入 facade */
    public InputClient inputs() {
        return inputs;
    }

    /** @return Attachment facade */
    public AttachmentClient attachments() {
        return attachments;
    }

    /** @return 加密 Secret Vault facade */
    public CredentialClient credentials() {
        return credentials;
    }

    /** @return Agent Role 配置 facade */
    public AgentRoleClient roles() {
        return roles;
    }

    /** @return 使用父级权限和预算的子智能体协作 */
    public AgentClient agents() {
        return agents;
    }

    /** @return 安装、Workspace 和 Thread 独立执行配置 */
    public ExecutionClient executions() {
        return executions;
    }

    /** @return 分层 Prompt 来源预览 */
    public PromptManifestClient prompts() {
        return prompts;
    }

    /** @return Provider 配置 facade */
    public ProviderClient providers() {
        return providers;
    }

    /** @return Agent Role Prompt 优化 facade */
    public PromptOptimizationClient promptOptimizations() {
        return promptOptimizations;
    }

    /** @return PermissionProfile facade */
    public PermissionProfileClient permissionProfiles() {
        return permissionProfiles;
    }

    /** @return Approval facade */
    public ApprovalClient approvals() {
        return approvals;
    }

    /** @return 私网、无人值守授权与权限决策 facade */
    public SecurityGrantClient securityGrants() {
        return securityGrants;
    }

    /** @return 当前权限可见工具目录 facade */
    public ToolClient tools() {
        return tools;
    }

    /** @return MCP Endpoint、健康与 Catalog facade */
    public McpClient mcp() {
        return mcp;
    }

    /** @return Rollout facade */
    public RolloutClient rollouts() {
        return rollouts;
    }

    /** @return Worktree facade */
    public WorktreeClient worktrees() {
        return worktrees;
    }

    /** @return 项目约定脱敏解析 facade */
    public InstructionClient instructions() {
        return instructions;
    }

    /** @return Diagnostics facade */
    public DiagnosticsClient diagnostics() {
        return diagnostics;
    }

    /** @return 通用 Extension facade */
    public ExtensionClient extensions() {
        return extensions;
    }

    /** @return 第三方 Bundle 管理 facade */
    public ExtensionBundleClient extensionBundles() {
        return extensionBundles;
    }

    /** @return 可恢复 Extension Job facade */
    public ExtensionJobClient extensionJobs() {
        return extensionJobs;
    }

    /** @return 内置 Bundle 与 MCP 平台能力管理 facade */
    public BuiltinExtensionManagementClient builtinExtensionManagement() {
        return builtinExtensionManagement;
    }

    /** @return 内置扩展强类型 facade */
    public BuiltinExtensionClients builtins() {
        return builtins;
    }

    /** 关闭 SDK 连接。 */
    @Override
    public void close() throws IOException {
        streams.close();
        connection.close();
    }
}
