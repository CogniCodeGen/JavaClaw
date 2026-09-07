package com.javaclaw.server.turn;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.ResolvedTurnConfig;
import com.javaclaw.api.ThreadExecutionIntent;
import com.javaclaw.api.ToolCallRequest;
import com.javaclaw.api.ToolIdentity;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.TurnId;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.SkillContracts;
import com.javaclaw.extension.spi.ExtensionResponse;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.runtime.TurnFailureException;
import com.javaclaw.server.TurnContractFixtures;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.TurnStartRequest;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SkillTurnCeilingTest {
    private static final String DIGEST = "a".repeat(64);

    @TempDir
    Path directory;

    private final CanonicalJson json = new CanonicalJson();
    private CoreCommandService core;

    @Test
    void frozenWhitelistRejectsDirectReadAndExecutableResourceBypassAfterRestart() {
        TurnId turn = start(Optional.of(Set.of("review")));
        core = new CoreCommandService(new H2Database(directory.resolve("data-v6")), json, Clock.systemUTC());
        for (String tool : List.of("skill_read", "skill_execute_resource")) {
            assertDoesNotThrow(() -> SkillTurnCeiling.requireAllowed(core, json, request(turn, tool, "review")));
            TurnFailureException denied = assertThrows(
                    TurnFailureException.class,
                    () -> SkillTurnCeiling.requireAllowed(core, json, request(turn, tool, "deploy")));
            assertEquals("SKILL_NOT_ALLOWED", denied.code());
        }
    }

    @Test
    void searchHidesForbiddenSkillsWithoutChangingFrozenCatalogDigestOrRevision() {
        TurnId turn = start(Optional.of(Set.of("review")));
        ToolCallRequest request = request(turn, "skill_search", "query");
        assertDoesNotThrow(() -> SkillTurnCeiling.requireAllowed(core, json, request));
        ExtensionResponse response = SkillTurnCeiling.filter(core, json, request, searchResponse());
        SkillContracts.SearchResult result = json.decode(response.payload(), SkillContracts.SearchResult.class);
        assertEquals(List.of(summary("review")), result.matches());
        assertEquals(DIGEST, result.catalogDigest());
        assertEquals(7, response.revision());
    }

    @Test
    void explicitEmptyWhitelistDisablesDiscoveryAndBothDirectAccessPaths() {
        TurnId turn = start(Optional.of(Set.of()));
        ExtensionResponse response =
                SkillTurnCeiling.filter(core, json, request(turn, "skill_search", "query"), searchResponse());
        assertEquals(
                List.of(),
                json.decode(response.payload(), SkillContracts.SearchResult.class)
                        .matches());
        for (String tool : List.of("skill_read", "skill_execute_resource")) {
            assertThrows(
                    TurnFailureException.class,
                    () -> SkillTurnCeiling.requireAllowed(core, json, request(turn, tool, "review")));
        }
    }

    @Test
    void inheritedWhitelistLeavesPublishedSkillValidationToExistingExtensionBoundary() {
        TurnId turn = start(Optional.empty());
        ExtensionResponse response = searchResponse();
        assertSame(response, SkillTurnCeiling.filter(core, json, request(turn, "skill_search", "query"), response));
        assertDoesNotThrow(() -> SkillTurnCeiling.requireAllowed(core, json, request(turn, "skill_read", "deploy")));
    }

    @Test
    void nonSearchResultsAndOtherProducersAreNotDecodedAsSkillSearchData() {
        TurnId turn = start(Optional.of(Set.of("review")));
        ExtensionResponse response = new ExtensionResponse(json.encode(Map.of("instructions", "published")), 9);
        assertSame(response, SkillTurnCeiling.filter(core, json, request(turn, "skill_read", "review"), response));
        // 同名的第三方工具由工具身份与自身治理边界校验，不能被错误地按内置 Skill 载荷解析。
        ToolCallRequest external = new ToolCallRequest(
                TurnId.random(),
                "call",
                new ToolIdentity("third-party", "skill_search", 1),
                json.encode(Map.of("query", "query")),
                "key",
                1);
        assertDoesNotThrow(() -> SkillTurnCeiling.requireAllowed(core, json, external));
        assertSame(response, SkillTurnCeiling.filter(core, json, external, response));
    }

    private TurnId start(Optional<Set<String>> skills) {
        H2Database database = new H2Database(directory.resolve("data-v6"));
        database.initialize();
        core = new CoreCommandService(database, json, Clock.systemUTC());
        var workspace = core.createWorkspace(identity("workspace"), "Workspace", directory.resolve("project"));
        var thread = core.createThread(
                identity("thread"), workspace.id(), Optional.empty(), ThreadExecutionIntent.WORKSPACE, "Skills");
        var request = TurnContractFixtures.request(
                thread.id(),
                new TurnBudget(1_000, 1_000, 10, 1, Duration.ofMinutes(1)),
                new CorePayloads.Message(MessageRole.USER, "检查项目", List.of(), Optional.empty()));
        var original = request.configuration();
        var configuration = new ResolvedTurnConfig(
                original.role(),
                original.provider(),
                original.permissionProfile(),
                original.approvalPolicy(),
                original.budget(),
                original.effectiveCapabilities(),
                original.reasoning(),
                original.permissionConstraint(),
                skills,
                original.promptManifestDigest(),
                original.toolCatalogDigest(),
                original.provenance());
        return core.startTurn(
                        identity("turn"),
                        new TurnStartRequest(
                                thread.id(),
                                configuration,
                                request.executionRoot(),
                                request.promptSnapshot(),
                                request.toolCatalog(),
                                request.message(),
                                Optional.empty()))
                .id();
    }

    private ToolCallRequest request(TurnId turn, String tool, String skill) {
        var read = new SkillContracts.PublishedReadRequest(skill, 1, DIGEST);
        Object arguments =
                switch (tool) {
                    case "skill_read" -> read;
                    case "skill_execute_resource" ->
                        new SkillContracts.ResourceExecutionRequest(read, "script.java", List.of());
                    default -> new SkillContracts.SearchRequest(skill, 10);
                };
        return new ToolCallRequest(
                turn, "call", new ToolIdentity(BuiltinExtensionIds.SKILL, tool, 1), json.encode(arguments), "key", 1);
    }

    private ExtensionResponse searchResponse() {
        return new ExtensionResponse(
                json.encode(new SkillContracts.SearchResult(List.of(summary("review"), summary("deploy")), DIGEST)), 7);
    }

    private static SkillContracts.Summary summary(String id) {
        return new SkillContracts.Summary(id, 1, DIGEST, id, "已发布技能");
    }

    private CommandIdentity identity(String key) {
        return new CommandIdentity(
                "test/skill-ceiling", key, 0, json.encode(Map.of("key", key)).sha256());
    }
}
