package com.javaclaw.server.rpc;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AgentRole;
import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.AgentRoleSpec;
import com.javaclaw.api.AttachmentScope;
import com.javaclaw.api.AttachmentUploadSession;
import com.javaclaw.api.CapabilityNarrowing;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.PermissionConstraint;
import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.RoleLifecycle;
import com.javaclaw.api.ThreadExecutionIntent;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceLifecycle;
import com.javaclaw.extension.spi.LoginStartupPort;
import com.javaclaw.protocol.AgentRoleRpcContracts;
import com.javaclaw.protocol.AttachmentRpcContracts;
import com.javaclaw.protocol.CapabilityAdvertisement;
import com.javaclaw.protocol.ClientInfo;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.protocol.DiagnosticsRpcContracts;
import com.javaclaw.protocol.InitializeParams;
import com.javaclaw.protocol.JsonRpcRequest;
import com.javaclaw.protocol.JsonRpcResponse;
import com.javaclaw.protocol.ProtocolErrorCode;
import com.javaclaw.protocol.ProtocolVersion;
import com.javaclaw.protocol.ProviderRpcContracts;
import com.javaclaw.protocol.RpcId;
import com.javaclaw.protocol.WorktreeRpcContracts;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.runtime.ModelCapabilities;
import com.javaclaw.runtime.ModelEventSink;
import com.javaclaw.runtime.ModelFinishReason;
import com.javaclaw.runtime.ModelGateway;
import com.javaclaw.runtime.ModelInvocation;
import com.javaclaw.runtime.ModelInvocationResult;
import com.javaclaw.runtime.ModelUsage;
import com.javaclaw.server.AppServerBootstrap;
import com.javaclaw.server.ProviderEndpointTestFixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreRpcHandlersCoverageTest {
    @TempDir
    Path temporaryDirectory;

    private final AtomicBoolean startupRequired = new AtomicBoolean();
    private AppServerBootstrap.Components components;
    private AppServerSession session;

    @BeforeEach
    void createServer() {
        LoginStartupPort startup = startupRequired::set;
        components = AppServerBootstrap.create(
                temporaryDirectory.resolve("data-v6"), Clock.systemUTC(), new NoOpModel(), startup);
        session = components.newSession();
        decode(invoke("initialize/session", initialization()), com.javaclaw.protocol.InitializeResult.class);
    }

    @AfterEach
    void closeServer() throws Exception {
        components.close();
    }

    @Test
    void workspace重命名归档与Thread查询均使用权威Revision() {
        Workspace workspace = createWorkspace("workspace-lifecycle");
        Workspace renamed = write(
                "workspace/rename",
                "rename-workspace",
                workspace.revision(),
                new CoreRpcContracts.WorkspaceRenamePayload(workspace.id(), "新名称"),
                Workspace.class);
        CoreRpcContracts.ThreadListResult empty = decode(
                invoke("thread/list", new CoreRpcContracts.WorkspaceQuery(workspace.id())),
                CoreRpcContracts.ThreadListResult.class);
        ConversationThread thread = createThread(renamed, "可读取对话");
        ConversationThread read =
                decode(invoke("thread/read", new CoreRpcContracts.ThreadQuery(thread.id())), ConversationThread.class);
        CoreRpcContracts.ThreadListResult listed = decode(
                invoke("thread/list", new CoreRpcContracts.WorkspaceQuery(workspace.id())),
                CoreRpcContracts.ThreadListResult.class);
        Workspace archived = write(
                "workspace/archive",
                "archive-workspace",
                renamed.revision(),
                new CoreRpcContracts.WorkspaceArchivePayload(workspace.id()),
                Workspace.class);

        assertTrue(empty.threads().isEmpty());
        assertEquals(thread, read);
        assertEquals(List.of(thread), listed.threads());
        assertEquals(WorkspaceLifecycle.ARCHIVED, archived.lifecycle());
        assertEquals(
                ProtocolErrorCode.INVALID_PARAMS,
                error("thread/read", new CoreRpcContracts.ThreadQuery(ThreadId.random())));
        assertEquals(
                ProtocolErrorCode.INVALID_PARAMS, error("turn/read", new CoreRpcContracts.TurnQuery(TurnId.random())));
        assertEquals(ProtocolErrorCode.INVALID_PARAMS, error("workspace/list", Map.of("unexpected", true)));
    }

    @Test
    void role和Provider完整更新后可归档且旧Revision仍可精确读取() {
        ProviderEndpointSpec providerSpec = providerSpec("Provider v1");
        ProviderEndpoint provider = write(
                "provider/create",
                "create-provider",
                0,
                new ProviderRpcContracts.ProviderCreatePayload("provider", providerSpec, ProviderLifecycle.ACTIVE),
                ProviderEndpoint.class);
        AgentRoleSpec profileSpec = roleSpec("Role v1");
        AgentRole profile = write(
                "agent/role/create",
                "create-profile",
                0,
                new AgentRoleRpcContracts.CreatePayload("profile", profileSpec),
                AgentRole.class);
        AgentRole updatedProfile = write(
                "agent/role/update",
                "update-profile",
                profile.revision(),
                new AgentRoleRpcContracts.UpdatePayload(profile.id(), roleSpec("Role v2"), RoleLifecycle.DISABLED),
                AgentRole.class);
        AgentRole archivedProfile = write(
                "agent/role/archive",
                "archive-profile",
                updatedProfile.revision(),
                new AgentRoleRpcContracts.ArchivePayload(profile.id()),
                AgentRole.class);
        ProviderEndpoint updatedProvider = write(
                "provider/update",
                "update-provider",
                provider.revision(),
                new ProviderRpcContracts.ProviderUpdatePayload(
                        provider.id(), providerSpec("Provider v2"), ProviderLifecycle.DISABLED),
                ProviderEndpoint.class);
        ProviderEndpoint archivedProvider = write(
                "provider/archive",
                "archive-provider",
                updatedProvider.revision(),
                new ProviderRpcContracts.ProviderArchivePayload(provider.id()),
                ProviderEndpoint.class);

        AgentRole original = decode(
                invoke("agent/role/read", new AgentRoleRpcContracts.ReadPayload(new AgentRoleRef(profile.id(), 1))),
                AgentRole.class);
        ProviderEndpoint originalProvider = decode(
                invoke("provider/read", new ProviderRpcContracts.ProviderReadPayload(provider.id(), 1)),
                ProviderEndpoint.class);
        assertEquals("Role v1", original.spec().name());
        assertEquals("Provider v1", originalProvider.spec().displayName());
        assertEquals(RoleLifecycle.ARCHIVED, archivedProfile.lifecycle());
        assertEquals(ProviderLifecycle.ARCHIVED, archivedProvider.lifecycle());
    }

    @Test
    void attachment上传可读取并原子中止且不同范围不可见() {
        Workspace workspace = createWorkspace("attachment-owner");
        AttachmentScope scope = AttachmentScope.workspace(workspace.id());
        AttachmentRpcContracts.BeginPayload begin =
                new AttachmentRpcContracts.BeginPayload(scope, "text/plain", "0".repeat(64), 1);
        AttachmentUploadSession opened =
                write("attachment/upload/begin", "begin-upload", 0, begin, AttachmentUploadSession.class);
        AttachmentUploadSession read = decode(
                invoke("attachment/upload/read", new AttachmentRpcContracts.UploadReadPayload(scope, opened.id())),
                AttachmentUploadSession.class);
        AttachmentUploadSession aborted = write(
                "attachment/upload/abort",
                "abort-upload",
                opened.revision(),
                new AttachmentRpcContracts.AbortPayload(scope, opened.id(), "用户取消"),
                AttachmentUploadSession.class);

        assertEquals(opened, read);
        assertEquals(com.javaclaw.api.AttachmentUploadState.ABORTED, aborted.state());
        assertEquals(
                ProtocolErrorCode.INVALID_PARAMS,
                error(
                        "attachment/upload/read",
                        new AttachmentRpcContracts.UploadReadPayload(AttachmentScope.global(), opened.id())));
    }

    @Test
    void 空Item游标Worktree空列表与登录启动修复都有稳定响应() {
        Workspace workspace = createWorkspace("empty-queries");
        ConversationThread thread = createThread(workspace, "空对话");
        CoreRpcContracts.ItemListResult items = decode(
                invoke("item/list", new CoreRpcContracts.ItemList(thread.id(), 17, 20)),
                CoreRpcContracts.ItemListResult.class);
        WorktreeRpcContracts.ListResult worktrees = decode(
                invoke("worktree/list", new WorktreeRpcContracts.ListPayload(workspace.id(), false)),
                WorktreeRpcContracts.ListResult.class);
        var diagnostics = write(
                "diagnostics/loginStartup/repair",
                "repair-startup",
                0,
                new DiagnosticsRpcContracts.LoginStartupRepairPayload(),
                com.javaclaw.api.DiagnosticsSnapshot.class);

        assertTrue(items.items().isEmpty());
        assertEquals(17, items.nextSequence());
        assertTrue(worktrees.worktrees().isEmpty());
        assertFalse(startupRequired.get());
        assertTrue(diagnostics.subsystems().schedule().repairAvailable());
    }

    private Workspace createWorkspace(String key) {
        return write(
                "workspace/create",
                "create-" + key,
                0,
                new CoreRpcContracts.WorkspaceCreatePayload(key, temporaryDirectory.resolve(key)),
                Workspace.class);
    }

    private ConversationThread createThread(Workspace workspace, String title) {
        return write(
                "thread/create",
                "create-thread-" + workspace.id(),
                0,
                new CoreRpcContracts.ThreadCreatePayload(
                        workspace.id(), Optional.empty(), ThreadExecutionIntent.WORKSPACE, title),
                ConversationThread.class);
    }

    private ProviderEndpointSpec providerSpec(String displayName) {
        return ProviderEndpointTestFixtures.chat(displayName, ProviderAdapter.OPENAI_COMPATIBLE, "test-model");
    }

    private AgentRoleSpec roleSpec(String name) {
        return new AgentRoleSpec(
                name,
                "职责",
                "指令",
                Optional.empty(),
                Optional.empty(),
                CapabilityNarrowing.inherit(),
                PermissionConstraint.INHERIT,
                Map.of());
    }

    private InitializeParams initialization() {
        return new InitializeParams(
                ProtocolVersion.CURRENT,
                new ClientInfo("core-handler-test", "5.0"),
                new CapabilityAdvertisement(Set.of("core.item-envelope"), Set.of()));
    }

    private <T> T write(String method, String key, long revision, Object payload, Class<T> type) {
        return decode(
                invoke(method, new WriteCommand(key, revision, components.json().encode(payload))), type);
    }

    private int error(String method, Object params) {
        return invoke(method, params).error().orElseThrow().code();
    }

    private JsonRpcResponse invoke(String method, Object params) {
        return session.handle(new JsonRpcRequest(
                new RpcId(method + "-request"), method, components.json().encode(params)));
    }

    private <T> T decode(JsonRpcResponse response, Class<T> type) {
        return components.json().decode(response.result().orElseThrow(), type);
    }

    private static final class NoOpModel implements ModelGateway {
        @Override
        public ModelCapabilities capabilities(String modelId) {
            return new ModelCapabilities(false, false, false, false, false, false, false);
        }

        @Override
        public ModelInvocationResult invoke(
                TurnId turnId,
                ModelInvocation invocation,
                ModelEventSink events,
                com.javaclaw.api.CancellationToken cancellation) {
            return new ModelInvocationResult(
                    "", List.of(), ModelUsage.zero(), Optional.empty(), Optional.empty(), ModelFinishReason.COMPLETE);
        }
    }
}
