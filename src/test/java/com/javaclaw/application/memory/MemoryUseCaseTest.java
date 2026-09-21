package com.javaclaw.application.memory;

import com.javaclaw.application.error.ValidationException;
import com.javaclaw.application.memory.MemoryApplicationService.AddFactCommand;
import com.javaclaw.application.memory.MemoryApplicationService.EditFactCommand;
import com.javaclaw.application.memory.MemoryApplicationService.EmbeddingState;
import com.javaclaw.application.memory.MemoryApplicationService.PersonaDraft;
import com.javaclaw.application.memory.MemoryApplicationService.Snapshot;
import com.javaclaw.application.memory.MemoryApplicationService.Statistics;
import com.javaclaw.memory.graph.MemoryGraph;
import com.javaclaw.memory.MemoryGraphScope;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryUseCaseTest {

    private final FakePort port = new FakePort();
    private final MemoryUseCase useCase = new MemoryUseCase(port);

    @Test
    void validatesAndNormalizesFactMutations() {
        var added = useCase.addFact(new AddFactCommand("  ", "  使用中文  "));

        assertEquals("其它", port.addedSection);
        assertEquals("使用中文", port.addedText);
        assertEquals("事实已新增", added.message());

        useCase.editFact(new EditFactCommand(" fact-1 ", " 新内容 "));
        assertEquals("fact-1", port.editedId);
        assertEquals("新内容", port.editedText);
        assertThrows(ValidationException.class,
                () -> useCase.addFact(new AddFactCommand("偏好", "  ")));
        assertThrows(ValidationException.class,
                () -> useCase.editFact(new EditFactCommand("", "内容")));
    }

    @Test
    void deduplicatesBatchDeletionWithoutChangingOrder() {
        port.deleteCount = 2;

        var result = useCase.deleteFacts(List.of(" first ", "second", "first"));

        assertEquals(List.of("first", "second"), port.deletedIds);
        assertEquals(2, result.affected());
        assertThrows(ValidationException.class, () -> useCase.deleteFacts(List.of()));
        assertThrows(ValidationException.class, () -> useCase.deleteFacts(List.of(" ")));
    }

    @Test
    void refillsOnlyAfterHealthyEmbeddingProbe() {
        port.snapshot = snapshot(new EmbeddingState("", 3));
        port.promoteCount = 3;

        var healthy = useCase.probeAndRefill();

        assertEquals(1, port.probes);
        assertEquals(1, port.promotions);
        assertEquals(3, healthy.affected());

        port.snapshot = snapshot(new EmbeddingState("服务不可用", 4));
        port.probeError = "服务不可用";
        var degraded = useCase.probeAndRefill();
        assertEquals(1, port.promotions, "探测失败不得尝试回填");
        assertEquals(0, degraded.affected());

        port.promoteCount = 0;
        assertEquals("没有可回填的记忆", useCase.refillPending().message());
    }

    @Test
    void validatesPersonaForSaveButAllowsEmptyPreviewAndExport() {
        PersonaDraft empty = PersonaDraft.empty();
        assertEquals("persona:", useCase.personaMarkdown(empty));
        assertThrows(ValidationException.class, () -> useCase.savePersona(empty));

        PersonaDraft persona = new PersonaDraft(
                " 助手 ", "温和", List.of(" 简洁 ", ""), List.of("敷衍"));
        var saved = useCase.savePersona(persona);
        assertEquals(persona, port.savedPersona);
        assertEquals("人格已保存（下一轮对话生效）", saved.message());

        Path target = Path.of("persona.md");
        useCase.exportPersona(target, persona);
        assertEquals(target, port.exportTarget);
        assertEquals("persona:助手", port.exportMarkdown);
    }

    @Test
    void delegatesDocumentCorrectionAndFactStateActions() {
        port.reindexCount = 5;
        port.deleteDocumentCount = 2;

        assertEquals(5, useCase.reindexDocument(" notes.md ").affected());
        assertEquals("notes.md", port.reindexedDocument);
        assertEquals(2, useCase.deleteDocument(" notes.md ").affected());
        assertEquals("notes.md", port.deletedDocument);
        useCase.toggleFactPin(" fact-1 ");
        useCase.restoreFact(" fact-2 ");
        useCase.revokeCorrection(" correction-1 ");
        useCase.deleteCorrection(" correction-2 ");
        assertEquals(List.of("fact-1", "fact-2", "correction-1", "correction-2"),
                port.stateActions);
        assertEquals(MemoryGraph.empty(), useCase.graph());

        assertThrows(ValidationException.class, () -> useCase.reindexDocument(" "));
        assertThrows(ValidationException.class, () -> useCase.deleteDocument(null));
        assertThrows(ValidationException.class, () -> useCase.toggleFactPin(""));
    }

    @Test
    void snapshotRecordsDefensivelyCopyCollectionsAndDescribeEmbeddingState() {
        List<String> preferences = new ArrayList<>(List.of(" 代码 "));
        PersonaDraft draft = new PersonaDraft(null, null, preferences, null);
        preferences.add("后来加入");

        assertEquals("", draft.identity());
        assertEquals("简洁直接", draft.tone());
        assertEquals(List.of("代码"), draft.preferences());
        assertFalse(new EmbeddingState("", 0).degraded());
        assertTrue(new EmbeddingState("", 1).canRefill());
        assertTrue(new EmbeddingState("错误", 0).degraded());
        assertFalse(new EmbeddingState("错误", 1).canRefill());
    }

    @Test
    void scopedViewsKeepBothTheirReadsAndEditsBoundToTheSelectedGraph() {
        var habits = new MemoryGraphScope("workspace", "user", "", MemoryGraphScope.Kind.WORKSPACE_HABITS);
        var first = new MemoryGraphScope("workspace", "user", "first", MemoryGraphScope.Kind.THREAD);
        var second = new MemoryGraphScope("workspace", "user", "second", MemoryGraphScope.Kind.THREAD);
        var firstPort = new FakePort();
        var secondPort = new FakePort();
        port.selectedScope = habits;
        port.views.put(habits, port);
        port.views.put(first, firstPort);
        port.views.put(second, secondPort);
        firstPort.selectedScope = first;
        secondPort.selectedScope = second;
        firstPort.snapshot = snapshot(new EmbeddingState("first graph unavailable", 2));
        secondPort.snapshot = snapshot(new EmbeddingState("", 0));
        MemoryApplicationService firstView = useCase.inScope(first);
        MemoryApplicationService secondView = useCase.inScope(second);

        firstView.addFact(new AddFactCommand("项目约定", "只属于第一会话"));
        secondView.addFact(new AddFactCommand(null, "只属于第二会话"));

        assertEquals(first, firstView.scope());
        assertEquals(second, secondView.scope());
        assertEquals(habits, useCase.scope(), "切换视图不能修改共享目录的 scope");
        assertEquals(List.of(habits, first, second), useCase.scopes());
        assertEquals("项目约定", firstPort.addedSection);
        assertEquals("只属于第一会话", firstPort.addedText);
        assertEquals("只属于第二会话", secondPort.addedText);
        assertEquals(null, port.addedText, "会话编辑不能意外写入个人习惯");
        assertEquals(2, firstView.snapshot().embedding().pendingCount());
        assertEquals(0, secondView.snapshot().embedding().pendingCount());
        var foreign = new MemoryGraphScope("other-workspace", "user", "first", MemoryGraphScope.Kind.THREAD);
        assertThrows(IllegalArgumentException.class, () -> useCase.inScope(foreign));
        assertEquals(first, firstView.scope());
    }

    @Test
    void healthyEmptyGraphSkipsRefillAndExplicitRefillReportsMovedItems() {
        port.probeError = null;
        port.snapshot = snapshot(new EmbeddingState(null, 0));
        assertEquals(0, useCase.probeAndRefill().affected());
        assertEquals(0, port.promotions, "没有待索引内容时健康探测不能触发重建");
        port.promoteCount = 2;
        assertEquals("已回填 2 条记忆", useCase.refillPending().message());
        assertEquals(1, port.promotions);
    }

    @Test
    void rejectsAbsentDeleteSelectionAndPersonaButAllowsPreferenceOnlyOrTabooOnlyPersona() {
        assertThrows(ValidationException.class, () -> useCase.deleteFacts(null));
        assertThrows(ValidationException.class, () -> useCase.savePersona(null));
        PersonaDraft preferences = new PersonaDraft("", "", List.of("简短回答"), List.of());
        useCase.savePersona(preferences);
        assertEquals(preferences, port.savedPersona);
        PersonaDraft taboos = new PersonaDraft("", "", List.of(), List.of("重复敏感内容"));
        useCase.savePersona(taboos);
        assertEquals(taboos, port.savedPersona);
    }

    @Test
    void emptyLegacySnapshotAndOptionalNodeFieldsRemainSafeForScopeSelection() {
        Snapshot legacy = new Snapshot(null, null, null, null, null, null, null, null, null);
        assertEquals(Statistics.empty(), legacy.statistics());
        assertTrue(legacy.facts().isEmpty());
        assertTrue(legacy.episodes().isEmpty());
        assertTrue(legacy.entities().isEmpty());
        assertTrue(legacy.documents().isEmpty());
        assertTrue(legacy.corrections().isEmpty());
        assertTrue(legacy.changes().isEmpty());
        assertEquals(PersonaDraft.empty(), legacy.persona());
        assertFalse(legacy.embedding().canRefill());
        var fact = new MemoryApplicationService.FactItem(null, null, null, 0, 0, 0,
                false, false, false, false, false, true, null, null);
        assertEquals("其它", fact.section());
        assertEquals("", fact.sourceEpisodeId());
        assertTrue(fact.entityNames().isEmpty());
        assertEquals("其它", new MemoryApplicationService.EntityItem(null, null, " ", 0).type());
        assertEquals("—", new MemoryApplicationService.KnowledgeDocument(null, 0, 0, null).importedAt());
        assertFalse(new MemoryApplicationService.EpisodeItem(null, null, null, null, 0, true, 0).hasToolTrace());
        assertTrue(new MemoryApplicationService.EpisodeItem("id", "input", "output", "[]", 0, false, 0).hasToolTrace());
    }

    private static Snapshot snapshot(EmbeddingState embedding) {
        return new Snapshot(Statistics.empty(), List.of(), List.of(), List.of(), List.of(),
                PersonaDraft.empty(), List.of(), List.of(), embedding);
    }

    private static final class FakePort implements MemoryPort {
        private Snapshot snapshot = snapshot(new EmbeddingState("", 0));
        private MemoryGraphScope selectedScope;
        private final java.util.Map<MemoryGraphScope, FakePort> views = new java.util.LinkedHashMap<>();
        private String probeError = "";
        private int promoteCount;
        private int probes;
        private int promotions;
        private String addedSection;
        private String addedText;
        private String editedId;
        private String editedText;
        private int deleteCount;
        private List<String> deletedIds = List.of();
        private int reindexCount;
        private String reindexedDocument;
        private int deleteDocumentCount;
        private String deletedDocument;
        private PersonaDraft savedPersona;
        private Path exportTarget;
        private String exportMarkdown;
        private final List<String> stateActions = new ArrayList<>();

        @Override public List<MemoryGraphScope> scopes() { return List.copyOf(views.keySet()); }
        @Override public MemoryGraphScope scope() { return selectedScope; }
        @Override public MemoryPort inScope(MemoryGraphScope scope) {
            FakePort selected = views.get(scope);
            if (selected == null) throw new IllegalArgumentException("图谱不属于当前工作区");
            return selected;
        }
        @Override public Snapshot load() { return snapshot; }
        @Override public String probeEmbedding() { probes++; return probeError; }
        @Override public int promoteAllPending() { promotions++; return promoteCount; }
        @Override public MemoryGraph graph() { return MemoryGraph.empty(); }
        @Override public void addFact(String section, String text) {
            addedSection = section;
            addedText = text;
        }
        @Override public void editFact(String id, String text) {
            editedId = id;
            editedText = text;
        }
        @Override public void toggleFactPin(String id) { stateActions.add(id); }
        @Override public void restoreFact(String id) { stateActions.add(id); }
        @Override public int deleteFacts(List<String> ids) {
            deletedIds = List.copyOf(ids);
            return deleteCount;
        }
        @Override public int reindexDocument(String name) {
            reindexedDocument = name;
            return reindexCount;
        }
        @Override public int deleteDocument(String name) {
            deletedDocument = name;
            return deleteDocumentCount;
        }
        @Override public void savePersona(PersonaDraft persona) { savedPersona = persona; }
        @Override public String personaMarkdown(PersonaDraft persona) {
            return "persona:" + persona.identity();
        }
        @Override public void exportPersona(Path target, String markdown) {
            exportTarget = target;
            exportMarkdown = markdown;
        }
        @Override public void revokeCorrection(String id) { stateActions.add(id); }
        @Override public void deleteCorrection(String id) { stateActions.add(id); }
    }
}
