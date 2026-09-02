package com.javaclaw.builtin.extensions;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AttachmentRef;
import com.javaclaw.api.ExecutionState;
import com.javaclaw.builtin.contracts.DocumentContracts;
import com.javaclaw.builtin.contracts.KnowledgeContracts;
import com.javaclaw.extension.spi.ExtensionContext;
import com.javaclaw.extension.spi.ExtensionContribution;
import com.javaclaw.extension.spi.ExtensionContributions;
import com.javaclaw.extension.spi.ExtensionExecutionContext;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.ExtensionJob;
import com.javaclaw.extension.spi.ExtensionJobPort;
import com.javaclaw.extension.spi.ExtensionRequest;
import com.javaclaw.extension.spi.ExtensionResponse;
import com.javaclaw.extension.spi.ViewQueryRequest;
import com.javaclaw.extension.spi.ViewQueryResult;

import static com.javaclaw.builtin.extensions.BuiltinExtensionTestSupport.NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeExtensionBranchCoverageTest {
    private static final ExtensionId ID = new ExtensionId("javaclaw.knowledge");

    @Test
    void lifecycleAndHandlersRejectInvalidStateAndUnknownOperations() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        KnowledgeExtension extension = new KnowledgeExtension();

        assertThrows(IllegalStateException.class, extension::schemas);
        assertThrows(NullPointerException.class, () -> extension.start(null));
        BuiltinExtensionTestSupport.Started started = support.start(extension);
        assertFalse(extension.schemas().isEmpty());
        assertFalse(extension.jobExecutors(runtime(support)).isEmpty());
        assertThrows(
                IllegalStateException.class,
                () -> extension.start(new ExtensionContext(support.clock, support.payloads)));

        ExtensionContributions.Query query =
                contribution(started.contributions(), ExtensionContributions.Query.class, "knowledge.query");
        ExtensionContributions.Command command =
                contribution(started.contributions(), ExtensionContributions.Command.class, "knowledge.command");
        assertThrows(
                IllegalArgumentException.class,
                () -> query.handler()
                        .handle(support.request("unknown", Map.of(), Optional.empty(), 0), started.context()));
        assertThrows(
                IllegalArgumentException.class,
                () -> command.handler()
                        .handle(support.request("unknown", Map.of(), Optional.of("unknown"), 0), started.context()));

        extension.close();
        assertThrows(IllegalStateException.class, extension::schemas);
        assertFalse(extension
                .start(new ExtensionContext(support.clock, support.payloads))
                .isEmpty());
        extension.close();
    }

    @Test
    void sourceAndGenerationQueriesCoverPaginationMissingResourcesAndViewPages() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        KnowledgeExtension extension = new KnowledgeExtension();
        BuiltinExtensionTestSupport.Started started = support.start(extension);
        try {
            putSource(support, source("alpha", 1, "generation-alpha"), 0);
            putSource(support, source("beta", 1, "generation-beta"), 0);
            putGeneration(support, generation("alpha", 1), 0);
            putGeneration(support, generation("beta", 1), 0);

            assertKnowledgePages(started, support);
            assertDirectReads(started, support);
            assertMissingAndInvalidViewRequests(started, support);
        } finally {
            extension.close();
        }
    }

    @Test
    void sourceEditorValidatesDataSourceArgumentsCursorAndRevisionText() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        KnowledgeExtension extension = new KnowledgeExtension();
        BuiltinExtensionTestSupport.Started started = support.start(extension);
        try {
            putSource(support, source("guide", 1, "generation-guide"), 0);
            ViewQueryResult valid = view(
                    started, support, "view.source", "sourceEditor", Map.of("id", "guide", "revision", "1"), "", 1);
            assertEquals(1, valid.revision());

            List<ViewQueryRequest> invalid = List.of(
                    request("wrong", Map.of("id", "guide", "revision", "1"), ""),
                    request("sourceEditor", Map.of("id", "guide"), ""),
                    request("sourceEditor", Map.of("id", "guide", "revision", "1"), "cursor"),
                    request("sourceEditor", Map.of("id", "guide", "revision", "0"), ""),
                    request("sourceEditor", Map.of("id", "guide", "revision", "bad"), ""),
                    request("sourceEditor", Map.of("id", "guide", "revision", "2"), ""));
            invalid.forEach(value -> assertThrows(
                    IllegalArgumentException.class,
                    () -> started.query(support.request("view.source", value, Optional.empty(), 0))));
        } finally {
            extension.close();
        }
    }

    @Test
    void importRevisionChecksCoverFirstSubmissionCurrentSourceAndCompletedReplay() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        KnowledgeExtension extension = new KnowledgeExtension();
        BuiltinExtensionTestSupport.Started started = support.start(extension);
        try {
            KnowledgeContracts.ImportRequest first = importRequest("first", "1".repeat(64));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> started.command(support.request("source/import", first, Optional.empty(), 0)));
            KnowledgeContracts.ImportAccepted accepted = support.decode(
                    started.command(support.request("source/import", first, Optional.of("first"), 0)),
                    KnowledgeContracts.ImportAccepted.class);
            assertEquals(1, accepted.targetSourceRevision());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> started.command(support.request(
                            "source/import", importRequest("missing", "2".repeat(64)), Optional.of("missing"), 1)));

            putSource(support, source("current", 1, "generation-current"), 0);
            ExtensionExecutionContext currentContext = copyContext(
                    started.context(), new FixedKnowledgeJobPort(job(support, "job-current", "current", 2), true));
            ExtensionResponse current = command(
                    started,
                    support.request(
                            "source/import", importRequest("current", "3".repeat(64)), Optional.of("current"), 1),
                    currentContext);
            assertEquals(2, current.revision());

            putSource(support, source("replayed", 1, "generation-job-replay"), 0);
            ExtensionExecutionContext replayContext = copyContext(
                    started.context(), new FixedKnowledgeJobPort(job(support, "job-replay", "replayed", 1), false));
            assertEquals(
                    1,
                    command(
                                    started,
                                    support.request(
                                            "source/import",
                                            importRequest("replayed", "4".repeat(64)),
                                            Optional.of("replayed"),
                                            0),
                                    replayContext)
                            .revision());

            ExtensionExecutionContext staleContext = copyContext(
                    started.context(), new FixedKnowledgeJobPort(job(support, "job-stale", "current", 3), false));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> command(
                            started,
                            support.request(
                                    "source/import", importRequest("current", "5".repeat(64)), Optional.of("stale"), 2),
                            staleContext));
        } finally {
            extension.close();
        }
    }

    @Test
    void deleteRequiresIdempotencyExistingSourceAndExactRevision() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        KnowledgeExtension extension = new KnowledgeExtension();
        BuiltinExtensionTestSupport.Started started = support.start(extension);
        try {
            putSource(support, source("guide", 1, "generation-guide"), 0);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> started.command(support.request(
                            "source/delete", new KnowledgeContracts.Key("guide"), Optional.empty(), 1)));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> started.command(support.request(
                            "source/delete", new KnowledgeContracts.Key("missing"), Optional.of("missing"), 1)));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> started.command(support.request(
                            "source/delete", new KnowledgeContracts.Key("guide"), Optional.of("stale"), 2)));
            DocumentContracts.Deleted deleted = support.decode(
                    started.command(support.request(
                            "source/delete", new KnowledgeContracts.Key("guide"), Optional.of("delete"), 1)),
                    DocumentContracts.Deleted.class);
            assertEquals("guide", deleted.id());
        } finally {
            extension.close();
        }
    }

    @Test
    void searchIndexRejectsMissingGenerationAndMismatchedChunkMetadata() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        KnowledgeExtension extension = new KnowledgeExtension();
        BuiltinExtensionTestSupport.Started started = support.start(extension);
        try {
            putSource(support, source("missing-generation", 1, "generation-missing"), 0);
            assertSearchFails(started, support, IllegalStateException.class);
        } finally {
            extension.close();
        }

        BuiltinExtensionTestSupport mismatchSupport = new BuiltinExtensionTestSupport();
        KnowledgeExtension mismatch = new KnowledgeExtension();
        BuiltinExtensionTestSupport.Started mismatchStarted = mismatchSupport.start(mismatch);
        try {
            putSource(mismatchSupport, source("mismatch", 1, "generation-mismatch"), 0);
            putGeneration(mismatchSupport, generation("mismatch", 1), 0);
            assertSearchFails(mismatchStarted, mismatchSupport, IllegalStateException.class);
        } finally {
            mismatch.close();
        }
    }

    @Test
    void indexReaderTraversesFullStoragePageBeforeDetectingMissingGeneration() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        KnowledgeExtension extension = new KnowledgeExtension();
        BuiltinExtensionTestSupport.Started started = support.start(extension);
        try {
            for (int index = 0; index < 500; index++) {
                String id = "source-%03d".formatted(index);
                putSource(support, source(id, 1, "generation-" + id), 0);
            }
            assertSearchFails(started, support, IllegalStateException.class);
        } finally {
            extension.close();
        }
    }

    @Test
    void jobViewCoversNextCursorAndTerminalPage() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        KnowledgeExtension extension = new KnowledgeExtension();
        BuiltinExtensionTestSupport.Started started = support.start(extension);
        try {
            started.command(
                    support.request("source/import", importRequest("alpha", "6".repeat(64)), Optional.of("alpha"), 0));
            started.command(
                    support.request("source/import", importRequest("beta", "7".repeat(64)), Optional.of("beta"), 0));
            ViewQueryResult first = view(started, support, "view.jobs", "jobs", Map.of(), "", 1);
            ViewQueryResult second = view(started, support, "view.jobs", "jobs", Map.of(), first.nextCursor(), 10);

            assertTrue(first.hasMore());
            assertFalse(first.nextCursor().isEmpty());
            assertFalse(second.hasMore());
            assertEquals(1, second.rows().size());
        } finally {
            extension.close();
        }
    }

    private static void assertMissingAndInvalidViewRequests(
            BuiltinExtensionTestSupport.Started started, BuiltinExtensionTestSupport support) {
        assertThrows(
                IllegalArgumentException.class,
                () -> started.query(
                        support.request("source/read", new KnowledgeContracts.Key("missing"), Optional.empty(), 0)));
        assertThrows(
                IllegalArgumentException.class,
                () -> started.query(support.request(
                        "generation/read", new KnowledgeContracts.Key("missing"), Optional.empty(), 0)));
        assertThrows(
                IllegalArgumentException.class, () -> view(started, support, "view.sources", "wrong", Map.of(), "", 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> view(started, support, "view.sources", "sources", Map.of("id", "x"), "", 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> view(started, support, "view.new-source", "wrong", Map.of(), "", 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> view(started, support, "view.new-source", "newSource", Map.of("id", "x"), "", 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> view(started, support, "view.new-source", "newSource", Map.of(), "cursor", 1));
        assertEquals(
                0,
                assertDoesNotThrowView(started, support, "view.new-source", "newSource")
                        .revision());
    }

    private static void assertKnowledgePages(
            BuiltinExtensionTestSupport.Started started, BuiltinExtensionTestSupport support) throws Exception {
        KnowledgeContracts.SourcePage firstSources = support.decode(
                started.query(
                        support.request("source/list", new KnowledgeContracts.PageRequest("", 1), Optional.empty(), 0)),
                KnowledgeContracts.SourcePage.class);
        KnowledgeContracts.SourcePage secondSources = support.decode(
                started.query(support.request(
                        "source/list",
                        new KnowledgeContracts.PageRequest(firstSources.nextKey(), 2),
                        Optional.empty(),
                        0)),
                KnowledgeContracts.SourcePage.class);
        KnowledgeContracts.GenerationPage generations = support.decode(
                started.query(support.request(
                        "generation/list", new KnowledgeContracts.PageRequest("", 1), Optional.empty(), 0)),
                KnowledgeContracts.GenerationPage.class);
        ViewQueryResult sourceView = view(started, support, "view.sources", "sources", Map.of(), "", 1);
        ViewQueryResult generationView = view(started, support, "view.generations", "generations", Map.of(), "", 10);

        assertEquals(
                List.of("alpha"),
                firstSources.values().stream()
                        .map(KnowledgeContracts.Source::id)
                        .toList());
        assertEquals("alpha", firstSources.nextKey());
        assertEquals(
                List.of("beta"),
                secondSources.values().stream()
                        .map(KnowledgeContracts.Source::id)
                        .toList());
        assertEquals("", secondSources.nextKey());
        assertEquals("generation-alpha", generations.nextKey());
        assertTrue(sourceView.hasMore());
        assertEquals("alpha", sourceView.nextCursor());
        assertFalse(generationView.hasMore());
        assertEquals(1, generationView.revision());
    }

    private static void assertDirectReads(
            BuiltinExtensionTestSupport.Started started, BuiltinExtensionTestSupport support) throws Exception {
        assertEquals(
                "alpha",
                support.decode(
                                started.query(support.request(
                                        "source/read", new KnowledgeContracts.Key("alpha"), Optional.empty(), 0)),
                                KnowledgeContracts.Source.class)
                        .id());
        assertEquals(
                "generation-alpha",
                support.decode(
                                started.query(support.request(
                                        "generation/read",
                                        new KnowledgeContracts.Key("generation-alpha"),
                                        Optional.empty(),
                                        0)),
                                KnowledgeContracts.Generation.class)
                        .id());
    }

    private static ViewQueryResult assertDoesNotThrowView(
            BuiltinExtensionTestSupport.Started started,
            BuiltinExtensionTestSupport support,
            String operation,
            String source) {
        try {
            return view(started, support, operation, source, Map.of(), "", 1);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static void assertSearchFails(
            BuiltinExtensionTestSupport.Started started,
            BuiltinExtensionTestSupport support,
            Class<? extends RuntimeException> type) {
        assertThrows(
                type,
                () -> started.query(support.request(
                        "search", new KnowledgeContracts.SearchRequest("query", Set.of(), 10), Optional.empty(), 0)));
    }

    private static ExtensionResponse command(
            BuiltinExtensionTestSupport.Started started, ExtensionRequest request, ExtensionExecutionContext context)
            throws Exception {
        ExtensionContributions.Command command =
                contribution(started.contributions(), ExtensionContributions.Command.class, "knowledge.command");
        return command.handler().handle(request, context);
    }

    private static ViewQueryResult view(
            BuiltinExtensionTestSupport.Started started,
            BuiltinExtensionTestSupport support,
            String operation,
            String source,
            Map<String, String> arguments,
            String cursor,
            int limit)
            throws Exception {
        return support.decode(
                started.query(
                        support.request(operation, request(source, arguments, cursor, limit), Optional.empty(), 0)),
                ViewQueryResult.class);
    }

    private static ViewQueryRequest request(String source, Map<String, String> arguments, String cursor) {
        return request(source, arguments, cursor, 1);
    }

    private static ViewQueryRequest request(String source, Map<String, String> arguments, String cursor, int limit) {
        return new ViewQueryRequest(source, arguments, cursor, limit, Optional.empty());
    }

    private static void putSource(
            BuiltinExtensionTestSupport support, KnowledgeContracts.Source source, long expectedRevision) {
        support.store.put(
                KnowledgeCollections.sources(support.workspaceId),
                source.id(),
                expectedRevision,
                support.payloads.encode(source));
    }

    private static void putGeneration(
            BuiltinExtensionTestSupport support, KnowledgeContracts.Generation generation, long expectedRevision) {
        support.store.put(
                KnowledgeCollections.generations(support.workspaceId),
                generation.id(),
                expectedRevision,
                support.payloads.encode(generation));
    }

    private static KnowledgeContracts.Source source(String id, long revision, String activeGenerationId) {
        return new KnowledgeContracts.Source(
                id,
                revision,
                id,
                new AttachmentRef(digest(id), "text/plain", id + ".txt", 20),
                activeGenerationId,
                NOW);
    }

    private static KnowledgeContracts.Generation generation(String sourceId, int chunks) {
        return new KnowledgeContracts.Generation(
                "generation-" + sourceId,
                1,
                sourceId,
                1,
                digest(sourceId),
                "plain-v1",
                KnowledgeContracts.RetrievalMode.KEYWORD,
                Optional.empty(),
                0,
                chunks,
                100,
                Optional.empty(),
                NOW);
    }

    private static KnowledgeContracts.ImportRequest importRequest(String id, String digest) {
        return new KnowledgeContracts.ImportRequest(
                id,
                id,
                new AttachmentRef(digest, "text/plain", id + ".txt", 20),
                10_000,
                500,
                50,
                KnowledgeContracts.RetrievalPreference.KEYWORD_ONLY);
    }

    private static ExtensionJob job(
            BuiltinExtensionTestSupport support, String id, String definitionId, long definitionRevision) {
        return new ExtensionJob(
                id,
                ID,
                support.workspaceId,
                KnowledgeContracts.GENERATION_JOB_TYPE,
                definitionId,
                definitionRevision,
                support.payloads.encode(Map.of()),
                ExecutionState.QUEUED,
                1,
                support.payloads.encode(Map.of()),
                1,
                Optional.empty(),
                Optional.empty(),
                NOW,
                NOW);
    }

    private static ExtensionExecutionContext copyContext(ExtensionExecutionContext source, ExtensionJobPort jobs) {
        return new ExtensionExecutionContext(
                source.extension(),
                source.workspaceId(),
                source.effectivePermissions(),
                source.cancellation(),
                source.clock(),
                source.managedStore(),
                source.turns(),
                source.executionPolicies(),
                source.scheduleTargets(),
                source.inputs(),
                jobs,
                source.evidence(),
                source.attachments(),
                source.credentials(),
                source.privateNetworkGrants(),
                source.services(),
                source.embeddings());
    }

    private static com.javaclaw.extension.spi.ExtensionJobRuntimeContext runtime(BuiltinExtensionTestSupport support) {
        return new com.javaclaw.extension.spi.ExtensionJobRuntimeContext(
                support.clock,
                support.payloads,
                support.turns,
                support.store,
                invocation -> support.service.invoke(invocation.caller(), invocation.serviceId(), invocation.request()),
                support.embeddings,
                com.javaclaw.extension.spi.AutomationStepPort.unavailable(),
                com.javaclaw.extension.spi.ScheduledCommandPort.unavailable(),
                com.javaclaw.extension.spi.ScheduleLifecyclePort.unavailable());
    }

    private static String digest(String value) {
        char digit = (char) ('0' + Math.floorMod(value.hashCode(), 10));
        return String.valueOf(digit).repeat(64);
    }

    private static <T extends ExtensionContribution> T contribution(
            List<ExtensionContribution> contributions, Class<T> type, String id) {
        return contributions.stream()
                .filter(type::isInstance)
                .map(type::cast)
                .filter(value -> value.contributionId().equals(id))
                .findFirst()
                .orElseThrow();
    }
}
