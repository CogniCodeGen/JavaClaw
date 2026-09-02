package com.javaclaw.server.extension;

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

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.ToolDescriptor;
import com.javaclaw.api.ToolIdentity;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.Workspace;
import com.javaclaw.extension.spi.AutomationStepPort;
import com.javaclaw.extension.spi.ContributionKind;
import com.javaclaw.extension.spi.CredentialVaultPort;
import com.javaclaw.extension.spi.EmbeddingPort;
import com.javaclaw.extension.spi.ExtensionBundle;
import com.javaclaw.extension.spi.ExtensionContext;
import com.javaclaw.extension.spi.ExtensionContribution;
import com.javaclaw.extension.spi.ExtensionContributions;
import com.javaclaw.extension.spi.ExtensionDescriptor;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.ExtensionPayloadCodec;
import com.javaclaw.extension.spi.ExtensionRequirements;
import com.javaclaw.extension.spi.ExtensionResponse;
import com.javaclaw.extension.spi.ExtensionSchema;
import com.javaclaw.extension.spi.ExtensionTrust;
import com.javaclaw.extension.spi.PrivateNetworkGrantPort;
import com.javaclaw.extension.spi.ScheduleLifecyclePort;
import com.javaclaw.extension.spi.ScheduledCommandPort;
import com.javaclaw.extension.spi.ViewBinding;
import com.javaclaw.extension.spi.ViewDataSource;
import com.javaclaw.extension.spi.ViewSchema;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ExtensionRpcContracts;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.ExtensionCatalogRepository;
import com.javaclaw.server.persistence.ExtensionJobService;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.H2ManagedExtensionStore;
import com.javaclaw.server.persistence.InputRequestService;
import com.javaclaw.server.persistence.PermissionProfileService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuiltinExtensionHostValidationTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path temporaryDirectory;

    private CanonicalJson json;
    private CoreCommandService core;
    private PermissionProfileService profiles;
    private BuiltinExtensionRuntimePorts ports;

    @BeforeEach
    void setUp() {
        json = new CanonicalJson();
        H2Database database = new H2Database(temporaryDirectory.resolve("data-v5"));
        database.initialize();
        core = new CoreCommandService(database, json, CLOCK);
        profiles = new PermissionProfileService(database, json, CLOCK);
        profiles.installStandardProfile();
        ports = new BuiltinExtensionRuntimePorts(
                CLOCK,
                new CanonicalExtensionPayloadCodec(json),
                new H2ManagedExtensionStore(database, CLOCK),
                (command, cancellation) -> {
                    throw new AssertionError("turn orchestration must not be used");
                },
                (workspaceId, profile, cancellation) -> {
                    throw new AssertionError("automation execution policy must not be used");
                },
                new InputRequestService(database, json, CLOCK),
                new ExtensionJobService(database, json, CLOCK),
                (workspaceId, threadId, itemId, verbatim) -> false,
                workspaceId -> reference -> {
                    throw new IllegalArgumentException("Attachment evidence is unavailable");
                },
                emptyCredentials(),
                emptyPrivateNetworkGrants(),
                invocation -> {
                    throw new AssertionError("isolated service must not be used");
                },
                EmbeddingPort.unavailable(),
                AutomationStepPort.unavailable(),
                ScheduledCommandPort.unavailable(),
                ScheduleLifecyclePort.unavailable(),
                new ExtensionCatalogRepository(database, json, CLOCK));
    }

    @Test
    void validContributionsAreIndexedAndClosedInReverseOrder() throws Exception {
        List<String> closes = new java.util.ArrayList<>();
        TestBundle first =
                bundle("test.first", Set.of(ContributionKind.QUERY), List.of(query("query", "read")), closes);
        TestBundle second = bundle(
                "test.second",
                Set.of(
                        ContributionKind.TOOL,
                        ContributionKind.COMMAND,
                        ContributionKind.ORCHESTRATOR,
                        ContributionKind.VIEW,
                        ContributionKind.CONTEXT,
                        ContributionKind.TIMER),
                mixedContributions("test.second"),
                closes);
        second.schemas = List.of(new ExtensionSchema("test.schema", objectSchema()));

        try (BuiltinExtensionHost host = BuiltinExtensionHost.start(List.of(first, second), core, profiles, ports)) {
            assertEquals(2, host.list().size());
            assertEquals(
                    List.of("shared_tool"),
                    host.tools().stream().map(tool -> tool.identity().name()).toList());
            assertEquals(
                    "test.schema", host.schema("test.second", "test.schema").schemaId());
            assertEquals(
                    "overview",
                    host.views(java.util.Optional.of("test.second")).getFirst().viewId());
            assertEquals(1, host.views(java.util.Optional.empty()).size());
            assertThrows(IllegalArgumentException.class, () -> host.schema("test.second", "missing"));
            assertMissingOperation(host);
        }

        assertEquals(List.of("test.second", "test.first"), closes);
    }

    @Test
    void failedRegistrationClosesTheBundleAndPreservesThePrimaryFailure() {
        TestBundle mismatch =
                bundle("test.mismatch", Set.of(ContributionKind.QUERY), List.of(), new java.util.ArrayList<>());
        assertRejected(mismatch, "declaration differs");
        assertTrue(mismatch.closed);

        TestBundle failedStart = bundle("test.start", Set.of(), List.of(), new java.util.ArrayList<>());
        IllegalStateException startFailure = new IllegalStateException("start failed");
        failedStart.startFailure = startFailure;
        failedStart.closeFailure = new IllegalStateException("close failed");
        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> BuiltinExtensionHost.start(List.of(failedStart), core, profiles, ports));
        assertSame(startFailure, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertTrue(failedStart.closed);
    }

    @Test
    void untrustedAndDuplicateBundlesNeverStartAnUnownedBundle() {
        TestBundle thirdParty = bundle("test.third", Set.of(), List.of(), new java.util.ArrayList<>());
        thirdParty.trust = ExtensionTrust.THIRD_PARTY;
        assertRejected(thirdParty, "third-party");
        assertFalse(thirdParty.started);
        assertFalse(thirdParty.closed);

        List<String> closes = new java.util.ArrayList<>();
        TestBundle first = bundle("test.duplicate", Set.of(), List.of(), closes);
        TestBundle duplicate = bundle("test.duplicate", Set.of(), List.of(), closes);
        assertThrows(
                IllegalArgumentException.class,
                () -> BuiltinExtensionHost.start(List.of(first, duplicate), core, profiles, ports));
        assertTrue(first.closed);
        assertFalse(duplicate.started);
        assertFalse(duplicate.closed);
    }

    @Test
    void closePropagatesTheFirstFailureAndSuppressesEarlierBundleFailures() throws Exception {
        List<String> closes = new java.util.ArrayList<>();
        TestBundle first = bundle("test.close-one", Set.of(), List.of(), closes);
        TestBundle second = bundle("test.close-two", Set.of(), List.of(), closes);
        first.closeFailure = new IllegalStateException("first close failed");
        second.closeFailure = new IllegalStateException("second close failed");
        BuiltinExtensionHost host = BuiltinExtensionHost.start(List.of(first, second), core, profiles, ports);

        IllegalStateException failure = assertThrows(IllegalStateException.class, host::close);

        assertSame(second.closeFailure, failure);
        assertEquals(1, failure.getSuppressed().length);
        assertSame(first.closeFailure, failure.getSuppressed()[0]);
        assertEquals(List.of("test.close-two", "test.close-one"), closes);
    }

    @Test
    void queryRejectsCrossWorkspaceThreadAndTurnReferences() throws Exception {
        TestBundle bundle = bundle(
                "test.ownership",
                Set.of(ContributionKind.QUERY),
                List.of(query("query", "read")),
                new java.util.ArrayList<>());
        Workspace first = createWorkspace("first");
        Workspace second = createWorkspace("second");
        ConversationThread firstThread = createThread(first, "first");
        ConversationThread secondThread = createThread(second, "second");
        AgentTurn turn = startTurn(firstThread);

        try (BuiltinExtensionHost host = BuiltinExtensionHost.start(List.of(bundle), core, profiles, ports)) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> host.query(call(second, Optional.of(firstThread), Optional.empty())));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> host.query(call(second, Optional.of(secondThread), Optional.of(turn))));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> host.query(call(second, Optional.empty(), Optional.of(turn))));
            assertEquals(
                    0,
                    host.query(call(first, Optional.of(firstThread), Optional.of(turn)))
                            .revision());
        }
    }

    @Test
    void contributionIdentifiersOperationsAndGlobalToolNamesMustBeUnique() {
        assertRejected(
                bundle(
                        "test.ids",
                        Set.of(ContributionKind.QUERY),
                        List.of(query("same", "one"), query("same", "two")),
                        new java.util.ArrayList<>()),
                "duplicate contribution ID");
        assertRejected(
                bundle(
                        "test.operations",
                        Set.of(ContributionKind.QUERY),
                        List.of(query("one", "read"), query("two", "read")),
                        new java.util.ArrayList<>()),
                "duplicate extension operation");

        List<String> closes = new java.util.ArrayList<>();
        TestBundle first = bundle(
                "test.tools-one",
                Set.of(ContributionKind.TOOL),
                List.of(tool("one", "test.tools-one", 1, "global", objectSchema(), objectSchema())),
                closes);
        TestBundle second = bundle(
                "test.tools-two",
                Set.of(ContributionKind.TOOL),
                List.of(tool("two", "test.tools-two", 1, "global", objectSchema(), objectSchema())),
                closes);
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> BuiltinExtensionHost.start(List.of(first, second), core, profiles, ports));
        assertTrue(failure.getMessage().contains("duplicate global tool name"));
        assertEquals(List.of("test.tools-two", "test.tools-one"), closes);
    }

    @Test
    void toolIdentitySchemasAndLocalNamesAreValidatedBeforePublication() {
        List<InvalidToolCase> cases = List.of(
                invalidTool("producer", "other.producer", 1, "tool", objectSchema(), objectSchema()),
                invalidTool("revision", "test.revision", 2, "tool", objectSchema(), objectSchema()),
                invalidTool("input", "test.input", 1, "tool", scalarSchema(), objectSchema()),
                invalidTool("output", "test.output", 1, "tool", objectSchema(), scalarSchema()));
        for (InvalidToolCase invalid : cases) {
            assertRejected(invalid.bundle(), invalid.expectedMessage());
            assertTrue(invalid.bundle().closed);
        }

        CanonicalPayload decodedAsText = json.encode(Map.of("sentinel", true));
        TestBundle nonMap = bundle(
                "test.nonmap",
                Set.of(ContributionKind.TOOL),
                List.of(tool("tool", "test.nonmap", 1, "tool", decodedAsText, objectSchema())),
                new java.util.ArrayList<>());
        IllegalArgumentException nonMapFailure = assertThrows(
                IllegalArgumentException.class,
                () -> BuiltinExtensionHost.start(List.of(nonMap), core, profiles, portsDecodingAsText(decodedAsText)));
        assertTrue(nonMapFailure.getMessage().contains("inputSchema"));
        assertTrue(nonMap.closed);

        TestBundle duplicate = bundle(
                "test.local",
                Set.of(ContributionKind.TOOL),
                List.of(
                        tool("one", "test.local", 1, "same", objectSchema(), objectSchema()),
                        tool("two", "test.local", 1, "same", objectSchema(), objectSchema())),
                new java.util.ArrayList<>());
        assertRejected(duplicate, "duplicate extension tool name");
    }

    private List<ExtensionContribution> mixedContributions(String extensionId) {
        return List.of(
                tool("tool", extensionId, 1, "shared_tool", objectSchema(), objectSchema()),
                new ExtensionContributions.Command("command", Set.of("write"), handler()),
                new ExtensionContributions.Orchestrator(
                        "orchestrator",
                        Set.of("orchestrate"),
                        (request, context) -> handler().handle(request, context)),
                new ExtensionContributions.View(
                        "view",
                        new ViewSchema(
                                ViewSchema.CURRENT_VERSION,
                                "overview",
                                "Overview",
                                List.of(new ViewDataSource("content", "read", Map.of(), List.of(), 1)),
                                List.of(new ViewSchema.Markdown(
                                        "content", "正文", new ViewBinding("content", "markdown"))))),
                new ExtensionContributions.Resource("context", ContributionKind.CONTEXT, objectSchema()),
                new ExtensionContributions.Timer("timer", Duration.ofMinutes(1), "write"));
    }

    private InvalidToolCase invalidTool(
            String suffix,
            String producer,
            long revision,
            String name,
            CanonicalPayload input,
            CanonicalPayload output) {
        String id = "test." + suffix;
        TestBundle bundle = bundle(
                id,
                Set.of(ContributionKind.TOOL),
                List.of(tool("tool", producer, revision, name, input, output)),
                new java.util.ArrayList<>());
        String message =
                switch (suffix) {
                    case "producer", "revision" -> "producer or revision";
                    default -> suffix + "Schema";
                };
        return new InvalidToolCase(bundle, message);
    }

    private BuiltinExtensionRuntimePorts portsDecodingAsText(CanonicalPayload selected) {
        ExtensionPayloadCodec delegate = new CanonicalExtensionPayloadCodec(json);
        ExtensionPayloadCodec codec = new ExtensionPayloadCodec() {
            @Override
            public CanonicalPayload encode(Object value) {
                return delegate.encode(value);
            }

            @Override
            public <T> T decode(CanonicalPayload payload, Class<T> type) {
                return payload.equals(selected) ? type.cast("invalid") : delegate.decode(payload, type);
            }
        };
        return new BuiltinExtensionRuntimePorts(
                ports.clock(),
                codec,
                ports.managedStore(),
                ports.turns(),
                ports.executionPolicies(),
                ports.inputs(),
                ports.jobs(),
                ports.evidence(),
                ports.attachments(),
                ports.credentials(),
                ports.privateNetworkGrants(),
                ports.services(),
                ports.embeddings(),
                ports.automationSteps(),
                ports.scheduledCommands(),
                ports.scheduleLifecycle(),
                ports.catalog());
    }

    private static CredentialVaultPort emptyCredentials() {
        return new CredentialVaultPort() {
            @Override
            public Optional<com.javaclaw.api.CredentialMetadata> metadata(com.javaclaw.api.CredentialRef reference) {
                return Optional.empty();
            }

            @Override
            public List<com.javaclaw.api.CredentialMetadata> listMetadata(String namespace) {
                return List.of();
            }
        };
    }

    private static PrivateNetworkGrantPort emptyPrivateNetworkGrants() {
        return new PrivateNetworkGrantPort() {
            @Override
            public List<com.javaclaw.api.PrivateNetworkGrant> available(
                    com.javaclaw.api.WorkspaceId workspaceId, com.javaclaw.api.PrivateNetworkPurpose purpose) {
                return List.of();
            }

            @Override
            public com.javaclaw.api.PrivateNetworkGrant requireBindable(
                    com.javaclaw.api.PrivateNetworkGrantRef reference,
                    com.javaclaw.api.WorkspaceId workspaceId,
                    com.javaclaw.api.PrivateNetworkPurpose purpose,
                    java.net.URI origin) {
                throw new IllegalArgumentException("Private-network grant evidence is unavailable");
            }
        };
    }

    private void assertMissingOperation(BuiltinExtensionHost host) {
        CanonicalPayload payload = json.encode(Map.of("name", "Workspace"));
        var identity = new CommandIdentity("workspace/create", "workspace", 0, payload.sha256());
        var workspace = core.createWorkspace(
                identity, "Workspace", temporaryDirectory.resolve("workspace").toAbsolutePath());
        var call = new ExtensionRpcContracts.CallPayload(
                "test.first",
                workspace.id(),
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                "missing",
                objectSchema());
        assertThrows(IllegalArgumentException.class, () -> host.query(call));
    }

    private ExtensionRpcContracts.CallPayload call(
            Workspace workspace, Optional<ConversationThread> thread, Optional<AgentTurn> turn) {
        return new ExtensionRpcContracts.CallPayload(
                "test.ownership",
                workspace.id(),
                thread.map(ConversationThread::id),
                turn.map(AgentTurn::id),
                "read",
                objectSchema());
    }

    private Workspace createWorkspace(String suffix) {
        Map<String, String> payload = Map.of("name", suffix);
        return core.createWorkspace(
                identity("workspace/create", "workspace-" + suffix, payload),
                suffix,
                temporaryDirectory.resolve(suffix).toAbsolutePath());
    }

    private ConversationThread createThread(Workspace workspace, String suffix) {
        Map<String, String> payload = Map.of("title", suffix);
        return core.createThread(
                identity("thread/create", "thread-" + suffix, payload),
                workspace.id(),
                Optional.empty(),
                com.javaclaw.api.ThreadExecutionIntent.WORKSPACE,
                suffix);
    }

    private AgentTurn startTurn(ConversationThread thread) {
        CorePayloads.Message message =
                new CorePayloads.Message(MessageRole.USER, "question", List.of(), Optional.empty());
        TurnBudget budget = new TurnBudget(1_000, 1_000, 2, 0, Duration.ofMinutes(1));
        return core.startTurn(
                identity("turn/start", "turn", Map.of("thread", thread.id().value())),
                com.javaclaw.server.TurnContractFixtures.request(thread.id(), budget, message));
    }

    private CommandIdentity identity(String method, String key, Object payload) {
        return CommandIdentity.from(method, new WriteCommand(key, 0, json.encode(payload)), json);
    }

    private ExtensionContributions.Tool tool(
            String contributionId,
            String producer,
            long revision,
            String name,
            CanonicalPayload input,
            CanonicalPayload output) {
        ToolDescriptor descriptor = new ToolDescriptor(
                new ToolIdentity(producer, name, revision),
                "Test tool",
                input,
                output,
                ToolRisk.READ_ONLY,
                Set.of("test"));
        return new ExtensionContributions.Tool(contributionId, descriptor, handler());
    }

    private ExtensionContributions.Query query(String contributionId, String operation) {
        return new ExtensionContributions.Query(contributionId, Set.of(operation), handler());
    }

    private com.javaclaw.extension.spi.ExtensionHandler handler() {
        return (request, context) -> new ExtensionResponse(json.encode(Map.of("ok", true)), 0);
    }

    private TestBundle bundle(
            String id, Set<ContributionKind> kinds, List<ExtensionContribution> contributions, List<String> closes) {
        return new TestBundle(id, kinds, contributions, closes);
    }

    private void assertRejected(TestBundle bundle, String message) {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> BuiltinExtensionHost.start(List.of(bundle), core, profiles, ports));
        assertTrue(failure.getMessage().contains(message));
    }

    private CanonicalPayload objectSchema() {
        return json.encode(Map.of("type", "object"));
    }

    private CanonicalPayload scalarSchema() {
        return json.encode(Map.of("type", "string"));
    }

    private record InvalidToolCase(TestBundle bundle, String expectedMessage) {}

    private final class TestBundle implements ExtensionBundle {
        private final String id;
        private final Set<ContributionKind> kinds;
        private final List<ExtensionContribution> contributions;
        private final List<String> closes;
        private ExtensionTrust trust = ExtensionTrust.BUILT_IN;
        private List<ExtensionSchema> schemas = List.of();
        private RuntimeException startFailure;
        private RuntimeException closeFailure;
        private boolean started;
        private boolean closed;

        private TestBundle(
                String id,
                Set<ContributionKind> kinds,
                List<ExtensionContribution> contributions,
                List<String> closes) {
            this.id = id;
            this.kinds = kinds;
            this.contributions = contributions;
            this.closes = closes;
        }

        @Override
        public ExtensionDescriptor descriptor() {
            return new ExtensionDescriptor(
                    new ExtensionId(id),
                    id,
                    "5.0.0",
                    1,
                    kinds,
                    new ExtensionRequirements(
                            trust,
                            com.javaclaw.extension.spi.ExtensionAvailability.OPTIONAL,
                            2,
                            profiles.require(PermissionProfileService.STANDARD_PROFILE_ID, 1)));
        }

        @Override
        public List<ExtensionContribution> start(ExtensionContext context) {
            started = true;
            if (startFailure != null) {
                throw startFailure;
            }
            return contributions;
        }

        @Override
        public List<ExtensionSchema> schemas() {
            return schemas;
        }

        @Override
        public void close() {
            closed = true;
            closes.add(id);
            if (closeFailure != null) {
                throw closeFailure;
            }
        }
    }
}
