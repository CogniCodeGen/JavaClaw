package com.javaclaw.sdk;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.sdk.model.ArtifactItemContent;
import com.javaclaw.sdk.model.AutomationDefinitionInfo;
import com.javaclaw.sdk.model.AutomationInfo;
import com.javaclaw.sdk.model.CheckpointItemContent;
import com.javaclaw.sdk.model.ItemInfo;
import com.javaclaw.sdk.model.JsonDocument;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenSpecBundleCodecTest {
    @TempDir
    Path temporary;

    @Test
    void explicitExchangePreservesMarkdownButDoesNotPromoteCheckboxesToExecutionState() throws Exception {
        var documents = Map.of(
                "proposal.md",
                "# 保留 UI\n",
                "specs/chat/spec.md",
                "### Requirement: 原配色\n",
                "tasks.md",
                "- [x] 示例旧勾选\n- [ ] 尚待验收\n");
        Path archive = temporary.resolve("change.zip");
        write(archive, documents, "openspec/changes/keep-ui/");
        var draft = OpenSpecBundleCodec.read(archive);
        assertEquals(documents, draft.documents());
        assertEquals(64, draft.sourceSha256().length());
        assertTrue(draft.warnings().stream().anyMatch(value -> value.contains("不视为验收")));
        var definition =
                new AutomationDefinitionInfo(25, 100, 200_000, 3600, 3, "", List.of(), List.of(), draft.documents());
        assertEquals(definition, AutomationDocuments.read(AutomationDocuments.write(definition)));
        Path output = temporary.resolve("export.zip");
        OpenSpecBundleCodec.write(definition, List.of(), output, false);
        assertEquals(documents, OpenSpecBundleCodec.read(output).documents());
        assertThrows(
                java.nio.file.FileAlreadyExistsException.class,
                () -> OpenSpecBundleCodec.write(definition, List.of(), output, false));
    }

    @Test
    void rejectsTraversalMultipleChangesUnknownExecutableAndZipBombsWithoutExtracting() throws Exception {
        for (Map<String, String> entries : List.of(
                Map.of("../outside.md", "unsafe"),
                Map.of("proposal.md", "a", "openspec/changes/other/tasks.md", "b"),
                Map.of("proposal.md", "a", "run.sh", "execute me"),
                Map.of("proposal.md", "x".repeat(100_000)))) {
            Path archive = temporary.resolve("bad-" + Math.abs(entries.hashCode()) + ".zip");
            write(archive, entries, "");
            assertThrows(IllegalArgumentException.class, () -> OpenSpecBundleCodec.read(archive));
        }
        assertFalse(Files.exists(temporary.getParent().resolve("outside.md")));
    }

    @Test
    void exportCannotReplaceEditedSpecificationsWithArtifactsFromAnEarlierDefinitionOrExecution() throws Exception {
        var definition = new AutomationInfo(
                "sdd",
                "SDD",
                "设计",
                "workspace",
                "profile",
                "保留 UI",
                JsonDocument.EMPTY_OBJECT,
                "READY",
                "thread",
                null,
                2,
                Instant.EPOCH,
                Instant.EPOCH);
        String hash = java.util.HexFormat.of()
                .formatHex(java.security.MessageDigest.getInstance("SHA-256")
                        .digest("SDD\n保留 UI\n{}".getBytes(StandardCharsets.UTF_8)));
        var oldArtifact = item(new ArtifactItemContent(
                "old:specification", "sdd-specification", "旧规格", 1, "旧正文", List.of(), JsonDocument.EMPTY_OBJECT));
        var currentArtifact = item(new ArtifactItemContent(
                "current:specification", "sdd-specification", "新规格", 1, "新正文", List.of(), JsonDocument.EMPTY_OBJECT));
        var checkpoint = item(new CheckpointItemContent(
                "current", hash, "design", "WAITING", 1, 1, 10, 100, "待审", JsonDocument.EMPTY_OBJECT));
        assertEquals(
                List.of(currentArtifact),
                OpenSpecBundleCodec.currentExecutionArtifacts(
                        definition, List.of(oldArtifact, checkpoint, currentArtifact)));
        var obsolete = item(new CheckpointItemContent(
                "current", "0".repeat(64), "end", "COMPLETED", 1, 1, 10, 100, "历史", JsonDocument.EMPTY_OBJECT));
        assertTrue(OpenSpecBundleCodec.currentExecutionArtifacts(
                        definition, List.of(oldArtifact, checkpoint, currentArtifact, obsolete))
                .isEmpty());
    }

    private static ItemInfo item(com.javaclaw.sdk.model.ItemContent content) {
        return new ItemInfo("item", "thread", "turn", 1, "COMPLETED", content, Instant.EPOCH, Instant.EPOCH);
    }

    private void write(Path target, Map<String, String> entries, String prefix) throws Exception {
        try (var zip = new ZipOutputStream(Files.newOutputStream(target), StandardCharsets.UTF_8)) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(prefix + entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }
}
