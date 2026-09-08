package com.javaclaw.server.preview;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.ApprovalRequirement;
import com.javaclaw.api.AttachmentRef;
import com.javaclaw.api.AttachmentScope;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.FilePermission;
import com.javaclaw.api.ItemEnvelope;
import com.javaclaw.api.ItemPayload;
import com.javaclaw.api.ItemStatus;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.NetworkPermission;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ProcessPermission;
import com.javaclaw.api.ResourceLimits;
import com.javaclaw.api.ThreadExecutionIntent;
import com.javaclaw.api.ToolCatalogSnapshot;
import com.javaclaw.api.ToolPermission;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.Workspace;
import com.javaclaw.nativehost.sandbox.PlatformSandboxExecutor;
import com.javaclaw.protocol.AttachmentRpcContracts;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CoreItemCodecs;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.TurnContractFixtures;
import com.javaclaw.server.persistence.AttachmentService;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.CoreItemReader;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.H2Transactions;
import com.javaclaw.server.persistence.H2TurnJournal;
import com.javaclaw.server.persistence.ManagedWorktreeService;
import com.javaclaw.server.persistence.PermissionProfileService;
import com.javaclaw.server.turn.PreviewReadAuthority;

/** 真实 H2、来源 Item 与受限 Worker 的预览夹具；不调用模型，不以客户端路径授权文件。 */
abstract class PreviewServiceFixture {
    @TempDir
    Path temporary;

    final CanonicalJson json = new CanonicalJson();
    final Clock clock = Clock.systemUTC();
    H2Database database;
    CoreCommandService core;
    AttachmentService attachments;
    DocumentPreviewService previews;
    PermissionProfileService profiles;
    Workspace workspace;

    @BeforeEach
    void createServerOwnedSources() throws Exception {
        database = new H2Database(temporary.resolve("data-v6"));
        database.initialize();
        core = new CoreCommandService(database, json, clock);
        attachments = new AttachmentService(database, json, clock);
        var items = new CoreItemReader(database, json);
        var worktrees = new ManagedWorktreeService(database, attachments, json, clock, new PlatformSandboxExecutor());
        profiles = new PermissionProfileService(database, json, clock);
        var authority = new PreviewReadAuthority(core, items, worktrees, profiles, json);
        previews = new DocumentPreviewService(database.dataRoot(), items, attachments, authority, json, clock);
        workspace = workspace("first");
    }

    @AfterEach
    void closePreviewWorkersAndCache() throws Exception {
        if (previews != null) {
            previews.close();
        }
    }

    AttachmentRef attachment(byte[] content) throws Exception {
        String digest =
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        var scope = AttachmentScope.workspace(workspace.id());
        var input = new AttachmentRpcContracts.BeginPayload(scope, "text/plain", digest, content.length);
        var metadata = attachments.store(scope, identity("attachment/internal/store", input), "text/plain", content);
        return new AttachmentRef(metadata.digest(), metadata.mediaType(), "文档.txt", metadata.sizeBytes());
    }

    ItemEnvelope append(ItemEnvelope source, String schema, ItemPayload payload) {
        // 用 Core codec 写入真实来源，验证按 Turn/callId 查证而非相信客户端路径。
        var journal = new H2TurnJournal(database, CoreItemCodecs.createRegistry(json), json, clock);
        journal.append(source.turnId(), schema, schema, payload, ItemStatus.COMPLETED);
        var thread = core.findTurn(source.turnId()).orElseThrow().threadId();
        return core.listItems(thread).getLast();
    }

    PermissionProfile permission(long version, List<Path> roots) {
        return new PermissionProfile(
                "preview",
                version,
                new FilePermission(roots, List.of(), false, false),
                new NetworkPermission(Set.of(), Set.of(), true),
                new ProcessPermission(Set.of(), false, Duration.ofSeconds(30)),
                new ToolPermission(Set.of(), ToolRisk.READ_ONLY, ApprovalRequirement.NONE),
                new ResourceLimits(512L * 1024 * 1024, 1024 * 1024, 4, 128));
    }

    ItemEnvelope message(PermissionProfile permission, String text, List<AttachmentRef> references) throws Exception {
        var input = new CoreRpcContracts.ThreadCreatePayload(
                workspace.id(), Optional.empty(), ThreadExecutionIntent.WORKSPACE, "preview");
        var thread = core.createThread(
                identity("thread/create", input),
                workspace.id(),
                input.parentThreadId(),
                input.executionIntent(),
                input.title());
        var selection = new TurnContractFixtures.Selection(
                new TurnBudget(1000, 1000, 1, 0, Duration.ofSeconds(30)),
                TurnContractFixtures.ROLE,
                TurnContractFixtures.PROVIDER,
                new PermissionProfileRef(permission.id(), permission.version()));
        var catalog = new ToolCatalogSnapshot(TurnId.random(), 1, List.of(), permission, clock.instant());
        var request = TurnContractFixtures.request(
                thread.id(),
                selection,
                workspace.root(),
                TurnContractFixtures.PROMPT_SNAPSHOT,
                catalog,
                new CorePayloads.Message(MessageRole.USER, text, references, Optional.empty()),
                Optional.empty());
        var turn = core.startTurn(
                identity(
                        "turn/start",
                        new CoreRpcContracts.TurnStartPayload(
                                thread.id(), TurnContractFixtures.select(TurnContractFixtures.ROLE), text, references)),
                request);
        // 用已持久终态测试历史阅读，不借运行中的 Turn 重新授予权限，也不调用模型。
        new H2Transactions(database).execute(connection -> {
            try (var statement =
                    connection.prepareStatement("UPDATE CORE.AGENT_TURN SET STATUS = 'COMPLETED' WHERE ID = ?")) {
                statement.setString(1, turn.id().toString());
                statement.executeUpdate();
            }
            return null;
        });
        return core.listItems(thread.id()).getFirst();
    }

    Workspace workspace(String name) throws Exception {
        Path root = Files.createDirectory(temporary.resolve(name)).toRealPath();
        var input = new CoreRpcContracts.WorkspaceCreatePayload(name, root);
        return core.createWorkspace(identity("workspace/create", input), name, root);
    }

    CommandIdentity identity(String method, Object payload) {
        return CommandIdentity.from(
                method, new WriteCommand(UUID.randomUUID().toString(), 0, json.encode(payload)), json);
    }
}
