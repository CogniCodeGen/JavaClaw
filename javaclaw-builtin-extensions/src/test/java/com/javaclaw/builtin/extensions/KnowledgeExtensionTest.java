package com.javaclaw.builtin.extensions;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AttachmentRef;
import com.javaclaw.api.ExecutionState;
import com.javaclaw.builtin.contracts.KnowledgeContracts;
import com.javaclaw.extension.spi.AutomationStepPort;
import com.javaclaw.extension.spi.EmbeddingPort;
import com.javaclaw.extension.spi.ExpectedRevisionBinding;
import com.javaclaw.extension.spi.ExtensionJob;
import com.javaclaw.extension.spi.ExtensionJobExecution;
import com.javaclaw.extension.spi.ExtensionJobExecutor;
import com.javaclaw.extension.spi.ExtensionJobRuntimeContext;
import com.javaclaw.extension.spi.ExtensionJobStepResult;
import com.javaclaw.extension.spi.ExtensionJobUnit;
import com.javaclaw.extension.spi.ExtensionJobUnitState;
import com.javaclaw.extension.spi.ExtensionJobWorkUnit;
import com.javaclaw.extension.spi.ScheduledCommandPort;
import com.javaclaw.extension.spi.ViewBinding;
import com.javaclaw.extension.spi.ViewField;
import com.javaclaw.extension.spi.ViewFieldType;
import com.javaclaw.extension.spi.ViewQueryRequest;
import com.javaclaw.extension.spi.ViewQueryResult;
import com.javaclaw.extension.spi.ViewSchema;

import static com.javaclaw.builtin.extensions.BuiltinExtensionTestSupport.NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeExtensionTest {
    @Test
    void managementViewBindsPlatformAttachmentDirectlyToSourceImport() {
        ViewSchema view = KnowledgeExtensionPresentation.managementView();
        ViewSchema.Form form = (ViewSchema.Form) view.nodes().getFirst();
        var attachment = form.fields().stream()
                .filter(ViewField.class::isInstance)
                .map(ViewField.class::cast)
                .filter(field -> field.type() == ViewFieldType.ATTACHMENT)
                .findFirst()
                .orElseThrow();

        assertEquals("source/import", form.submit().command());
        assertEquals("attachment", attachment.name());
        assertEquals(
                16L * 1024 * 1024,
                attachment.validation().attachment().orElseThrow().maximumBytes());
        assertTrue(attachment.validation().attachment().orElseThrow().accepts("application/pdf"));
        assertTrue(attachment.validation().attachment().orElseThrow().accepts("text/markdown"));
    }

    @Test
    void deleteUsesSelectedSourceRevisionWhenPageContainsMixedRevisions() throws Exception {
        BuiltinExtensionTestSupport support = supportWithExtraction();
        KnowledgeExtension extension = new KnowledgeExtension();
        var started = support.start(extension);
        KnowledgeContracts.ImportAccepted first = submit(support, started, importRequest("guide", "a".repeat(64)), 0);
        executeJob(support, extension, first.jobId());
        KnowledgeContracts.ImportAccepted replacement =
                submit(support, started, importRequest("guide", "b".repeat(64)), 1);
        executeJob(support, extension, replacement.jobId());
        KnowledgeContracts.ImportAccepted notes = submit(support, started, importRequest("notes", "c".repeat(64)), 0);
        executeJob(support, extension, notes.jobId());
        ViewQueryRequest query = new ViewQueryRequest("sources", java.util.Map.of(), "", 100, Optional.empty());
        ViewQueryResult page = support.decode(
                started.query(support.request("view.sources", query, Optional.empty(), 0)), ViewQueryResult.class);
        ViewSchema.Table sources = KnowledgeExtensionPresentation.managementView().nodes().stream()
                .filter(ViewSchema.Table.class::isInstance)
                .map(ViewSchema.Table.class::cast)
                .filter(table -> table.id().equals("sources"))
                .findFirst()
                .orElseThrow();

        assertEquals(2, page.revision());
        assertEquals(
                new ExpectedRevisionBinding.RowField("revision"),
                sources.actions().getFirst().expectedRevision());
        started.command(
                support.request("source/delete", new KnowledgeContracts.Key("notes"), Optional.of("delete-notes"), 1));

        assertThrows(
                IllegalArgumentException.class,
                () -> started.query(
                        support.request("source/read", new KnowledgeContracts.Key("notes"), Optional.empty(), 0)));
        assertEquals(2, readSource(support, started).revision());
    }

    @Test
    void rebuildFormReadsOneAuthoritativeSourceRevision() throws Exception {
        BuiltinExtensionTestSupport support = supportWithExtraction();
        KnowledgeExtension extension = new KnowledgeExtension();
        var started = support.start(extension);
        KnowledgeContracts.ImportAccepted accepted =
                submit(support, started, importRequest("guide", "d".repeat(64)), 0);
        executeJob(support, extension, accepted.jobId());

        ViewQueryRequest query = new ViewQueryRequest(
                "sourceEditor", java.util.Map.of("id", "guide", "revision", "1"), "", 1, Optional.empty());
        ViewQueryResult detail = support.decode(
                started.query(support.request("view.source", query, Optional.empty(), 0)), ViewQueryResult.class);
        KnowledgeContracts.Source source = support.payloads.decode(detail.values(), KnowledgeContracts.Source.class);
        ViewSchema.Form rebuild = KnowledgeExtensionPresentation.managementView().nodes().stream()
                .filter(ViewSchema.Form.class::isInstance)
                .map(ViewSchema.Form.class::cast)
                .filter(form -> form.id().equals("source-rebuild"))
                .findFirst()
                .orElseThrow();

        assertEquals("guide", source.id());
        assertEquals(1, detail.revision());
        assertEquals(
                new ExpectedRevisionBinding.SourceRevision("sourceEditor"),
                rebuild.submit().expectedRevision());
        assertEquals("source/import", rebuild.submit().command());
        assertFalse(rebuild.fields().stream().anyMatch(field -> field.name().equals("id")));
        assertEquals(1, rebuild.submit().commandBindings().size());
        assertEquals("id", rebuild.submit().commandBindings().getFirst().argumentName());
        assertEquals(
                new ViewBinding("sourceEditor", "id"),
                rebuild.submit().commandBindings().getFirst().binding());
        assertEquals(
                java.util.List.of("id", "revision"),
                KnowledgeExtensionPresentation.managementView().dataSources().stream()
                        .filter(sourceData -> sourceData.id().equals("sourceEditor"))
                        .findFirst()
                        .orElseThrow()
                        .argumentBindings()
                        .stream()
                        .map(com.javaclaw.extension.spi.ViewArgumentBinding::argument)
                        .toList());
    }

    @Test
    void rebuildRejectsStaleSourceRevision() throws Exception {
        BuiltinExtensionTestSupport support = supportWithExtraction();
        KnowledgeExtension extension = new KnowledgeExtension();
        var started = support.start(extension);
        KnowledgeContracts.ImportAccepted accepted =
                submit(support, started, importRequest("guide", "e".repeat(64)), 0);
        executeJob(support, extension, accepted.jobId());

        assertThrows(
                IllegalArgumentException.class,
                () -> submit(support, started, importRequest("guide", "f".repeat(64)), 0));
        assertEquals(1, readSource(support, started).revision());
        assertEquals(
                1,
                support.jobs
                        .list(Optional.empty(), Optional.empty(), Set.of(), 100)
                        .size());
        assertThrows(
                IllegalArgumentException.class,
                () -> started.query(support.request(
                        "view.source",
                        new ViewQueryRequest(
                                "sourceEditor",
                                java.util.Map.of("id", "guide", "revision", "2"),
                                "",
                                1,
                                Optional.empty()),
                        Optional.empty(),
                        0)));
    }

    @Test
    void importReturnsBeforeJobAndAtomicallyActivatesSearchableHybridGeneration() throws Exception {
        BuiltinExtensionTestSupport support = supportWithExtraction();
        KnowledgeExtension extension = new KnowledgeExtension();
        var started = support.start(extension);
        KnowledgeContracts.ImportAccepted accepted = submit(support, started, importRequest("a".repeat(64)), 0);

        assertThrows(
                IllegalArgumentException.class,
                () -> started.query(
                        support.request("source/read", new KnowledgeContracts.Key("guide"), Optional.empty(), 0)));

        ExtensionJobStepResult completed = executeJob(support, extension, accepted.jobId());
        KnowledgeContracts.Generation generation =
                support.payloads.decode(completed.result(), KnowledgeContracts.Generation.class);
        KnowledgeContracts.Source source = support.decode(
                started.query(support.request("source/read", new KnowledgeContracts.Key("guide"), Optional.empty(), 0)),
                KnowledgeContracts.Source.class);
        KnowledgeContracts.SearchResult search = support.decode(
                started.query(support.request(
                        "search",
                        new KnowledgeContracts.SearchRequest("unrelated semantic query", Set.of(), 10),
                        Optional.empty(),
                        0)),
                KnowledgeContracts.SearchResult.class);

        assertEquals(ExecutionState.COMPLETED, completed.nextState());
        assertEquals(generation.id(), source.activeGenerationId());
        assertEquals(KnowledgeContracts.RetrievalMode.HYBRID, generation.retrievalMode());
        assertEquals(
                List.of("guide"),
                search.matches().stream().map(match -> match.source().id()).toList());
        assertTrue(search.matches().stream()
                .allMatch(match -> match.retrievalMode() == KnowledgeContracts.RetrievalMode.HYBRID));
    }

    @Test
    void failedReplacementKeepsPreviouslyActiveGeneration() throws Exception {
        BuiltinExtensionTestSupport support = supportWithExtraction();
        KnowledgeExtension extension = new KnowledgeExtension();
        var started = support.start(extension);
        KnowledgeContracts.ImportAccepted first = submit(support, started, importRequest("a".repeat(64)), 0);
        executeJob(support, extension, first.jobId());
        KnowledgeContracts.Source original = readSource(support, started);

        support.service = (caller, serviceId, request) -> support.payloads.encode(
                new KnowledgeContracts.ExtractionResult("0".repeat(64), "plain-v1", "replacement"));
        KnowledgeContracts.ImportAccepted replacement =
                submit(support, started, importRequest("b".repeat(64)), original.revision());

        assertThrows(IllegalStateException.class, () -> executeJob(support, extension, replacement.jobId()));
        assertEquals(original, readSource(support, started));
    }

    @Test
    void missingEmbeddingEndpointCreatesExplicitKeywordFallback() throws Exception {
        BuiltinExtensionTestSupport support = supportWithExtraction();
        support.embeddings = EmbeddingPort.unavailable();
        KnowledgeExtension extension = new KnowledgeExtension();
        var started = support.start(extension);
        KnowledgeContracts.ImportAccepted accepted = submit(support, started, importRequest("c".repeat(64)), 0);

        KnowledgeContracts.Generation generation = support.payloads.decode(
                executeJob(support, extension, accepted.jobId()).result(), KnowledgeContracts.Generation.class);

        assertEquals(KnowledgeContracts.RetrievalMode.KEYWORD, generation.retrievalMode());
        assertEquals(
                KnowledgeContracts.FallbackReason.EMBEDDING_UNAVAILABLE,
                generation.fallbackReason().orElseThrow());
    }

    @Test
    void managementJobRowsDoNotExposeFrozenInputOrCheckpoint() throws Exception {
        BuiltinExtensionTestSupport support = supportWithExtraction();
        KnowledgeExtension extension = new KnowledgeExtension();
        var started = support.start(extension);
        submit(support, started, importRequest("e".repeat(64)), 0);

        ViewQueryResult jobs = support.decode(
                started.query(support.request(
                        "view.jobs",
                        new ViewQueryRequest("jobs", java.util.Map.of(), "", 100, Optional.empty()),
                        Optional.empty(),
                        0)),
                ViewQueryResult.class);

        assertEquals(1, jobs.rows().size());
        assertEquals(
                Set.of("id", "state", "definitionId", "definitionRevision", "updatedAt"),
                support.payloads
                        .decode(jobs.rows().getFirst(), java.util.Map.class)
                        .keySet());
    }

    private static BuiltinExtensionTestSupport supportWithExtraction() {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        support.service = (caller, serviceId, payload) -> {
            KnowledgeContracts.ExtractionRequest request =
                    support.payloads.decode(payload, KnowledgeContracts.ExtractionRequest.class);
            return support.payloads.encode(new KnowledgeContracts.ExtractionResult(
                    request.attachment().digest(), "plain-v1", "JavaClaw architecture and extension boundaries"));
        };
        return support;
    }

    private static KnowledgeContracts.ImportAccepted submit(
            BuiltinExtensionTestSupport support,
            BuiltinExtensionTestSupport.Started started,
            KnowledgeContracts.ImportRequest request,
            long expectedRevision)
            throws Exception {
        return support.decode(
                started.command(support.request(
                        "source/import",
                        request,
                        Optional.of("import-" + request.attachment().digest()),
                        expectedRevision)),
                KnowledgeContracts.ImportAccepted.class);
    }

    private static KnowledgeContracts.Source readSource(
            BuiltinExtensionTestSupport support, BuiltinExtensionTestSupport.Started started) throws Exception {
        return support.decode(
                started.query(support.request("source/read", new KnowledgeContracts.Key("guide"), Optional.empty(), 0)),
                KnowledgeContracts.Source.class);
    }

    private static ExtensionJobStepResult executeJob(
            BuiltinExtensionTestSupport support, KnowledgeExtension extension, String jobId) throws Exception {
        ExtensionJob queued = support.jobs.find(jobId).orElseThrow();
        ExtensionJobExecutor executor =
                extension.jobExecutors(runtimeContext(support)).getFirst().executor();
        ExtensionJobWorkUnit work = executor.plan(queued).orElseThrow();
        ExtensionJob running = new ExtensionJob(
                queued.id(),
                queued.extensionId(),
                queued.workspaceId(),
                queued.jobType(),
                queued.definitionId(),
                queued.definitionRevision(),
                queued.frozenInput(),
                ExecutionState.RUNNING,
                2,
                queued.checkpoint(),
                2,
                Optional.of(1L),
                Optional.empty(),
                queued.createdAt(),
                NOW);
        ExtensionJobUnit unit = new ExtensionJobUnit(
                queued.id(),
                1,
                work.unitId(),
                work.intent(),
                ExtensionJobUnitState.INTENT_RECORDED,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                NOW,
                Optional.empty());
        return executor.execute(new ExtensionJobExecution(running, unit), support.cancellation);
    }

    private static ExtensionJobRuntimeContext runtimeContext(BuiltinExtensionTestSupport support) {
        return new ExtensionJobRuntimeContext(
                support.clock,
                support.payloads,
                support.turns,
                support.store,
                invocation -> support.service.invoke(invocation.caller(), invocation.serviceId(), invocation.request()),
                support.embeddings,
                AutomationStepPort.unavailable(),
                ScheduledCommandPort.unavailable(),
                com.javaclaw.extension.spi.ScheduleLifecyclePort.unavailable());
    }

    private static KnowledgeContracts.ImportRequest importRequest(String digest) {
        return importRequest("guide", digest);
    }

    private static KnowledgeContracts.ImportRequest importRequest(String id, String digest) {
        return new KnowledgeContracts.ImportRequest(
                id,
                id,
                new AttachmentRef(digest, "text/plain", id + ".txt", 42),
                10_000,
                500,
                50,
                KnowledgeContracts.RetrievalPreference.EMBEDDING_PREFERRED);
    }
}
