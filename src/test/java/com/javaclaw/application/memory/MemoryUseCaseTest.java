package com.javaclaw.application.memory;

import com.javaclaw.application.error.ValidationException;
import com.javaclaw.application.memory.MemoryApplicationService.AddFactCommand;
import com.javaclaw.application.memory.MemoryApplicationService.EditFactCommand;
import com.javaclaw.application.memory.MemoryApplicationService.EmbeddingState;
import com.javaclaw.application.memory.MemoryApplicationService.PersonaDraft;
import com.javaclaw.application.memory.MemoryApplicationService.Snapshot;
import com.javaclaw.application.memory.MemoryApplicationService.Statistics;
import com.javaclaw.memory.graph.MemoryGraph;
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

    private static Snapshot snapshot(EmbeddingState embedding) {
        return new Snapshot(Statistics.empty(), List.of(), List.of(), List.of(), List.of(),
                PersonaDraft.empty(), List.of(), List.of(), embedding);
    }

    private static final class FakePort implements MemoryPort {
        private Snapshot snapshot = snapshot(new EmbeddingState("", 0));
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
