package com.javaclaw.agent.expert;

import com.javaclaw.application.knowledge.KnowledgeDocumentPreferencePort;
import com.javaclaw.agent.model.ModelFactory;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.memory.embed.EmbeddingPurpose;
import com.javaclaw.memory.embed.TestEmbeddingGatewayFactory;
import com.javaclaw.platform.data.DataRoot;
import com.javaclaw.platform.spring.ApplicationContexts;
import com.javaclaw.util.ProjectAccessPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeExpertBehaviorTest {

    @TempDir
    Path temporaryDirectory;

    private AnnotationConfigApplicationContext context;
    private TestEmbeddingGatewayFactory.Fixture embeddings;
    private ModelFactory models;
    private KnowledgeExpert expert;

    @AfterEach
    void closeResources() {
        if (expert != null) expert.close();
        if (models != null) models.close();
        if (embeddings != null) embeddings.close();
        if (context != null) context.close();
    }

    @Test
    void disabledAndFailedInitializationDegradeWithoutOpeningStores() {
        AgentConfig config = config(false);
        embeddings = TestEmbeddingGatewayFactory.create(4,
                (text, timeout) -> new double[]{1, 0, 0, 0});
        Preferences preferences = new Preferences();
        models = new ModelFactory(config);
        expert = new KnowledgeExpert(models, embeddings.gateway(), config,
                null, null, preferences);

        assertFalse(expert.isRagEnabled());
        assertNull(expert.ragInitializationError());
        assertFalse(expert.hasDocuments());
        assertEquals(0, expert.getTotalChunkCount());
        assertTrue(expert.getDocumentNames().isEmpty());
        assertTrue(expert.getDocumentNames(KnowledgeExpert.Scope.GLOBAL).isEmpty());
        assertTrue(expert.allKnowledgeChunks().isEmpty());
        assertNull(expert.retrieveContext("query", null));
        assertEquals(0, expert.reindexDocument("doc"));
        assertEquals(0, expert.reindexDocument(null));
        assertEquals(0, expert.reindexAll());
        assertTrue(expert.searchTest("query", 3).isEmpty());
        assertTrue(expert.getDocumentChunkPreviews("doc", 2).isEmpty());
        assertTrue(expert.knowledge_list().contains("未启用"));
        assertTrue(expert.knowledge_delete("doc").contains("未启用"));
        assertTrue(expert.knowledge_clear().contains("未启用"));
        assertTrue(expert.knowledge_search("query").contains("未启用"));
        assertTrue(expert.importText("text", "title", KnowledgeExpert.Scope.WORKSPACE)
                .contains("未启用"));
        assertTrue(expert.importFile("missing", KnowledgeExpert.Scope.WORKSPACE)
                .contains("未启用"));
        assertFalse(expert.isDocEnabled(null));
        assertTrue(expert.isDocEnabled("new"));
        expert.setDocEnabled(null, false);
        expert.setAllEnabled(false, null);
        assertTrue(expert.getEnabledDocs().isEmpty());
        assertEquals(0, expert.getEnabledDocCount());
        assertEquals(KnowledgeExpert.Scope.WORKSPACE, expert.getDocumentScope("missing"));

        expert.close();
        expert = null;
        config.setRagEnabled(true);
        expert = new KnowledgeExpert(models, embeddings.gateway(), config,
                null, temporaryDirectory.resolve("workspace"), preferences);
        assertFalse(expert.isRagEnabled());
        assertNotNull(expert.ragInitializationError());
    }

    @Test
    void importsSearchesReindexesPreferencesAndDeletesAcrossBothScopes() throws Exception {
        AgentConfig config = config(true);
        config.setRagRetrieveLimit(3);
        config.setRagScoreThreshold(-1.0);
        AtomicBoolean embeddingFails = new AtomicBoolean();
        AtomicInteger embeddingCalls = new AtomicInteger();
        embeddings = TestEmbeddingGatewayFactory.create(4, (text, timeout) -> {
            embeddingCalls.incrementAndGet();
            if (embeddingFails.get()) throw new IllegalStateException("embedding offline");
            int bucket = Math.floorMod(text.hashCode(), 3);
            return switch (bucket) {
                case 0 -> new double[]{1, 0, 0, 0};
                case 1 -> new double[]{0, 1, 0, 0};
                default -> new double[]{0, 0, 1, 0};
            };
        });
        Preferences preferences = new Preferences();
        preferences.excluded.add("disabled-on-load");
        models = new ModelFactory(config);
        expert = new KnowledgeExpert(models, embeddings.gateway(), config,
                temporaryDirectory.resolve("global"), temporaryDirectory.resolve("workspace"),
                preferences);

        assertTrue(expert.isRagEnabled());
        assertNull(expert.ragInitializationError());
        assertFalse(expert.isDocEnabled("disabled-on-load"));
        assertNotNull(expert.getTool());
        assertNotNull(expert.embeddingHealth());
        AtomicInteger healthEvents = new AtomicInteger();
        AutoCloseable healthSubscription = expert.onEmbeddingHealthChanged(
                ignored -> healthEvents.incrementAndGet());
        assertEquals(1, healthEvents.get());
        healthSubscription.close();

        assertTrue(expert.importText(null, "title", KnowledgeExpert.Scope.WORKSPACE)
                .contains("不能为空"));
        assertTrue(expert.importText(" ", "title", KnowledgeExpert.Scope.WORKSPACE)
                .contains("不能为空"));
        assertTrue(expert.importText("api_key=super-secret-value", "title",
                KnowledgeExpert.Scope.WORKSPACE).contains("凭据"));
        assertTrue(expert.importText("safe", "token=super-secret-value",
                KnowledgeExpert.Scope.WORKSPACE).contains("凭据"));

        assertTrue(expert.importText("alpha beta workspace knowledge", null,
                KnowledgeExpert.Scope.WORKSPACE).contains("已导入"));
        assertTrue(expert.importText("alpha global reference", "global-doc",
                KnowledgeExpert.Scope.GLOBAL).contains("已导入"));
        assertTrue(expert.hasDocuments());
        assertEquals(2, expert.getDocumentCount());
        assertTrue(expert.getDocumentNames().contains("手动导入文本"));
        assertEquals(List.of("global-doc"), expert.getDocumentNames(KnowledgeExpert.Scope.GLOBAL));
        assertEquals(KnowledgeExpert.Scope.GLOBAL, expert.getDocumentScope("global-doc"));
        assertEquals(KnowledgeExpert.Scope.WORKSPACE, expert.getDocumentScope("手动导入文本"));
        assertTrue(expert.getDocumentChunkCount("global-doc") > 0);
        assertNotNull(expert.getDocumentImportTime("global-doc"));
        assertNull(expert.getDocumentImportTime("missing"));
        assertTrue(expert.knowledge_list().contains("[全局] global-doc"));
        assertTrue(expert.knowledge_list().contains("[工作区] 手动导入文本"));

        assertTrue(expert.knowledge_search(null).contains("不能为空"));
        assertTrue(expert.knowledge_search(" ").contains("不能为空"));
        assertTrue(expert.knowledge_search("does-not-exist").contains("未找到"));
        assertTrue(expert.knowledge_search("alpha beta").contains("参考"));
        assertNotNull(expert.retrieveContext("alpha", null));
        assertNotNull(expert.retrieveContext("alpha", Set.of("global-doc")));
        assertTrue(expert.searchTest("alpha", 2).size() <= 2);
        assertTrue(expert.searchTest(null, 2).isEmpty());
        assertTrue(expert.searchTest(" ", 2).isEmpty());
        assertFalse(expert.getDocumentChunkPreviews("global-doc", 2).isEmpty());
        assertTrue(expert.getDocumentChunkPreviews("global-doc", 0).isEmpty());
        assertTrue(expert.getDocumentChunkPreviews(null, 2).isEmpty());

        assertTrue(expert.reindexDocument("global-doc") > 0);
        assertEquals(0, expert.reindexDocument("missing"));
        assertEquals(0, expert.reindexDocument(null));
        assertTrue(expert.reindexAll() >= 2);

        expert.setDocEnabled("global-doc", false);
        assertFalse(expert.isDocEnabled("global-doc"));
        assertTrue(preferences.excluded.contains("global-doc"));
        expert.setDocEnabled("global-doc", false);
        expert.setDocEnabled("global-doc", true);
        assertTrue(expert.isDocEnabled("global-doc"));
        expert.setAllEnabled(false, KnowledgeExpert.Scope.GLOBAL);
        assertFalse(expert.isDocEnabled("global-doc"));
        expert.setAllEnabled(true, KnowledgeExpert.Scope.GLOBAL);
        expert.setAllEnabled(false, null);
        assertTrue(expert.getEnabledDocs().isEmpty());
        assertTrue(expert.searchTest("alpha", 2).isEmpty());
        expert.setAllEnabled(true, null);
        assertEquals(2, expert.getEnabledDocCount());

        embeddingFails.set(true);
        assertFalse(expert.searchTest("alpha beta", 2).isEmpty());
        String fallback = expert.retrieveContext("alpha beta", Set.of("global-doc"));
        assertNotNull(fallback);
        assertTrue(fallback.contains("命中") || fallback.contains("参考"));
        assertEquals(0, expert.reindexDocument("global-doc"));
        embeddingFails.set(false);
        embeddings.gateway().probe();

        assertTrue(expert.knowledge_delete(null).contains("不能为空"));
        assertTrue(expert.knowledge_delete(" ").contains("不能为空"));
        assertTrue(expert.knowledge_delete("missing").contains("未找到"));
        assertTrue(expert.deleteDocument(null) == 0);
        assertTrue(expert.deleteDocument(" ") == 0);
        assertTrue(expert.knowledge_delete("global-doc").contains("已删除"));
        assertTrue(expert.knowledge_clear().contains("已清空"));
        assertFalse(expert.hasDocuments());
        assertTrue(expert.knowledge_list().contains("知识库为空"));
        assertTrue(embeddingCalls.get() > 0);
    }

    @Test
    void fileImportEnforcesProjectBoundaryTypesAndCredentialSafeNames() throws Exception {
        AgentConfig config = config(true);
        embeddings = TestEmbeddingGatewayFactory.create(4,
                (text, timeout) -> new double[]{1, 0, 0, 0});
        models = new ModelFactory(config);
        expert = new KnowledgeExpert(models, embeddings.gateway(), config,
                temporaryDirectory.resolve("global-files"),
                temporaryDirectory.resolve("workspace-files"), new Preferences());

        Path importDirectory = Files.createTempDirectory(
                ProjectAccessPolicy.projectRoot().resolve("target"), "knowledge-import-");
        assertTrue(expert.importFile(importDirectory.resolve("missing.txt").toString(),
                KnowledgeExpert.Scope.WORKSPACE).contains("不存在"));
        Path unsupported = Files.writeString(importDirectory.resolve("unsupported.bin"), "safe");
        assertTrue(expert.importFile(unsupported.toString(), KnowledgeExpert.Scope.WORKSPACE)
                .contains("不支持"));
        Path credential = Files.writeString(importDirectory.resolve("credential.txt"),
                "password=super-secret-value");
        assertTrue(expert.importFile(credential.toString(), KnowledgeExpert.Scope.WORKSPACE)
                .contains("凭据"));
        Path credentialName = Files.writeString(
                importDirectory.resolve("token=super-secret-value.txt"), "safe text");
        assertTrue(expert.importFile(credentialName.toString(), KnowledgeExpert.Scope.WORKSPACE)
                .contains("凭据"));

        for (String extension : List.of(
                "txt", "text", "md", "markdown", "log", "csv", "json", "xml", "html", "htm")) {
            Path file = Files.writeString(importDirectory.resolve("document." + extension),
                    "alpha content for " + extension);
            assertTrue(expert.importFile(file.toString(), KnowledgeExpert.Scope.WORKSPACE)
                    .contains("已导入"), extension);
        }
        assertTrue(expert.getDocumentCount() >= 10);
        assertTrue(expert.importFile(null, KnowledgeExpert.Scope.WORKSPACE).contains("导入失败"));
        assertTrue(expert.importFile(temporaryDirectory.resolve("outside.txt").toString(),
                KnowledgeExpert.Scope.WORKSPACE).contains("导入失败"));
    }

    @Test
    void preferenceFailuresAreIsolatedFromKnowledgeOperations() {
        AgentConfig config = config(true);
        embeddings = TestEmbeddingGatewayFactory.create(4,
                (text, timeout) -> new double[]{1, 0, 0, 0});
        Preferences preferences = new Preferences();
        preferences.failLoad = true;
        preferences.failSave = true;
        models = new ModelFactory(config);
        expert = new KnowledgeExpert(models, embeddings.gateway(), config,
                temporaryDirectory.resolve("global-prefs"),
                temporaryDirectory.resolve("workspace-prefs"), preferences);

        assertTrue(expert.importText("alpha", "doc", KnowledgeExpert.Scope.WORKSPACE)
                .contains("已导入"));
        expert.setDocEnabled("doc", false);
        assertFalse(expert.isDocEnabled("doc"));
        expert.setAllEnabled(true, null);
        assertTrue(expert.isDocEnabled("doc"));
    }

    private AgentConfig config(boolean ragEnabled) {
        context = ApplicationContexts.createRoot(
                new DataRoot(temporaryDirectory.resolve("data-" + System.nanoTime())));
        AgentConfig config = context.getBean(AgentConfig.class);
        config.setRagEnabled(ragEnabled);
        config.setRagEmbeddingDimensions(4);
        config.setRagChunkSize(200);
        config.setRagChunkOverlap(20);
        return config;
    }

    private static final class Preferences implements KnowledgeDocumentPreferencePort {
        private final Set<String> excluded = new HashSet<>();
        private boolean failLoad;
        private boolean failSave;

        @Override
        public Set<String> loadExcluded() {
            if (failLoad) throw new IllegalStateException("load failed");
            return Set.copyOf(excluded);
        }

        @Override
        public void replaceExcluded(Set<String> documentNames) {
            if (failSave) throw new IllegalStateException("save failed");
            excluded.clear();
            excluded.addAll(documentNames);
        }
    }
}
