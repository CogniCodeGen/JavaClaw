package com.javaclaw.server.coding;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.CoreTools;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ThreadExecutionIntent;
import com.javaclaw.api.ToolCatalogSnapshot;
import com.javaclaw.api.ToolPermission;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.TurnId;
import com.javaclaw.builtin.contracts.CodingContracts;
import com.javaclaw.builtin.extensions.BuiltinExtensions;
import com.javaclaw.nativehost.sandbox.PlatformSandboxExecutor;
import com.javaclaw.protocol.CoreItemCodecs;
import com.javaclaw.runtime.DefaultTurnHarness;
import com.javaclaw.runtime.ModelGateway;
import com.javaclaw.runtime.TurnExecutionCommand;
import com.javaclaw.runtime.TurnHarnessServices;
import com.javaclaw.server.TurnContractFixtures;
import com.javaclaw.server.extension.BuiltinExtensionHost;
import com.javaclaw.server.persistence.ApprovalService;
import com.javaclaw.server.persistence.AttachmentService;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.ExtensionCatalogRepository;
import com.javaclaw.server.persistence.H2TurnJournal;
import com.javaclaw.server.persistence.ManagedWorktreeService;
import com.javaclaw.server.persistence.ProviderStateService;
import com.javaclaw.server.persistence.TurnStartRequest;
import com.javaclaw.server.security.grant.UnattendedToolGrantService;
import com.javaclaw.server.turn.ExtensionToolPlatform;
import com.javaclaw.server.turn.H2ConversationContext;

/** 真实 H2、Coding Host、治理平台和 DefaultTurnHarness；仅模型与工具链定位使用确定性替身。 */
final class CodingHarnessFixture implements AutoCloseable {
    final CodingTestFixture base;
    final PermissionProfile permission;
    final ConversationThread thread;
    final H2TurnJournal journal;
    final DefaultTurnHarness harness;
    private final BuiltinExtensionHost host;
    private final ExtensionToolPlatform tools;
    private final ApprovalService approvals;

    CodingHarnessFixture(Path directory, ModelGateway model) throws Exception {
        this(new CodingTestFixture(directory), model);
    }

    /** 接管调用方已配置的真实平台夹具；关闭 Harness 时一起释放，不替换权限或原生执行器。 */
    CodingHarnessFixture(CodingTestFixture base, ModelGateway model) throws Exception {
        this.base = base;
        permission = permission();
        var catalog = new ExtensionCatalogRepository(base.database, base.json, base.clock);
        var bundles = BuiltinExtensions.create().stream()
                .filter(bundle -> bundle.descriptor().id().value().equals(CodingContracts.EXTENSION_ID))
                .toList();
        host = BuiltinExtensionHost.start(
                bundles,
                base.core,
                base.profiles,
                CodingHarnessPorts.create(base, catalog),
                Optional.of(base.platform));
        approvals = new ApprovalService(base.database, base.json, base.clock);
        var attachments = new AttachmentService(base.database, base.json, base.clock);
        var worktrees = new ManagedWorktreeService(
                base.database, attachments, base.json, base.clock, new PlatformSandboxExecutor());
        tools = new ExtensionToolPlatform(new ExtensionToolPlatform.Dependencies(
                host,
                base.core,
                approvals,
                base.profiles,
                worktrees,
                catalog,
                base.json,
                base.clock,
                new UnattendedToolGrantService(base.database, base.json, base.clock),
                Optional.empty()));
        var schemas = CoreItemCodecs.createRegistry(base.json);
        journal = new H2TurnJournal(base.database, schemas, base.json, base.clock);
        var contexts = new H2ConversationContext(base.core, new ProviderStateService(base.database), schemas, model);
        harness = new DefaultTurnHarness(
                new TurnHarnessServices(
                        model,
                        contexts,
                        (command, window, gateway, cancellation) -> {
                            throw new AssertionError("unexpected compaction");
                        },
                        tools,
                        tools,
                        journal,
                        (turn, event, cancellation) -> {},
                        base.platform),
                base.clock);
        thread = base.core.createThread(
                base.identity("thread/create", "harness-thread", Map.of()),
                base.workspace.id(),
                Optional.empty(),
                ThreadExecutionIntent.WORKSPACE,
                "统一聊天与编程验收");
    }

    TurnExecutionCommand queued(String key, String message) {
        return queued(key, message, Duration.ofMinutes(3));
    }

    TurnExecutionCommand queued(String key, String message, Duration wallTime) {
        var catalog = tools.freeze(TurnId.random(), base.workspace.id(), permission, new CancellationSource());
        var selection = new TurnContractFixtures.Selection(
                new TurnBudget(500_000, 20_000, 20, 0, wallTime),
                TurnContractFixtures.ROLE,
                TurnContractFixtures.PROVIDER,
                new PermissionProfileRef(permission.id(), permission.version()));
        var configuration =
                TurnContractFixtures.configuration(selection, TurnContractFixtures.PROMPT_SNAPSHOT, catalog);
        var request = new TurnStartRequest(
                thread.id(),
                configuration,
                base.root,
                TurnContractFixtures.PROMPT_SNAPSHOT,
                catalog,
                new CorePayloads.Message(MessageRole.USER, message, List.of(), Optional.empty()),
                Optional.empty(),
                Optional.of(
                        base.core.codingEnvironments().frozen(base.turn.id()).inherited()));
        AgentTurn turn = base.core.startTurn(base.identity("turn/start", key, request), request);
        var frozen = base.json.decode(base.core.toolCatalogSnapshot(turn.id()), ToolCatalogSnapshot.class);
        return new TurnExecutionCommand(turn, turn.provider(), "测试固定平台指令", message, permission, frozen);
    }

    private PermissionProfile permission() {
        var previous = base.permission;
        var names = new java.util.HashSet<>(previous.tools().allowedTools());
        names.add(CoreTools.SEARCH_NAME);
        var updated = new PermissionProfile(
                previous.id(),
                previous.version() + 1,
                previous.files(),
                previous.network(),
                previous.processes(),
                new ToolPermission(
                        names, previous.tools().maximumRisk(), previous.tools().approvalRequirement()),
                previous.resources());
        return base.profiles.update(
                new CommandIdentity(
                        "permissionProfile/update",
                        "harness-tools",
                        previous.version(),
                        base.json.encode(updated).sha256()),
                updated);
    }

    @Override
    public void close() throws Exception {
        Exception failure = CodingCleanup.close(null, approvals, host, base);
        if (failure != null) {
            throw failure;
        }
    }
}
