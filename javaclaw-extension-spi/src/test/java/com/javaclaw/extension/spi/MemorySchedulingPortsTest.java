package com.javaclaw.extension.spi;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ItemId;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.WorkspaceId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemorySchedulingPortsTest {
    @Test
    void completionCursorAndEvidenceKeepOrderedAndBoundedMeaning() {
        var zero = new ConversationEvidencePort.Cursor(0, 0);
        assertTrue(zero.compareTo(new ConversationEvidencePort.Cursor(1, 0)) < 0);
        assertTrue(new ConversationEvidencePort.Cursor(1, 2).compareTo(new ConversationEvidencePort.Cursor(1, 1)) > 0);
        assertEquals(0, zero.compareTo(zero));
        assertThrows(IllegalArgumentException.class, () -> new ConversationEvidencePort.Cursor(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> new ConversationEvidencePort.Cursor(0, -1));
        var evidence = evidence("原文", "a".repeat(64), false);
        assertEquals(
                1,
                new ConversationEvidencePort.Page(List.of(evidence), evidence.cursor(), false)
                        .evidence()
                        .size());
        assertEquals("", evidence("", "a".repeat(64), true).text());
        assertThrows(IllegalArgumentException.class, () -> evidence("x", "bad", false));
        assertThrows(IllegalArgumentException.class, () -> evidence("x".repeat(48001), "a".repeat(64), false));
        assertThrows(IllegalArgumentException.class, () -> evidence("x", "a".repeat(64), true));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ConversationEvidencePort.Page(java.util.Collections.nCopies(201, evidence), zero, true));
        var unavailable = ConversationEvidencePort.unavailable();
        assertThrows(IllegalStateException.class, () -> unavailable.committedUpperBound(WorkspaceId.random()));
        assertThrows(
                IllegalStateException.class, () -> unavailable.scan(WorkspaceId.random(), zero, 0, Instant.EPOCH, 1));
    }

    @Test
    void bindingRevisionAndGenerationValidateEveryNegativeBoundary() {
        var owner = new ExtensionId("test.memory");
        var payload = new CanonicalPayload("{}");
        var change = new ScheduleDefinitionBindingPort.Change("learning", 1, 0, 0, false, payload, "key");
        assertEquals(owner, new ScheduleDefinitionBindingPort.Lookup(owner, "learning").owner());
        assertEquals(change, new ScheduleDefinitionBindingPort.Apply(owner, change).change());
        assertEquals(
                0,
                new ScheduleDefinitionBindingPort.Binding(
                                "learning", 0, 0, "", 0, ScheduleDefinitionBindingPort.State.UNBOUND)
                        .generation());
        for (int field = 0; field < 4; field++) {
            int selected = field;
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new ScheduleDefinitionBindingPort.Binding(
                            selected == 0 ? " " : "learning",
                            selected == 1 ? -1 : 0,
                            selected == 2 ? -1 : 0,
                            "",
                            selected == 3 ? -1 : 0,
                            ScheduleDefinitionBindingPort.State.UNBOUND));
        }
        for (int field = 0; field < 5; field++) {
            int selected = field;
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new ScheduleDefinitionBindingPort.Change(
                            selected == 0 ? " " : "learning",
                            selected == 1 ? 0 : 1,
                            selected == 2 ? -1 : 0,
                            selected == 3 ? -1 : 0,
                            false,
                            payload,
                            selected == 4 ? " " : "key"));
        }
        var unavailable = ScheduleDefinitionBindingPort.unavailable();
        assertThrows(IllegalStateException.class, () -> unavailable.read(owner, WorkspaceId.random(), "learning"));
        assertThrows(IllegalStateException.class, () -> unavailable.bind(owner, WorkspaceId.random(), change, null));
        ItemEvidencePort legacyEvidence = (workspace, thread, item, text) -> true;
        assertEquals(false, legacyEvidence.isUserText(WorkspaceId.random(), ThreadId.random(), ItemId.random()));
        TurnOrchestrationPort ordinary = (command, cancellation) -> null;
        assertThrows(UnsupportedOperationException.class, () -> ordinary.executeDerived(null, null));
    }

    private static ConversationEvidencePort.Evidence evidence(String text, String digest, boolean oversized) {
        return new ConversationEvidencePort.Evidence(
                new ConversationEvidencePort.Cursor(1, 1),
                ThreadId.random(),
                TurnId.random(),
                ItemId.random(),
                ConversationEvidencePort.SourceKind.USER_TEXT,
                text,
                digest,
                oversized);
    }
}
