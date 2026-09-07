package com.javaclaw.server.security.grant;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.PrivateNetworkGrant;
import com.javaclaw.api.PrivateNetworkGrantPreview;
import com.javaclaw.api.PrivateNetworkPurpose;
import com.javaclaw.api.SecurityGrantState;
import com.javaclaw.api.ToolIdentity;
import com.javaclaw.api.UnattendedInvocationOutcome;
import com.javaclaw.api.UnattendedToolGrant;
import com.javaclaw.api.UnattendedToolGrantDraft;
import com.javaclaw.api.UnattendedToolInvocation;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.PersistenceException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SecurityGrantDecisionBranchesTest {
    private static final Instant NOW = Instant.parse("2026-09-02T05:00:00Z");
    private static final String SCHEMA_HASH = "a".repeat(64);

    @TempDir
    Path temporaryDirectory;

    private H2Database database;
    private CanonicalJson json;
    private MutableClock clock;
    private Workspace workspace;
    private Workspace otherWorkspace;

    @BeforeEach
    void 创建DataV6与两个有效Workspace() {
        database = new H2Database(temporaryDirectory.resolve("data-v6"));
        database.initialize();
        json = new CanonicalJson();
        clock = new MutableClock(NOW);
        CoreCommandService core = new CoreCommandService(database, json, clock);
        workspace = createWorkspace(core, "primary");
        otherWorkspace = createWorkspace(core, "other");
    }

    @Test
    void 私网决策覆盖WorkspaceOriginRevision过期与撤销幂等分支() {
        PrivateNetworkGrantService service = new PrivateNetworkGrantService(database, json, clock);
        PrivateNetworkGrantPreview preview = service.preview(
                workspace.id(),
                PrivateNetworkPurpose.MCP,
                URI.create("https://mcp.example"),
                Set.of("10.0.0.8"),
                Optional.of(Duration.ofMinutes(5)));
        PrivateNetworkGrant grant =
                service.create(identity("privateNetworkGrant/create", "private-create", 0, preview), preview);

        assertFalse(service.evaluate(
                        grant.id(),
                        grant.revision() + 1,
                        workspace.id(),
                        grant.purpose(),
                        grant.origin(),
                        grant.dnsAddresses())
                .allowed());
        assertFalse(service.evaluate(
                        grant.id(),
                        grant.revision(),
                        otherWorkspace.id(),
                        grant.purpose(),
                        grant.origin(),
                        grant.dnsAddresses())
                .allowed());
        assertFalse(service.evaluate(
                        grant.id(),
                        grant.revision(),
                        workspace.id(),
                        grant.purpose(),
                        URI.create("https://other.example"),
                        grant.dnsAddresses())
                .allowed());
        clock.advance(Duration.ofMinutes(6));
        assertFalse(service.evaluate(
                        grant.id(),
                        grant.revision(),
                        workspace.id(),
                        grant.purpose(),
                        grant.origin(),
                        grant.dnsAddresses())
                .allowed());

        CommandIdentity revoke = identity("privateNetworkGrant/revoke", "private-revoke", 1, grant);
        PrivateNetworkGrant revoked = service.revoke(revoke, grant.id());
        assertEquals(revoked, service.revoke(revoke, grant.id()));
        assertEquals(SecurityGrantState.REVOKED, revoked.state());
        assertThrows(
                PersistenceException.class,
                () -> service.revoke(
                        identity("privateNetworkGrant/revoke", "private-missing", 1, Map.of()), "missing"));
        assertThrows(IllegalArgumentException.class, () -> service.history("bad id"));
    }

    @Test
    void 无人值守决策逐项拒绝漂移身份且拒绝缺失终态预留() {
        UnattendedToolGrantService service = new UnattendedToolGrantService(database, json, clock);
        UnattendedToolGrant grant = createGrant(service, workspace.id(), "matrix", 10, Duration.ofDays(1));

        assertDenied(service, invocation(grant, "revision", InvocationDrift.GRANT_REVISION));
        assertDenied(service, invocation(grant, "workspace", InvocationDrift.WORKSPACE));
        assertDenied(service, invocation(grant, "schedule-id", InvocationDrift.SCHEDULE_ID));
        assertDenied(service, invocation(grant, "schedule-revision", InvocationDrift.SCHEDULE_REVISION));
        assertDenied(service, invocation(grant, "tool", InvocationDrift.TOOL));
        assertDenied(service, invocation(grant, "catalog", InvocationDrift.CATALOG));
        assertDenied(service, invocation(grant, "schema", InvocationDrift.SCHEMA));
        assertDenied(service, invocation(grant, "arguments", InvocationDrift.ARGUMENTS));

        assertThrows(
                PersistenceException.class,
                () -> service.recordOutcome(grant.id(), "missing-reservation", UnattendedInvocationOutcome.FAILED));
        assertThrows(IllegalArgumentException.class, () -> service.history("bad id"));
        assertThrows(
                PersistenceException.class,
                () -> service.revoke(
                        identity("unattendedToolGrant/revoke", "unattended-missing", 1, Map.of()), "missing"));

        clock.advance(Duration.ofDays(2));
        assertDenied(service, invocation(grant, "expired", InvocationDrift.NONE));
    }

    @Test
    void 无人值守创建要求存在Workspace和零ExpectedRevision() {
        UnattendedToolGrantService service = new UnattendedToolGrantService(database, json, clock);
        UnattendedToolGrantDraft missing = draft(WorkspaceId.random(), 2, Duration.ofHours(1));

        assertThrows(
                PersistenceException.class,
                () -> service.create(identity("unattendedToolGrant/create", "missing-workspace", 0, missing), missing));

        UnattendedToolGrantDraft valid = draft(workspace.id(), 2, Duration.ofHours(1));
        assertThrows(
                PersistenceException.class,
                () -> service.create(identity("unattendedToolGrant/create", "wrong-revision", 1, valid), valid));
    }

    private Workspace createWorkspace(CoreCommandService core, String suffix) {
        Path root = temporaryDirectory.resolve(suffix).toAbsolutePath();
        CoreRpcContracts.WorkspaceCreatePayload payload = new CoreRpcContracts.WorkspaceCreatePayload(suffix, root);
        return core.createWorkspace(identity("workspace/create", "workspace-" + suffix, 0, payload), suffix, root);
    }

    private UnattendedToolGrant createGrant(
            UnattendedToolGrantService service,
            WorkspaceId workspaceId,
            String key,
            int maximumUses,
            Duration validity) {
        UnattendedToolGrantDraft draft = draft(workspaceId, maximumUses, validity);
        return service.create(identity("unattendedToolGrant/create", key, 0, draft), draft);
    }

    private UnattendedToolGrantDraft draft(WorkspaceId workspaceId, int maximumUses, Duration validity) {
        return new UnattendedToolGrantDraft(
                workspaceId,
                "nightly",
                4,
                tool(),
                8,
                SCHEMA_HASH,
                arguments("fixed@example.invalid"),
                Set.of("message"),
                maximumUses,
                validity);
    }

    private UnattendedToolInvocation invocation(UnattendedToolGrant grant, String invocationId, InvocationDrift drift) {
        return new UnattendedToolInvocation(
                drift == InvocationDrift.WORKSPACE ? otherWorkspace.id() : workspace.id(),
                grant.id(),
                drift == InvocationDrift.GRANT_REVISION ? grant.revision() + 1 : grant.revision(),
                drift == InvocationDrift.SCHEDULE_ID ? "other" : grant.scheduleId(),
                drift == InvocationDrift.SCHEDULE_REVISION ? 5 : 4,
                drift == InvocationDrift.TOOL ? new ToolIdentity("schedule-extension", "other", 3) : tool(),
                drift == InvocationDrift.CATALOG ? 9 : 8,
                drift == InvocationDrift.SCHEMA ? "b".repeat(64) : SCHEMA_HASH,
                arguments(drift == InvocationDrift.ARGUMENTS ? "changed@example.invalid" : "fixed@example.invalid"),
                invocationId);
    }

    private com.javaclaw.api.CanonicalPayload arguments(String recipient) {
        return json.parse("{\"message\":\"hello\",\"recipient\":\"" + recipient + "\"}");
    }

    private static ToolIdentity tool() {
        return new ToolIdentity("schedule-extension", "notify", 3);
    }

    private static void assertDenied(UnattendedToolGrantService service, UnattendedToolInvocation invocation) {
        assertFalse(service.authorizeAndConsume(invocation).allowed());
    }

    private enum InvocationDrift {
        NONE,
        GRANT_REVISION,
        WORKSPACE,
        SCHEDULE_ID,
        SCHEDULE_REVISION,
        TOOL,
        CATALOG,
        SCHEMA,
        ARGUMENTS
    }

    private CommandIdentity identity(String method, String key, long expectedRevision, Object payload) {
        return CommandIdentity.from(method, new WriteCommand(key, expectedRevision, json.encode(payload)), json);
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("测试时钟只支持 UTC");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
