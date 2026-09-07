package com.javaclaw.server.persistence;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.ApprovalPolicy;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.ExecutionConfiguration;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ReasoningPreference;
import com.javaclaw.api.ThreadExecutionIntent;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.protocol.CanonicalJson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionConfigurationBoundaryTest {
    @TempDir
    Path temporaryDirectory;

    private final CanonicalJson json = new CanonicalJson();
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);
    private CoreCommandService core;
    private ExecutionConfigurationService configurations;

    @BeforeEach
    void initialize() {
        H2Database database = new H2Database(temporaryDirectory.resolve("data-v6"));
        database.initialize();
        ProviderService providers = new ProviderService(database, reference -> true, json, clock);
        AgentRoleService roles = new AgentRoleService(database, providers, json, clock);
        core = new CoreCommandService(database, json, clock);
        configurations = new ExecutionConfigurationService(database, core, roles, json, clock);
    }

    @Test
    void updatesReplaceOnlyTheirOwnScopeAndReplayKeepsTheOriginalRevision() {
        Workspace workspace = workspace("main");
        ConversationThread thread = thread(workspace);
        ExecutionConfiguration installed = configurations.ensureInstallationDefaults(select("default"));
        ExecutionOverrides workspaceChoice = select("worker");
        ExecutionConfiguration workspaceDefaults = configurations.update(
                identity("workspace-default", 0, workspaceChoice),
                Optional.of(workspace.id()),
                Optional.empty(),
                workspaceChoice);
        ExecutionOverrides threadChoice = select("explorer");
        CommandIdentity original = identity("thread-default", 0, threadChoice);
        ExecutionConfiguration first =
                configurations.update(original, Optional.of(workspace.id()), Optional.of(thread.id()), threadChoice);
        assertEquals(
                List.of(installed, workspaceDefaults, first),
                configurations.findForTurn(workspace.id(), Optional.of(thread.id())));
        ExecutionConfiguration second = configurations.update(
                identity("clear-thread", 1, ExecutionOverrides.empty()),
                Optional.of(workspace.id()),
                Optional.of(thread.id()),
                ExecutionOverrides.empty());
        assertEquals(2, second.revision());
        assertEquals(ExecutionOverrides.empty(), second.overrides());
        assertEquals(
                first,
                configurations.update(original, Optional.of(workspace.id()), Optional.of(thread.id()), threadChoice));
        assertEquals(
                List.of(installed, workspaceDefaults, second),
                configurations.findForTurn(workspace.id(), Optional.of(thread.id())));
        assertEquals(
                List.of(installed, workspaceDefaults), configurations.findForTurn(workspace.id(), Optional.empty()));
        assertEquals(
                PersistenceException.Kind.REVISION_CONFLICT,
                assertThrows(
                                PersistenceException.class,
                                () -> configurations.update(
                                        identity("stale-thread", 1, threadChoice),
                                        Optional.of(workspace.id()),
                                        Optional.of(thread.id()),
                                        threadChoice))
                        .kind());
        assertEquals(
                second,
                configurations
                        .find(Optional.of(workspace.id()), Optional.of(thread.id()))
                        .orElseThrow());
    }

    @Test
    void absentScopeRejectsNonzeroRevisionAndCommittedIdentityCannotBeRepurposed() {
        ExecutionOverrides choice = select("worker");
        assertEquals(
                PersistenceException.Kind.REVISION_CONFLICT,
                assertThrows(
                                PersistenceException.class,
                                () -> configurations.update(
                                        identity("absent", 1, choice), Optional.empty(), Optional.empty(), choice))
                        .kind());
        assertTrue(configurations.find(Optional.empty(), Optional.empty()).isEmpty());
        CommandIdentity original = identity("install", 0, choice);
        ExecutionConfiguration installed = configurations.update(original, Optional.empty(), Optional.empty(), choice);
        CommandIdentity differentMethod = new CommandIdentity(
                "execution/subagent/update", original.idempotencyKey(), 0, original.requestDigest());
        CommandIdentity differentPayload =
                new CommandIdentity(original.method(), original.idempotencyKey(), 0, "c".repeat(64));
        for (CommandIdentity changed : List.of(differentMethod, differentPayload)) {
            assertEquals(
                    PersistenceException.Kind.IDEMPOTENCY_CONFLICT,
                    assertThrows(
                                    PersistenceException.class,
                                    () -> configurations.update(changed, Optional.empty(), Optional.empty(), choice))
                            .kind());
        }
        assertEquals(installed, configurations.update(original, Optional.empty(), Optional.empty(), choice));
        assertEquals(
                installed,
                configurations.find(Optional.empty(), Optional.empty()).orElseThrow());
    }

    @Test
    void scopeValidationRejectsMissingAndForeignOwnersForNormalAndSubagentDefaults() {
        Workspace first = workspace("first");
        Workspace second = workspace("second");
        ConversationThread foreignThread = thread(second);
        assertThrows(
                PersistenceException.class,
                () -> configurations.find(Optional.empty(), Optional.of(foreignThread.id())));
        assertThrows(
                PersistenceException.class,
                () -> configurations.findSubagentDefaults(Optional.empty(), Optional.of(foreignThread.id())));
        assertThrows(
                PersistenceException.class,
                () -> configurations.find(Optional.of(WorkspaceId.random()), Optional.empty()));
        assertThrows(
                PersistenceException.class,
                () -> configurations.find(Optional.of(first.id()), Optional.of(ThreadId.random())));
        assertThrows(
                PersistenceException.class,
                () -> configurations.findForTurn(first.id(), Optional.of(foreignThread.id())));
        assertThrows(
                PersistenceException.class,
                () -> configurations.findSubagentDefaultsForTurn(first.id(), Optional.of(foreignThread.id())));
        assertThrows(
                PersistenceException.class, () -> configurations.findForTurn(WorkspaceId.random(), Optional.empty()));
        assertThrows(
                PersistenceException.class,
                () -> configurations.findForTurn(first.id(), Optional.of(ThreadId.random())));
        ExecutionOverrides choice = reasoning(ReasoningPreference.HIGH);
        assertEquals(
                PersistenceException.Kind.INVALID_REQUEST,
                assertThrows(
                                PersistenceException.class,
                                () -> configurations.updateSubagentDefaults(
                                        identity("foreign-subagent", 0, choice),
                                        Optional.of(first.id()),
                                        Optional.of(foreignThread.id()),
                                        choice))
                        .kind());
        assertTrue(configurations
                .findSubagentDefaultsForTurn(second.id(), Optional.of(foreignThread.id()))
                .isEmpty());
    }

    @Test
    void subagentDefaultsCannotOverrideSafetyFieldsAndKeepAnIndependentRevision() {
        ExecutionConfiguration installed = configurations.ensureInstallationDefaults(select("worker"));
        for (ExecutionOverrides prohibited : prohibitedSubagentOverrides()) {
            assertEquals(
                    PersistenceException.Kind.INVALID_REQUEST,
                    assertThrows(
                                    PersistenceException.class,
                                    () -> configurations.updateSubagentDefaults(
                                            identity("prohibited-subagent", 0, prohibited),
                                            Optional.empty(),
                                            Optional.empty(),
                                            prohibited))
                            .kind());
            assertTrue(configurations
                    .findSubagentDefaults(Optional.empty(), Optional.empty())
                    .isEmpty());
        }
        ExecutionOverrides high = reasoning(ReasoningPreference.HIGH);
        ExecutionConfiguration first = configurations.updateSubagentDefaults(
                identity("subagent-high", 0, high), Optional.empty(), Optional.empty(), high);
        ExecutionOverrides low = reasoning(ReasoningPreference.LOW);
        ExecutionConfiguration second = configurations.updateSubagentDefaults(
                identity("subagent-low", 1, low), Optional.empty(), Optional.empty(), low);
        assertEquals(1, first.revision());
        assertEquals(2, second.revision());
        assertEquals(
                second,
                configurations
                        .findSubagentDefaults(Optional.empty(), Optional.empty())
                        .orElseThrow());
        assertEquals(
                installed,
                configurations.find(Optional.empty(), Optional.empty()).orElseThrow());
        assertEquals(
                List.of(second),
                configurations.findSubagentDefaultsForTurn(workspace("scope").id(), Optional.empty()));
    }

    private static List<ExecutionOverrides> prohibitedSubagentOverrides() {
        // 每个安全字段单独检查，避免前一个字段的拒绝掩盖后续约束。
        return List.of(
                select("default"),
                new ExecutionOverrides(
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(new PermissionProfileRef("standard", 1)),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()),
                new ExecutionOverrides(
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(ApprovalPolicy.NONE),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()),
                new ExecutionOverrides(
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(new TurnBudget(100, 100, 0, 0, Duration.ofSeconds(1))),
                        Optional.empty(),
                        Optional.empty()),
                new ExecutionOverrides(
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(Set.of()),
                        Optional.empty()));
    }

    private Workspace workspace(String name) {
        return core.createWorkspace(identity("workspace-" + name, 0, name), name, temporaryDirectory.resolve(name));
    }

    private ConversationThread thread(Workspace workspace) {
        return core.createThread(
                identity("thread-" + workspace.id(), 0, workspace.id()),
                workspace.id(),
                Optional.empty(),
                ThreadExecutionIntent.WORKSPACE,
                "Thread");
    }

    private static ExecutionOverrides select(String role) {
        return new ExecutionOverrides(
                Optional.of(new AgentRoleRef(role, 1)),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static ExecutionOverrides reasoning(ReasoningPreference reasoning) {
        return new ExecutionOverrides(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(reasoning));
    }

    private CommandIdentity identity(String key, long revision, Object payload) {
        return new CommandIdentity(
                "execution/configuration/update",
                key,
                revision,
                json.encode(Map.of("payload", payload, "expectedRevision", revision))
                        .sha256());
    }
}
