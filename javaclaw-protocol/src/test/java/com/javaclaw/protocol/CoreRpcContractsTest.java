package com.javaclaw.protocol;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AgentProfileRef;
import com.javaclaw.api.AgentProfileSpec;
import com.javaclaw.api.ApprovalDecision;
import com.javaclaw.api.AttachmentScope;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ProviderRole;
import com.javaclaw.api.ThreadExecutionIntent;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.WorkspaceId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreRpcContractsTest {
    private static final WorkspaceId WORKSPACE_ID = new WorkspaceId(new UUID(0, 1));
    private static final ThreadId THREAD_ID = new ThreadId(new UUID(0, 2));
    private static final TurnId TURN_ID = new TurnId(new UUID(0, 3));

    @TempDir
    Path temporaryDirectory;

    @Test
    void workspaceThreadTurn与查询契约规范化输入() {
        CoreRpcContracts.WorkspaceCreatePayload workspace =
                new CoreRpcContracts.WorkspaceCreatePayload(" Demo ", temporaryDirectory.resolve("a/.."));
        CoreRpcContracts.WorkspaceRenamePayload rename =
                new CoreRpcContracts.WorkspaceRenamePayload(WORKSPACE_ID, " Renamed ");
        CoreRpcContracts.ThreadCreatePayload thread = new CoreRpcContracts.ThreadCreatePayload(
                WORKSPACE_ID, Optional.empty(), ThreadExecutionIntent.WORKSPACE, " Topic ");
        CoreRpcContracts.TurnStartPayload turn = new CoreRpcContracts.TurnStartPayload(
                THREAD_ID, Optional.of(new AgentProfileRef("profile", 1)), "message");

        assertEquals("Demo", workspace.name());
        assertEquals("Renamed", rename.name());
        assertEquals(WORKSPACE_ID, new CoreRpcContracts.WorkspaceArchivePayload(WORKSPACE_ID).workspaceId());
        assertEquals(temporaryDirectory, workspace.root());
        assertEquals("Topic", thread.title());
        assertEquals("profile", turn.profile().orElseThrow().id());
        assertEquals(THREAD_ID, new CoreRpcContracts.ThreadQuery(THREAD_ID).threadId());
        assertEquals(TURN_ID, new CoreRpcContracts.TurnQuery(TURN_ID).turnId());
        assertEquals(WORKSPACE_ID, new CoreRpcContracts.WorkspaceQuery(WORKSPACE_ID).workspaceId());
    }

    @Test
    void workspaceThreadTurn拒绝空引用空文本和非法权限版本() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new CoreRpcContracts.WorkspaceCreatePayload(" ", temporaryDirectory));
        assertThrows(NullPointerException.class, () -> new CoreRpcContracts.WorkspaceCreatePayload("name", null));
        assertThrows(NullPointerException.class, () -> new CoreRpcContracts.WorkspaceRenamePayload(null, "name"));
        assertThrows(
                IllegalArgumentException.class, () -> new CoreRpcContracts.WorkspaceRenamePayload(WORKSPACE_ID, " "));
        assertThrows(NullPointerException.class, () -> new CoreRpcContracts.WorkspaceArchivePayload(null));
        assertThrows(
                NullPointerException.class,
                () -> new CoreRpcContracts.ThreadCreatePayload(
                        null, Optional.empty(), ThreadExecutionIntent.WORKSPACE, "title"));
        assertThrows(
                NullPointerException.class,
                () -> new CoreRpcContracts.ThreadCreatePayload(
                        WORKSPACE_ID, null, ThreadExecutionIntent.WORKSPACE, "title"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CoreRpcContracts.ThreadCreatePayload(
                        WORKSPACE_ID, Optional.empty(), ThreadExecutionIntent.ISOLATED_WRITE, "title"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CoreRpcContracts.ThreadCreatePayload(
                        WORKSPACE_ID, Optional.of(THREAD_ID), ThreadExecutionIntent.WORKSPACE, "title"));
        assertThrows(
                NullPointerException.class, () -> new CoreRpcContracts.TurnStartPayload(THREAD_ID, null, "message"));
        assertThrows(
                NullPointerException.class,
                () -> new CoreRpcContracts.TurnStartPayload(THREAD_ID, Optional.empty(), null));
        assertThrows(IllegalArgumentException.class, () -> new AgentProfileRef("profile", 0));
        assertThrows(NullPointerException.class, () -> new CoreRpcContracts.ThreadQuery(null));
        assertThrows(NullPointerException.class, () -> new CoreRpcContracts.TurnQuery(null));
        assertThrows(NullPointerException.class, () -> new CoreRpcContracts.WorkspaceQuery(null));
    }

    @Test
    void cancel与Item分页校验原因游标和页大小() {
        CoreRpcContracts.TurnCancelPayload cancel = new CoreRpcContracts.TurnCancelPayload(TURN_ID, " stop ");
        CoreRpcContracts.ItemList page = new CoreRpcContracts.ItemList(THREAD_ID, 0, 1_000);

        assertEquals("stop", cancel.reason());
        assertEquals(1_000, page.limit());
        assertThrows(
                IllegalArgumentException.class, () -> new CoreRpcContracts.TurnCancelPayload(TURN_ID, "x".repeat(501)));
        assertThrows(NullPointerException.class, () -> new CoreRpcContracts.TurnCancelPayload(null, "stop"));
        assertThrows(IllegalArgumentException.class, () -> new CoreRpcContracts.ItemList(THREAD_ID, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> new CoreRpcContracts.ItemList(THREAD_ID, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new CoreRpcContracts.ItemList(THREAD_ID, 0, 1_001));
        assertThrows(NullPointerException.class, () -> new CoreRpcContracts.ItemList(null, 0, 1));
    }

    @Test
    void attachment分块契约取得字节所有权并严格校验声明() {
        byte[] source = {1, 2, 3};
        AttachmentScope scope = AttachmentScope.workspace(WORKSPACE_ID);
        AttachmentRpcContracts.BeginPayload begin = new AttachmentRpcContracts.BeginPayload(
                scope, " application/octet-stream ", "A".repeat(64), source.length);
        AttachmentRpcContracts.ChunkPayload chunk =
                new AttachmentRpcContracts.ChunkPayload(scope, "00000000-0000-0000-0000-000000000001", 0, source);
        source[0] = 9;
        byte[] firstRead = chunk.content();
        firstRead[1] = 9;

        assertEquals("application/octet-stream", begin.mediaType());
        assertEquals("a".repeat(64), begin.expectedDigest());
        assertEquals(1, chunk.content()[0]);
        assertEquals(2, chunk.content()[1]);
        assertNotSame(firstRead, chunk.content());
        assertEquals(scope, begin.scope());
        assertEquals("a".repeat(64), new AttachmentRpcContracts.ReadPayload(scope, "A".repeat(64)).digest());
        assertThrows(
                NullPointerException.class,
                () -> new AttachmentRpcContracts.ChunkPayload(scope, "00000000-0000-0000-0000-000000000001", 0, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AttachmentRpcContracts.BeginPayload(scope, "text/plain", "bad", 1));
        assertThrows(
                IllegalArgumentException.class, () -> new AttachmentRpcContracts.ReadPayload(scope, "not-a-digest"));
    }

    @Test
    void attachmentScope在ProtocolJson中保留精确Workspace所有者() {
        AttachmentScope scope = AttachmentScope.workspace(WORKSPACE_ID);
        AttachmentRpcContracts.BeginPayload payload =
                new AttachmentRpcContracts.BeginPayload(scope, "text/plain", "a".repeat(64), 8);
        CanonicalJson json = new CanonicalJson();

        AttachmentRpcContracts.BeginPayload decoded =
                json.decode(json.encode(payload), AttachmentRpcContracts.BeginPayload.class);

        assertEquals(scope, decoded.scope());
        assertTrue(json.encode(payload).json().contains(WORKSPACE_ID.toString()));
    }

    @Test
    void attachment拒绝大单请求和越界总大小() {
        AttachmentScope scope = AttachmentScope.global();
        byte[] oversized = new byte[AttachmentRpcContracts.MAX_ATTACHMENT_CHUNK_BYTES + 1];
        assertThrows(
                IllegalArgumentException.class,
                () -> new AttachmentRpcContracts.ChunkPayload(
                        scope, "00000000-0000-0000-0000-000000000001", 0, oversized));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AttachmentRpcContracts.BeginPayload(
                        scope,
                        "application/octet-stream",
                        "a".repeat(64),
                        AttachmentRpcContracts.MAX_ATTACHMENT_BYTES + 1L));
    }

    @Test
    void configurationPermissionApproval与Rollout契约完整校验() {
        ProviderProfileRpcContracts.ProviderCreatePayload provider =
                new ProviderProfileRpcContracts.ProviderCreatePayload(" provider ", providerSpec());
        ProviderProfileRpcContracts.AgentProfileCreatePayload profile =
                new ProviderProfileRpcContracts.AgentProfileCreatePayload(" profile ", profileSpec());
        CoreRpcContracts.ApprovalResolvePayload approval =
                new CoreRpcContracts.ApprovalResolvePayload(" approval ", ApprovalDecision.APPROVED, " allowed ");
        CoreRpcContracts.RolloutExportPayload rollout =
                new CoreRpcContracts.RolloutExportPayload(THREAD_ID, temporaryDirectory.resolve("rollout.jsonl"));

        assertEquals("provider", provider.id());
        assertEquals("profile", profile.id());
        assertEquals("allowed", approval.reason());
        assertTrue(rollout.outputFile().isAbsolute());
        assertEquals(Optional.empty(), new CoreRpcContracts.ApprovalListPayload(Optional.empty(), false).turnId());
    }

    @Test
    void configurationPermissionApproval与Rollout拒绝无效字段() {
        assertThrows(
                NullPointerException.class, () -> new ProviderProfileRpcContracts.ProviderCreatePayload("id", null));
        assertThrows(
                NullPointerException.class,
                () -> new ProviderProfileRpcContracts.AgentProfileCreatePayload("id", null));
        assertThrows(NullPointerException.class, () -> new CoreRpcContracts.ApprovalListPayload(null, false));
        assertThrows(
                NullPointerException.class, () -> new CoreRpcContracts.ApprovalResolvePayload("id", null, "reason"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CoreRpcContracts.ApprovalResolvePayload("id", ApprovalDecision.DENIED, "x".repeat(501)));
        assertThrows(NullPointerException.class, () -> new CoreRpcContracts.RolloutExportPayload(THREAD_ID, null));
    }

    @Test
    void 所有Core列表结果复制输入并校验游标和附件结果() {
        assertTrue(
                new CoreRpcContracts.WorkspaceListResult(List.of()).workspaces().isEmpty());
        assertTrue(new CoreRpcContracts.ThreadListResult(List.of()).threads().isEmpty());
        assertTrue(new CoreRpcContracts.ItemListResult(List.of(), 0).items().isEmpty());
        assertTrue(
                new CoreRpcContracts.ApprovalListResult(List.of()).approvals().isEmpty());
        assertTrue(new ProviderProfileRpcContracts.ProviderListResult(List.of())
                .providers()
                .isEmpty());
        assertTrue(new ProviderProfileRpcContracts.AgentProfileListResult(List.of())
                .profiles()
                .isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new CoreRpcContracts.ItemListResult(List.of(), -1));
        assertThrows(NullPointerException.class, () -> new CoreRpcContracts.WorkspaceListResult(null));
    }

    private TurnBudget budget() {
        return new TurnBudget(1_000, 1_000, 10, 1, Duration.ofMinutes(1));
    }

    private ProviderEndpointSpec providerSpec() {
        return new ProviderEndpointSpec(
                "Provider",
                ProviderAdapter.OPENAI_COMPATIBLE,
                Optional.empty(),
                Set.of(ProviderRole.CHAT),
                List.of("model"),
                Optional.empty(),
                Duration.ofSeconds(30),
                1,
                Map.of());
    }

    private AgentProfileSpec profileSpec() {
        return new AgentProfileSpec(
                "Profile",
                "system",
                new ProviderRef("provider", 1, "model"),
                new PermissionProfileRef("default", 1),
                Set.of(),
                budget());
    }
}
