package com.javaclaw.server.turn;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.CoreSchemas;
import com.javaclaw.api.ItemStatus;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.ToolCatalogSnapshot;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.Workspace;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CoreItemCodecs;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.runtime.ConversationWindow;
import com.javaclaw.runtime.ModelMessage;
import com.javaclaw.runtime.ProviderState;
import com.javaclaw.runtime.TurnExecutionCommand;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.H2ManagedExtensionStore;
import com.javaclaw.server.persistence.H2TurnJournal;
import com.javaclaw.server.persistence.PermissionProfileService;
import com.javaclaw.server.persistence.PersistenceException;
import com.javaclaw.server.persistence.ProviderStateService;
import com.javaclaw.server.persistence.TurnStartRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class H2ConversationContextTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @TempDir
    Path temporaryDirectory;

    private H2Database database;
    private CanonicalJson json;
    private CoreCommandService core;
    private H2TurnJournal journal;
    private PermissionProfile profile;

    @BeforeEach
    void initializeDataV5() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        database = new H2Database(temporaryDirectory.resolve("data-v5"));
        database.initialize();
        json = new CanonicalJson();
        core = new CoreCommandService(database, json, clock);
        journal = new H2TurnJournal(database, CoreItemCodecs.createRegistry(json), json, clock);
        PermissionProfileService profiles = new PermissionProfileService(database, json, clock);
        profiles.installStandardProfile();
        profile = profiles.require(PermissionProfileService.STANDARD_PROFILE_ID, 1);
    }

    @Test
    void completedCoreItemsBecomeOrderedModelMessagesAndUnknownItemsAreIgnored() throws Exception {
        Aggregate aggregate = aggregate("current question");
        appendCompletedConversation(aggregate);
        appendIgnoredItems(aggregate);

        ConversationWindow window =
                context().assemble(command(aggregate, "current question"), new CancellationSource());

        assertEquals(
                List.of(
                        MessageRole.USER,
                        MessageRole.ASSISTANT,
                        MessageRole.ASSISTANT,
                        MessageRole.TOOL,
                        MessageRole.TOOL),
                window.messages().stream().map(ModelMessage::role).toList());
        assertEquals(2, window.messages().get(2).toolCalls().size());
        assertEquals("search", window.messages().get(3).toolName().orElseThrow());
        assertEquals("read", window.messages().get(4).toolName().orElseThrow());
        assertTrue(window.estimatedInputTokens() > 0);
    }

    private void appendCompletedConversation(Aggregate aggregate) {
        var arguments = json.parse("{\"query\":\"release\"}");
        var output = json.parse("{\"value\":1}");
        journal.append(
                aggregate.turn().id(),
                "message",
                CoreSchemas.MESSAGE,
                new CorePayloads.Message(MessageRole.ASSISTANT, "先搜索", List.of(), Optional.empty()),
                ItemStatus.COMPLETED);
        journal.append(
                aggregate.turn().id(),
                "tool-call",
                CoreSchemas.TOOL_CALL,
                new CorePayloads.ToolCall("call-1", "builtin", "search", 1, arguments),
                ItemStatus.COMPLETED);
        journal.append(
                aggregate.turn().id(),
                "tool-call",
                CoreSchemas.TOOL_CALL,
                new CorePayloads.ToolCall("call-2", "builtin", "read", 1, arguments),
                ItemStatus.COMPLETED);
        journal.append(
                aggregate.turn().id(),
                "tool-result",
                CoreSchemas.TOOL_RESULT,
                new CorePayloads.ToolResult("call-1", true, output, Optional.empty()),
                ItemStatus.COMPLETED);
        journal.append(
                aggregate.turn().id(),
                "message",
                CoreSchemas.MESSAGE,
                new CorePayloads.Message(MessageRole.TOOL, "read result", List.of(), Optional.of("call-2")),
                ItemStatus.COMPLETED);
    }

    private void appendIgnoredItems(Aggregate aggregate) throws Exception {
        journal.append(
                aggregate.turn().id(),
                "message",
                CoreSchemas.MESSAGE,
                new CorePayloads.Message(MessageRole.ASSISTANT, "不会进入上下文", List.of(), Optional.empty()),
                ItemStatus.IN_PROGRESS);
        H2ManagedExtensionStore extensions = new H2ManagedExtensionStore(database, Clock.fixed(NOW, ZoneOffset.UTC));
        extensions.inTransaction(new ExtensionId("com.javaclaw.unknown"), transaction -> {
            transaction.appendItem(
                    aggregate.turn().id(),
                    "artifact",
                    "com.javaclaw.unknown/artifact@1",
                    json.parse("{\"ignored\":true}"),
                    ItemStatus.COMPLETED);
            return null;
        });
    }

    @Test
    void providerStateSkipsItemsAlreadyRepresentedByOpaqueConversation() {
        Aggregate aggregate = aggregate("opaque question");
        ProviderState state = new ProviderState("openai", "responses-v1", json.parse("{\"id\":\"r1\"}"));
        journal.saveProviderState(
                aggregate.turn().id(), aggregate.turn().provider().routeKey(), state, 77);

        ConversationWindow window = context().assemble(command(aggregate, "opaque question"), new CancellationSource());

        assertEquals(state, window.providerState().orElseThrow());
        assertTrue(window.messages().isEmpty());
        assertEquals(77, window.estimatedInputTokens());
    }

    @Test
    void duplicateOrMissingToolCallIdentityIsRejected() {
        Aggregate duplicate = aggregate("duplicate");
        var arguments = json.parse("{}");
        CorePayloads.ToolCall call = new CorePayloads.ToolCall("same", "builtin", "read", 1, arguments);
        journal.append(duplicate.turn().id(), "tool-call", CoreSchemas.TOOL_CALL, call, ItemStatus.COMPLETED);
        journal.append(duplicate.turn().id(), "tool-call", CoreSchemas.TOOL_CALL, call, ItemStatus.COMPLETED);
        assertThrows(
                PersistenceException.class,
                () -> context().assemble(command(duplicate, "duplicate"), new CancellationSource()));

        Aggregate missing = aggregate("missing");
        journal.append(
                missing.turn().id(),
                "tool-result",
                CoreSchemas.TOOL_RESULT,
                new CorePayloads.ToolResult("not-called", true, json.parse("{}"), Optional.empty()),
                ItemStatus.COMPLETED);
        assertThrows(
                PersistenceException.class,
                () -> context().assemble(command(missing, "missing"), new CancellationSource()));
    }

    @Test
    void malformedToolMessageMismatchedInputAndCancellationFailClosed() {
        Aggregate malformed = aggregate("malformed");
        journal.append(
                malformed.turn().id(),
                "message",
                CoreSchemas.MESSAGE,
                new CorePayloads.Message(MessageRole.TOOL, "missing identity", List.of(), Optional.empty()),
                ItemStatus.COMPLETED);
        assertThrows(
                PersistenceException.class,
                () -> context().assemble(command(malformed, "malformed"), new CancellationSource()));
        assertThrows(
                PersistenceException.class,
                () -> context().assemble(command(malformed, "different input"), new CancellationSource()));

        CancellationSource cancelled = new CancellationSource();
        cancelled.cancel("stop");
        assertThrows(
                com.javaclaw.api.TurnCancelledException.class,
                () -> context().assemble(command(malformed, "malformed"), cancelled));
    }

    private H2ConversationContext context() {
        return new H2ConversationContext(core, new ProviderStateService(database), CoreItemCodecs.createRegistry(json));
    }

    private TurnExecutionCommand command(Aggregate aggregate, String userMessage) {
        ToolCatalogSnapshot catalog = new ToolCatalogSnapshot(aggregate.turn().id(), 1, List.of(), profile, NOW);
        return new TurnExecutionCommand(
                aggregate.turn(), aggregate.turn().provider(), "system instruction", userMessage, profile, catalog);
    }

    private Aggregate aggregate(String messageText) {
        Workspace workspace = core.listWorkspaces().stream().findFirst().orElseGet(() -> {
            CoreRpcContracts.WorkspaceCreatePayload payload =
                    new CoreRpcContracts.WorkspaceCreatePayload("上下文测试", temporaryDirectory.resolve("workspace"));
            return core.createWorkspace(
                    identity("workspace/create", "workspace", payload), payload.name(), payload.root());
        });
        CoreRpcContracts.ThreadCreatePayload threadPayload = new CoreRpcContracts.ThreadCreatePayload(
                workspace.id(), Optional.empty(), com.javaclaw.api.ThreadExecutionIntent.WORKSPACE, messageText);
        ConversationThread thread = core.createThread(
                identity("thread/create", "thread-" + messageText, threadPayload),
                workspace.id(),
                Optional.empty(),
                com.javaclaw.api.ThreadExecutionIntent.WORKSPACE,
                messageText);
        CorePayloads.Message message =
                new CorePayloads.Message(MessageRole.USER, messageText, List.of(), Optional.empty());
        CoreRpcContracts.TurnStartPayload turnPayload =
                com.javaclaw.server.TurnContractFixtures.payload(thread.id(), messageText);
        ToolCatalogSnapshot toolCatalog =
                new ToolCatalogSnapshot(com.javaclaw.api.TurnId.random(), 1, List.of(), profile, NOW);
        AgentTurn turn = core.startTurn(
                identity("turn/start", "turn-" + messageText, turnPayload),
                new TurnStartRequest(
                        thread.id(),
                        budget(),
                        com.javaclaw.server.TurnContractFixtures.PROFILE,
                        com.javaclaw.server.TurnContractFixtures.PROVIDER,
                        com.javaclaw.server.TurnContractFixtures.PERMISSIONS,
                        temporaryDirectory,
                        com.javaclaw.server.TurnContractFixtures.PROMPT_SNAPSHOT,
                        toolCatalog,
                        message,
                        Optional.empty()));
        return new Aggregate(thread, turn);
    }

    private CommandIdentity identity(String method, String key, Object payload) {
        return CommandIdentity.from(method, new WriteCommand(key, 0, json.encode(payload)), json);
    }

    private static TurnBudget budget() {
        return new TurnBudget(4_000, 1_000, 4, 0, Duration.ofMinutes(1));
    }

    private record Aggregate(ConversationThread thread, AgentTurn turn) {}
}
