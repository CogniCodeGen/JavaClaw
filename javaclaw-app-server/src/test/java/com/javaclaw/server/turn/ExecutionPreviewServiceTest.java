package com.javaclaw.server.turn;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AgentRole;
import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.AgentRoleSpec;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.CapabilityNarrowing;
import com.javaclaw.api.ConfigurationSource;
import com.javaclaw.api.ExecutionBlocker;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.ExecutionPreview;
import com.javaclaw.api.ModelPreference;
import com.javaclaw.api.PermissionConstraint;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ReasoningPreference;
import com.javaclaw.api.RoleLifecycle;
import com.javaclaw.api.SandboxCommand;
import com.javaclaw.api.SandboxExecutor;
import com.javaclaw.api.SandboxResult;
import com.javaclaw.api.SandboxSession;
import com.javaclaw.api.ThreadExecutionIntent;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.ProviderEndpointTestFixtures;
import com.javaclaw.server.persistence.AgentRoleService;
import com.javaclaw.server.persistence.AttachmentService;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.ExecutionConfigurationService;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.ManagedWorktreeService;
import com.javaclaw.server.persistence.PermissionProfileService;
import com.javaclaw.server.persistence.ProviderService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionPreviewServiceTest {
    @TempDir
    Path directory;

    private final CanonicalJson json = new CanonicalJson();
    private final Clock clock = Clock.systemUTC();
    private CoreCommandService core;
    private ProviderService providers;
    private AgentRoleService roles;
    private ExecutionConfigurationService configurations;
    private ExecutionPreviewService previews;
    private Workspace workspace;
    private ProviderEndpoint endpoint;

    @BeforeEach
    void initialize() {
        H2Database database = new H2Database(directory.resolve("data-v6"));
        database.initialize();
        core = new CoreCommandService(database, json, clock);
        providers = new ProviderService(database, ignored -> true, json, clock);
        roles = new AgentRoleService(database, providers, json, clock);
        configurations = new ExecutionConfigurationService(database, core, roles, json, clock);
        PermissionProfileService permissions = new PermissionProfileService(database, json, clock);
        permissions.installStandardProfile();
        var worktrees = new ManagedWorktreeService(
                database, new AttachmentService(database, json, clock), json, clock, new UnusedSandbox());
        previews = new ExecutionPreviewService(core, roles, configurations, permissions, worktrees, providers);
        workspace = core.createWorkspace(identity("workspace", 0), "项目", directory.resolve("project"));
        endpoint = providers.create(
                identity("provider", 0),
                "models",
                ProviderEndpointTestFixtures.chat("本地接口", ProviderAdapter.OPENAI_COMPATIBLE, "a", "b"),
                ProviderLifecycle.ACTIVE);
    }

    @Test
    void 未选择模型以正常阻塞返回并保留默认Agent() {
        ExecutionPreview result = preview(ExecutionOverrides.empty());

        assertEquals(Optional.of(new AgentRoleRef("default", 1)), result.role());
        assertTrue(result.provider().isEmpty());
        assertBlocked(result, ExecutionBlocker.Code.MODEL_REQUIRED);
        assertTrue(configurations
                .find(Optional.of(workspace.id()), Optional.empty())
                .isEmpty());
    }

    @Test
    void 最近选择只初始化新对话而手改项目默认仍影响继承中的旧对话() {
        configurations.ensureInstallationDefaults(selection(null, provider("a"), ReasoningPreference.HIGH));
        var existing = core.createThread(
                identity("existing-thread", 0),
                workspace.id(),
                Optional.empty(),
                ThreadExecutionIntent.WORKSPACE,
                "没有覆盖的旧对话");
        ExecutionPreview before =
                previews.preview(workspace.id(), Optional.of(existing.id()), ExecutionOverrides.empty());
        configurations.updateRecent(
                identity("recent-model", 0), selection(null, provider("b"), ReasoningPreference.LOW));

        assertEquals(before, previews.preview(workspace.id(), Optional.of(existing.id()), ExecutionOverrides.empty()));
        assertEquals(
                Optional.of(provider("a")), preview(ExecutionOverrides.empty()).provider());
        var created = core.createThread(
                identity("new-thread", 0),
                workspace.id(),
                Optional.empty(),
                ThreadExecutionIntent.WORKSPACE,
                "使用最近选择的新对话");
        configurations.update(
                identity("initialize-recent", 0),
                Optional.of(workspace.id()),
                Optional.of(created.id()),
                configurations.findRecent().orElseThrow().overrides());
        ExecutionPreview fresh =
                previews.preview(workspace.id(), Optional.of(created.id()), ExecutionOverrides.empty());
        assertEquals(Optional.of(provider("b")), fresh.provider());
        assertEquals(Optional.of(ReasoningPreference.LOW), fresh.reasoning());

        configurations.update(
                identity("manual-project-default", 0),
                Optional.of(workspace.id()),
                Optional.empty(),
                selection(null, provider("b"), ReasoningPreference.NONE));
        ExecutionPreview inherited =
                previews.preview(workspace.id(), Optional.of(existing.id()), ExecutionOverrides.empty());
        assertEquals(Optional.of(provider("b")), inherited.provider());
        assertEquals(Optional.of(ReasoningPreference.NONE), inherited.reasoning());
        assertEquals(
                Optional.of(ReasoningPreference.LOW),
                previews.preview(workspace.id(), Optional.of(created.id()), ExecutionOverrides.empty())
                        .reasoning());
    }

    @Test
    void 来源优先级与正式执行一致且关闭思考不变为继承() {
        configurations.ensureInstallationDefaults(selection(null, provider("a"), ReasoningPreference.HIGH));
        configurations.update(
                identity("workspace-defaults", 0),
                Optional.of(workspace.id()),
                Optional.empty(),
                selection(null, provider("b"), ReasoningPreference.LOW));
        var thread = core.createThread(
                identity("thread", 0), workspace.id(), Optional.empty(), ThreadExecutionIntent.WORKSPACE, "对话");
        configurations.update(
                identity("thread-defaults", 0),
                Optional.of(workspace.id()),
                Optional.of(thread.id()),
                selection(null, provider("a"), ReasoningPreference.NONE));

        ExecutionPreview inherited =
                previews.preview(workspace.id(), Optional.of(thread.id()), ExecutionOverrides.empty());
        ExecutionPreview explicit = previews.preview(
                workspace.id(), Optional.of(thread.id()), selection(null, provider("b"), ReasoningPreference.MEDIUM));

        assertTrue(inherited.ready());
        assertFalse(inherited.modelLocked());
        assertFalse(inherited.reasoningLocked());
        assertEquals(Optional.of(provider("a")), inherited.provider());
        assertEquals(Optional.of(ReasoningPreference.NONE), inherited.reasoning());
        assertTrue(inherited.provenance().stream()
                .anyMatch(value -> value.field().equals("provider") && value.source() == ConfigurationSource.THREAD));
        assertEquals(Optional.of(provider("b")), explicit.provider());
        assertEquals(Optional.of(ReasoningPreference.MEDIUM), explicit.reasoning());
    }

    @Test
    void 固定Agent覆盖模型和思考且停用后返回原因() {
        AgentRole role = roles.create(
                identity("role", 0),
                "fixed",
                new AgentRoleSpec(
                        "编程助手",
                        "说明",
                        "仅执行明确任务。",
                        Optional.of(new ModelPreference(provider("a"))),
                        Optional.of(ReasoningPreference.HIGH),
                        CapabilityNarrowing.inherit(),
                        PermissionConstraint.INHERIT,
                        Map.of()));
        ExecutionOverrides selection = selection(role.ref(), provider("b"), ReasoningPreference.LOW);

        ExecutionPreview result = preview(selection);

        assertTrue(result.ready());
        assertTrue(result.modelLocked());
        assertTrue(result.reasoningLocked());
        assertEquals(Optional.of(provider("a")), result.provider());
        assertEquals(Optional.of(ReasoningPreference.HIGH), result.reasoning());
        assertTrue(result.provenance().stream().anyMatch(value -> value.source() == ConfigurationSource.ROLE));
        assertFalse(json.encode(result).json().contains("仅执行明确任务"));
        roles.update(identity("disable-role", 1), role.id(), role.spec(), RoleLifecycle.DISABLED);
        assertBlocked(preview(selection), ExecutionBlocker.Code.ROLE_UNAVAILABLE);
    }

    @Test
    void 普通预览保留历史模型版本并实时检查连接停用() {
        providers.update(
                identity("provider-update", 1),
                endpoint.id(),
                ProviderEndpointTestFixtures.chat("新名称", ProviderAdapter.OPENAI_COMPATIBLE, "b"),
                ProviderLifecycle.ACTIVE);
        ExecutionPreview unchanged = preview(selection(null, provider("a"), null));

        assertTrue(unchanged.ready());
        assertEquals(Optional.of(provider("a")), unchanged.provider());
        providers.update(identity("provider-disable", 2), endpoint.id(), endpoint.spec(), ProviderLifecycle.DISABLED);
        assertBlocked(preview(selection(null, provider("a"), null)), ExecutionBlocker.Code.DISABLED);
        providers.archive(identity("provider-archive", 3), endpoint.id());
        assertBlocked(preview(selection(null, provider("a"), null)), ExecutionBlocker.Code.ARCHIVED);
    }

    @Test
    void 缺失精确版本和不支持聊天的模型不会变成就绪() {
        assertBlocked(
                preview(selection(null, new ProviderRef("models", 99, "a"), null)),
                ExecutionBlocker.Code.MODEL_UNAVAILABLE);
        assertBlocked(preview(selection(null, provider("missing"), null)), ExecutionBlocker.Code.INVALID_CONFIGURATION);
        ProviderEndpoint embeddings = providers.create(
                identity("embedding", 0),
                "embeddings",
                ProviderEndpointTestFixtures.embedding("向量接口", ProviderAdapter.OPENAI_COMPATIBLE, "embed"),
                ProviderLifecycle.ACTIVE);
        assertBlocked(
                preview(selection(null, new ProviderRef(embeddings.id(), 1, "embed"), null)),
                ExecutionBlocker.Code.MODEL_UNAVAILABLE);
    }

    @Test
    void 权限配置不可用时保留已解析模型和阻塞原因() {
        ExecutionOverrides invalid = new ExecutionOverrides(
                Optional.empty(),
                Optional.of(provider("a")),
                Optional.of(new PermissionProfileRef("missing", 1)),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());

        ExecutionPreview result = preview(invalid);

        assertEquals(Optional.of(provider("a")), result.provider());
        assertBlocked(result, ExecutionBlocker.Code.CONFIGURATION_INVALID);
    }

    @Test
    void 工作区不可用和跨工作区对话不泄漏任何执行配置() {
        assertBlocked(
                previews.preview(WorkspaceId.random(), Optional.empty(), ExecutionOverrides.empty()),
                ExecutionBlocker.Code.WORKSPACE_UNAVAILABLE);
        Workspace other = core.createWorkspace(identity("other", 0), "另一个项目", directory.resolve("other"));
        var foreign = core.createThread(
                identity("foreign", 0), other.id(), Optional.empty(), ThreadExecutionIntent.WORKSPACE, "其他项目对话");

        ExecutionPreview result =
                previews.preview(workspace.id(), Optional.of(foreign.id()), selection(null, provider("a"), null));

        assertBlocked(result, ExecutionBlocker.Code.THREAD_UNAVAILABLE);
        assertTrue(result.provider().isEmpty());
        assertTrue(result.provenance().isEmpty());
        assertBlocked(
                previews.preview(workspace.id(), Optional.of(ThreadId.random()), ExecutionOverrides.empty()),
                ExecutionBlocker.Code.THREAD_UNAVAILABLE);
        core.archiveWorkspace(identity("archive-workspace", 1), workspace.id());
        assertBlocked(preview(ExecutionOverrides.empty()), ExecutionBlocker.Code.WORKSPACE_UNAVAILABLE);
    }

    @Test
    void 隔离写对话未准备执行根时仅返回阻塞而不运行进程() {
        var root = core.createThread(
                identity("root-thread", 0), workspace.id(), Optional.empty(), ThreadExecutionIntent.WORKSPACE, "根对话");
        var isolated = core.createThread(
                identity("isolated-thread", 0),
                workspace.id(),
                Optional.of(root.id()),
                ThreadExecutionIntent.ISOLATED_WRITE,
                "隔离对话");

        assertBlocked(
                previews.preview(workspace.id(), Optional.of(isolated.id()), selection(null, provider("a"), null)),
                ExecutionBlocker.Code.THREAD_UNAVAILABLE);
    }

    private ExecutionPreview preview(ExecutionOverrides execution) {
        return previews.preview(workspace.id(), Optional.empty(), execution);
    }

    private static void assertBlocked(ExecutionPreview result, ExecutionBlocker.Code code) {
        assertFalse(result.ready());
        assertEquals(
                List.of(code),
                result.blockers().stream().map(ExecutionBlocker::code).toList());
    }

    private static ExecutionOverrides selection(
            AgentRoleRef role, ProviderRef provider, ReasoningPreference reasoning) {
        return new ExecutionOverrides(
                Optional.ofNullable(role),
                Optional.ofNullable(provider),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.ofNullable(reasoning));
    }

    private static ProviderRef provider(String model) {
        return new ProviderRef("models", 1, model);
    }

    private CommandIdentity identity(String key, long revision) {
        return new CommandIdentity(
                "test/preview", key, revision, json.encode(Map.of("key", key)).sha256());
    }

    private static final class UnusedSandbox implements SandboxExecutor {
        @Override
        public SandboxResult execute(
                SandboxCommand command, PermissionProfile permission, CancellationToken cancellation) {
            throw new AssertionError("配置预览不得执行进程");
        }

        @Override
        public SandboxSession open(
                SandboxCommand command, PermissionProfile permission, CancellationToken cancellation) {
            throw new AssertionError("配置预览不得打开进程");
        }
    }
}
