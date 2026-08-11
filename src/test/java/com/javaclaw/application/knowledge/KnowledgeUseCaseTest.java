package com.javaclaw.application.knowledge;

import com.javaclaw.application.error.NotFoundException;
import com.javaclaw.application.error.ValidationException;
import com.javaclaw.application.knowledge.KnowledgeApplicationService.Document;
import com.javaclaw.application.knowledge.KnowledgeApplicationService.Health;
import com.javaclaw.application.knowledge.KnowledgeApplicationService.HealthListener;
import com.javaclaw.application.knowledge.KnowledgeApplicationService.HealthStatus;
import com.javaclaw.application.knowledge.KnowledgeApplicationService.Scope;
import com.javaclaw.application.knowledge.KnowledgeApplicationService.SearchHit;
import com.javaclaw.application.knowledge.KnowledgeApplicationService.Settings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeUseCaseTest {

    private final FakeKnowledgePort knowledge = new FakeKnowledgePort();
    private final FakeSettingsPort settings = new FakeSettingsPort();
    private final KnowledgeUseCase useCase = new KnowledgeUseCase(
            knowledge, settings, "研发工作区");

    @Test
    void buildsImmutableWorkspaceSnapshotAndSearchesWithConfiguredLimit() {
        knowledge.documents.add(document("guide.md", Scope.WORKSPACE));
        knowledge.searchHits = List.of(new SearchHit(
                "guide.md", Scope.WORKSPACE, .92, "JavaClaw 架构"));

        var snapshot = useCase.snapshot();
        var result = useCase.search("  JavaClaw  ");

        assertEquals("研发工作区", snapshot.workspaceName());
        assertEquals(1, snapshot.totalChunks());
        assertEquals("JavaClaw", result.query());
        assertEquals(6, knowledge.searchLimit);
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.documents().add(document("later.md", Scope.GLOBAL)));
        assertThrows(ValidationException.class, () -> useCase.search("  "));
    }

    @Test
    void importsValidFilesAndReportsPartialFailure(@TempDir Path directory) throws Exception {
        Path valid = Files.writeString(directory.resolve("valid.md"), "content");
        Path missing = directory.resolve("missing.md");
        knowledge.failedFile = valid;

        var result = useCase.importFiles(List.of(valid, missing), Scope.GLOBAL);

        assertEquals(0, result.succeeded());
        assertEquals(2, result.failed());
        assertEquals(Scope.GLOBAL, knowledge.importScope);
        assertTrue(result.failures().stream().anyMatch(text -> text.contains("导入失败")));
        assertTrue(result.failures().stream().anyMatch(text -> text.contains("普通文件")));
        assertThrows(ValidationException.class, () -> useCase.importFiles(List.of(), Scope.ALL));
    }

    @Test
    void validatesSettingsAndDocumentMutations() {
        knowledge.documents.add(document("guide.md", Scope.WORKSPACE));

        useCase.setDocumentEnabled(" guide.md ", false);
        useCase.setAllEnabled(true, null);
        useCase.deleteDocument("guide.md");
        Settings saved = useCase.saveChunkSettings(512, 64);

        assertEquals("guide.md", knowledge.toggledDocument);
        assertEquals(null, knowledge.bulkScope);
        assertEquals("guide.md", knowledge.deletedDocument);
        assertEquals(512, saved.chunkSize());
        assertEquals(64, saved.chunkOverlap());
        assertThrows(NotFoundException.class, () -> useCase.deleteDocument("missing.md"));
        assertThrows(ValidationException.class, () -> useCase.saveChunkSettings(64, 8));
        assertThrows(ValidationException.class, () -> useCase.saveChunkSettings(256, 256));
    }

    @Test
    void normalizesTextImportAndReturnsRebuildSnapshot() {
        var imported = useCase.importText(" ", "  body  ", Scope.ALL);
        knowledge.rebuilt = 9;
        var rebuilt = useCase.rebuildIndex();

        assertEquals("手动导入文本", knowledge.importTitle);
        assertEquals("body", knowledge.importText);
        assertEquals(Scope.WORKSPACE, knowledge.importScope);
        assertEquals(1, imported.succeeded());
        assertEquals(9, rebuilt.rebuiltChunks());
        assertThrows(ValidationException.class,
                () -> useCase.importText("title", " ", Scope.WORKSPACE));
    }

    @Test
    void coversWorkspaceDefaultsScopesAndFailureResults(@TempDir Path directory) throws Exception {
        KnowledgeUseCase defaultWorkspace = new KnowledgeUseCase(knowledge, settings, null);
        assertEquals("默认工作区", defaultWorkspace.snapshot().workspaceName());
        assertEquals("默认工作区",
                new KnowledgeUseCase(knowledge, settings, "  ").snapshot().workspaceName());

        settings.current = new Settings(
                "OpenAI", "https://example.test", "embed", 1024, 0, 400, 50);
        useCase.search("query");
        assertEquals(1, knowledge.searchLimit);

        Path valid = Files.writeString(directory.resolve("valid.md"), "content");
        List<Path> candidates = new ArrayList<>();
        candidates.add(valid);
        candidates.add(null);
        candidates.add(valid);
        var files = useCase.importFiles(candidates, null);
        assertEquals(1, files.succeeded());
        assertEquals(Scope.WORKSPACE, knowledge.importScope);

        knowledge.importTextSucceeds = false;
        var text = useCase.importText(" title ", "body", Scope.GLOBAL);
        assertEquals(0, text.succeeded());
        assertEquals(List.of("文本导入失败"), text.failures());
        assertEquals("title", knowledge.importTitle);

        useCase.setAllEnabled(false, Scope.WORKSPACE);
        assertEquals(Scope.WORKSPACE, knowledge.bulkScope);
        useCase.setAllEnabled(true, Scope.ALL);
        assertEquals(null, knowledge.bulkScope);
        assertEquals(0, useCase.clear().documents().size());

        try (AutoCloseable observation = useCase.observeHealth(health -> { })) {
            assertTrue(knowledge.observing);
        }
        assertFalse(knowledge.observing);
        assertThrows(NullPointerException.class, () -> useCase.observeHealth(null));
        assertThrows(ValidationException.class, () -> useCase.importFiles(null, Scope.ALL));
        assertThrows(ValidationException.class, () -> useCase.search(null));
        assertThrows(NullPointerException.class,
                () -> new KnowledgeUseCase(null, settings, "workspace"));
        assertThrows(NullPointerException.class,
                () -> new KnowledgeUseCase(knowledge, null, "workspace"));
    }

    @Test
    void rejectsOversizedFilesDeleteRacesAndEveryChunkBoundary(@TempDir Path directory)
            throws Exception {
        Path large = directory.resolve("large.bin");
        try (var channel = Files.newByteChannel(large,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            channel.position(KnowledgeUseCase.MAX_IMPORT_FILE_SIZE);
            channel.write(ByteBuffer.wrap(new byte[] {1}));
        }
        var imported = useCase.importFiles(List.of(large), Scope.WORKSPACE);
        assertEquals(1, imported.failed());
        assertTrue(imported.failures().getFirst().contains("文件过大"));

        knowledge.documents.add(document("race.md", Scope.WORKSPACE));
        knowledge.deleteCount = 0;
        assertThrows(NotFoundException.class, () -> useCase.deleteDocument("race.md"));
        assertThrows(ValidationException.class, () -> useCase.setDocumentEnabled(null, true));

        assertEquals(1024, useCase.saveChunkSettings(1024, 256).chunkSize());
        assertThrows(ValidationException.class, () -> useCase.saveChunkSettings(1025, 0));
        assertThrows(ValidationException.class, () -> useCase.saveChunkSettings(256, -1));
        assertThrows(ValidationException.class, () -> useCase.saveChunkSettings(512, 257));
    }

    private static Document document(String name, Scope scope) {
        return new Document(name, scope, true, 1, "2026-08-12", "summary", List.of("preview"));
    }

    private static final class FakeKnowledgePort implements KnowledgePort {
        private final List<Document> documents = new ArrayList<>();
        private List<SearchHit> searchHits = List.of();
        private int searchLimit;
        private Path failedFile;
        private Scope importScope;
        private String importTitle;
        private String importText;
        private String toggledDocument;
        private Scope bulkScope;
        private String deletedDocument;
        private int rebuilt;
        private int deleteCount = 1;
        private boolean importTextSucceeds = true;
        private boolean observing;

        @Override public boolean enabled() { return true; }
        @Override public String initializationError() { return ""; }
        @Override public Health health() { return new Health(HealthStatus.HEALTHY, ""); }
        @Override public AutoCloseable observeHealth(HealthListener listener) {
            observing = true;
            return () -> observing = false;
        }
        @Override public List<Document> documents() { return List.copyOf(documents); }
        @Override public List<SearchHit> search(String query, int limit) {
            searchLimit = limit;
            return searchHits;
        }
        @Override public boolean importFile(Path file, Scope scope) {
            importScope = scope;
            return !file.equals(failedFile);
        }
        @Override public boolean importText(String title, String text, Scope scope) {
            importTitle = title;
            importText = text;
            importScope = scope;
            return importTextSucceeds;
        }
        @Override public void setDocumentEnabled(String name, boolean enabled) {
            toggledDocument = name;
        }
        @Override public void setAllEnabled(boolean enabled, Scope scope) { bulkScope = scope; }
        @Override public int deleteDocument(String name) {
            deletedDocument = name;
            return deleteCount;
        }
        @Override public int clear() { return 0; }
        @Override public int rebuildIndex() { return rebuilt; }
    }

    private static final class FakeSettingsPort implements KnowledgeSettingsPort {
        private Settings current = new Settings(
                "OpenAI", "https://example.test", "embed", 1024, 6, 400, 50);

        @Override public Settings load() { return current; }

        @Override
        public Settings saveChunkSettings(int chunkSize, int chunkOverlap) {
            current = new Settings(current.provider(), current.baseUrl(), current.model(),
                    current.dimensions(), current.retrieveLimit(), chunkSize, chunkOverlap);
            return current;
        }
    }
}
