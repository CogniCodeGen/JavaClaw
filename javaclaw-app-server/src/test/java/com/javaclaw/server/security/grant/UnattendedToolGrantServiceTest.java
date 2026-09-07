package com.javaclaw.server.security.grant;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.PermissionDecisionTrace;
import com.javaclaw.api.SecurityGrantKind;
import com.javaclaw.api.SecurityGrantState;
import com.javaclaw.api.ToolIdentity;
import com.javaclaw.api.UnattendedInvocationOutcome;
import com.javaclaw.api.UnattendedToolGrant;
import com.javaclaw.api.UnattendedToolGrantDraft;
import com.javaclaw.api.UnattendedToolGrantStatus;
import com.javaclaw.api.UnattendedToolInvocation;
import com.javaclaw.api.Workspace;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.protocol.SecurityGrantRpcContracts;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.PersistenceException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnattendedToolGrantServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
    private static final String SCHEMA_HASH = "a".repeat(64);

    @TempDir
    Path temporaryDirectory;

    private H2Database database;
    private CanonicalJson json;
    private MutableClock clock;
    private Workspace workspace;

    @BeforeEach
    void 初始化全新DataV6与Workspace() {
        database = new H2Database(temporaryDirectory.resolve("data-v6"));
        database.initialize();
        json = new CanonicalJson();
        clock = new MutableClock(NOW);
        CoreCommandService core = new CoreCommandService(database, json, clock);
        Path root = temporaryDirectory.resolve("workspace").toAbsolutePath();
        CoreRpcContracts.WorkspaceCreatePayload payload = new CoreRpcContracts.WorkspaceCreatePayload("Security", root);
        workspace = core.createWorkspace(identity("workspace/create", "workspace", 0, payload), "Security", root);
    }

    @Test
    void 无人值守授权冻结Schedule工具Schema参数并消费限额() {
        UnattendedToolGrantService service = new UnattendedToolGrantService(database, json, clock);
        UnattendedToolGrantDraft draft = draft(1, Duration.ofDays(1), Set.of("message"));
        SecurityGrantRpcContracts.UnattendedCreatePayload payload =
                new SecurityGrantRpcContracts.UnattendedCreatePayload(draft);
        CommandIdentity create = identity("unattendedToolGrant/create", "unattended-create", 0, payload);
        UnattendedToolGrant grant = service.create(create, draft);

        assertEquals(grant, service.create(create, draft));
        UnattendedToolInvocation invocation = invocation(grant, "occurrence-1", "world");
        assertTrue(service.authorizeAndConsume(invocation).allowed());
        service.recordOutcome(grant.id(), invocation.invocationId(), UnattendedInvocationOutcome.UNKNOWN_OUTCOME);

        PermissionDecisionTrace retry = service.authorizeAndConsume(invocation);
        assertFalse(retry.allowed());
        assertTrue(retry.steps().getLast().explanation().contains("UNKNOWN_OUTCOME"));
        PermissionDecisionTrace quota = service.authorizeAndConsume(invocation(grant, "occurrence-2", "again"));
        assertFalse(quota.allowed());
        assertTrue(quota.steps().stream().anyMatch(step -> step.source().equals("quota") && !step.allowed()));
        UnattendedToolGrantStatus status = service.listLatest(workspace.id()).getFirst();
        assertEquals(1, status.consumedUses());
        assertEquals(0, status.remainingUses());

        SecurityGrantAuditService audit = new SecurityGrantAuditService(database, json);
        assertEquals(
                3,
                audit.list(workspace.id(), Optional.of(SecurityGrantKind.UNATTENDED_TOOL), Optional.of(grant.id()), 10)
                        .size());
    }

    @Test
    void 无人值守模板拒绝敏感可变槽明文Secret与身份漂移() {
        UnattendedToolGrantService service = new UnattendedToolGrantService(database, json, clock);
        assertCreateRejected(service, "sensitive-slot", draft(2, Duration.ofDays(1), Set.of("recipient")));
        UnattendedToolGrantDraft inlineSecret = new UnattendedToolGrantDraft(
                workspace.id(),
                "nightly",
                4,
                tool(),
                8,
                SCHEMA_HASH,
                json.parse("{\"api_key\":\"plain\",\"message\":\"hello\"}"),
                Set.of("message"),
                2,
                Duration.ofDays(1));
        assertCreateRejected(service, "inline-secret", inlineSecret);
        assertCreateRejected(service, "too-long", draft(2, Duration.ofDays(31), Set.of("message")));

        UnattendedToolGrant grant = createGrant(service, "identity-grant", 2);
        UnattendedToolInvocation wrongCatalog = new UnattendedToolInvocation(
                workspace.id(),
                grant.id(),
                grant.revision(),
                grant.scheduleId(),
                grant.scheduleRevision(),
                grant.tool(),
                grant.catalogRevision() + 1,
                grant.schemaHash(),
                json.parse("{\"message\":\"world\",\"recipient\":\"fixed@example.invalid\"}"),
                "wrong-catalog");
        PermissionDecisionTrace denied = service.authorizeAndConsume(wrongCatalog);
        assertFalse(denied.allowed());
        assertEquals(0, service.listLatest(workspace.id()).getFirst().consumedUses());
    }

    @Test
    void 崩溃恢复把预留标为Unknown并且撤销立即阻止新调用() {
        UnattendedToolGrantService service = new UnattendedToolGrantService(database, json, clock);
        UnattendedToolGrant grant = createGrant(service, "recovery-grant", 3);
        UnattendedToolInvocation reserved = invocation(grant, "recovery-occurrence", "run");
        assertTrue(service.authorizeAndConsume(reserved).allowed());

        UnattendedToolGrantService restarted = new UnattendedToolGrantService(database, json, clock);
        assertEquals(1, restarted.recoverUnknownOutcomes());
        assertFalse(restarted.authorizeAndConsume(reserved).allowed());

        SecurityGrantRpcContracts.GrantRevokePayload revoke =
                new SecurityGrantRpcContracts.GrantRevokePayload(grant.id());
        UnattendedToolGrant tombstone =
                restarted.revoke(identity("unattendedToolGrant/revoke", "unattended-revoke", 1, revoke), grant.id());
        assertEquals(SecurityGrantState.REVOKED, tombstone.state());
        assertEquals(List.of(grant, tombstone), restarted.history(grant.id()));
        assertFalse(restarted
                .authorizeAndConsume(invocation(grant, "after-revoke", "run"))
                .allowed());
    }

    @Test
    void 无人值守强制入口和终态账本均保持幂等并拒绝不同结果() {
        UnattendedToolGrantService service = new UnattendedToolGrantService(database, json, clock);
        UnattendedToolGrant grant = createGrant(service, "terminal-ledger", 3);
        UnattendedToolInvocation invocation = invocation(grant, "terminal-invocation", "run");

        assertTrue(service.requireAndConsume(invocation).allowed());
        service.recordOutcome(grant.id(), invocation.invocationId(), UnattendedInvocationOutcome.SUCCEEDED);
        service.recordOutcome(grant.id(), invocation.invocationId(), UnattendedInvocationOutcome.SUCCEEDED);
        assertThrows(
                PersistenceException.class,
                () -> service.recordOutcome(grant.id(), invocation.invocationId(), UnattendedInvocationOutcome.FAILED));
        PermissionDecisionTrace consumed = service.authorizeAndConsume(invocation);
        assertFalse(consumed.allowed());
        assertTrue(consumed.denialReason().orElseThrow().contains("消费额度"));

        UnattendedToolInvocation missing = new UnattendedToolInvocation(
                workspace.id(),
                "missing",
                1,
                grant.scheduleId(),
                grant.scheduleRevision(),
                grant.tool(),
                grant.catalogRevision(),
                grant.schemaHash(),
                invocation.arguments(),
                "missing-invocation");
        assertThrows(SecurityException.class, () -> service.requireAndConsume(missing));

        SecurityGrantRpcContracts.GrantRevokePayload payload =
                new SecurityGrantRpcContracts.GrantRevokePayload(grant.id());
        CommandIdentity revoke = identity("unattendedToolGrant/revoke", "terminal-revoke", 1, payload);
        UnattendedToolGrant revoked = service.revoke(revoke, grant.id());
        assertEquals(revoked, service.revoke(revoke, grant.id()));
        assertThrows(
                PersistenceException.class,
                () -> service.revoke(
                        identity("unattendedToolGrant/revoke", "terminal-revoke-again", 2, payload), grant.id()));
    }

    private void assertCreateRejected(UnattendedToolGrantService service, String key, UnattendedToolGrantDraft draft) {
        SecurityGrantRpcContracts.UnattendedCreatePayload payload =
                new SecurityGrantRpcContracts.UnattendedCreatePayload(draft);
        assertThrows(
                IllegalArgumentException.class,
                () -> service.create(identity("unattendedToolGrant/create", key, 0, payload), draft));
    }

    private UnattendedToolGrant createGrant(UnattendedToolGrantService service, String key, int maximumUses) {
        UnattendedToolGrantDraft draft = draft(maximumUses, Duration.ofDays(1), Set.of("message"));
        return service.create(
                identity(
                        "unattendedToolGrant/create",
                        key,
                        0,
                        new SecurityGrantRpcContracts.UnattendedCreatePayload(draft)),
                draft);
    }

    private UnattendedToolGrantDraft draft(int maximumUses, Duration validity, Set<String> variableFields) {
        return new UnattendedToolGrantDraft(
                workspace.id(),
                "nightly",
                4,
                tool(),
                8,
                SCHEMA_HASH,
                json.parse("{\"message\":\"hello\",\"recipient\":\"fixed@example.invalid\"}"),
                variableFields,
                maximumUses,
                validity);
    }

    private UnattendedToolInvocation invocation(UnattendedToolGrant grant, String invocationId, String message) {
        return new UnattendedToolInvocation(
                workspace.id(),
                grant.id(),
                grant.revision(),
                grant.scheduleId(),
                grant.scheduleRevision(),
                grant.tool(),
                grant.catalogRevision(),
                grant.schemaHash(),
                json.parse("{\"message\":\"" + message + "\",\"recipient\":\"fixed@example.invalid\"}"),
                invocationId);
    }

    private static ToolIdentity tool() {
        return new ToolIdentity("schedule-extension", "notify", 3);
    }

    private CommandIdentity identity(String method, String key, long revision, Object payload) {
        return CommandIdentity.from(method, new WriteCommand(key, revision, json.encode(payload)), json);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("test clock only supports UTC");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
