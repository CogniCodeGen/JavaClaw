package com.javaclaw.builtin.extensions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AttachmentRef;
import com.javaclaw.api.ItemId;
import com.javaclaw.api.ThreadId;
import com.javaclaw.builtin.contracts.MemoryContracts;
import com.javaclaw.builtin.contracts.SkillContracts;
import com.javaclaw.extension.spi.ExpectedRevisionBinding;
import com.javaclaw.extension.spi.ExtensionBundle;
import com.javaclaw.extension.spi.ExtensionContribution;
import com.javaclaw.extension.spi.ExtensionContributions;
import com.javaclaw.extension.spi.ViewAction;
import com.javaclaw.extension.spi.ViewBinding;
import com.javaclaw.extension.spi.ViewQueryRequest;
import com.javaclaw.extension.spi.ViewQueryResult;
import com.javaclaw.extension.spi.ViewSchema;
import com.javaclaw.extension.spi.ViewStructuredListField;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagementActionViewTest {
    @Test
    void everyManagementActionResolvesToARealContributionOperation() throws Exception {
        for (ExtensionBundle bundle : BuiltinExtensions.create()) {
            BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
            var started = support.start(bundle);
            Set<String> operations = operations(started.contributions());
            List<ViewAction> actions = actions(started.contributions());

            assertFalse(actions.isEmpty(), bundle.descriptor().id() + " management view is read-only");
            assertTrue(
                    actions.stream().allMatch(action -> operations.contains(action.command())),
                    bundle.descriptor().id() + " exposes an action without a handler");
            bundle.close();
        }
    }

    @Test
    void memoryManagementActionsPreserveHiddenFieldsAndRestoreTombstone() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        var started = support.start(new MemoryExtension());
        MemoryContracts.CreateRequest create = new MemoryContracts.CreateRequest(
                "managed",
                MemoryContracts.MemoryKind.FACT,
                "workspace",
                "原始正文",
                Set.of("保留标签"),
                false,
                Optional.empty());
        started.command(support.request("create", create, Optional.of("create"), 0));

        MemoryContracts.Memory pinned = support.decode(
                started.command(support.request("pin/set", new MemoryContracts.Key("managed"), Optional.of("pin"), 1)),
                MemoryContracts.Memory.class);
        MemoryContracts.Memory corrected = support.decode(
                started.command(support.request(
                        "update/content",
                        new MemoryContracts.ContentUpdateRequest(
                                "managed", MemoryContracts.MemoryKind.FACT, "project", "纠错正文"),
                        Optional.of("correct"),
                        2)),
                MemoryContracts.Memory.class);
        assertThrows(
                IllegalArgumentException.class,
                () -> started.command(support.request(
                        "update/content",
                        new MemoryContracts.ContentUpdateRequest(
                                "managed", MemoryContracts.MemoryKind.FACT, "project", "过期草稿"),
                        Optional.of("stale-correct"),
                        2)));
        started.command(support.request("tombstone", new MemoryContracts.Key("managed"), Optional.of("delete"), 3));
        ViewQueryResult tombstones = support.decode(
                started.query(support.request(
                        "view.tombstones",
                        new ViewQueryRequest("tombstones", Map.of(), "", 10, Optional.empty()),
                        Optional.empty(),
                        0)),
                ViewQueryResult.class);
        MemoryContracts.Memory restored = support.decode(
                started.command(support.request(
                        "restore", new MemoryContracts.RestoreRequest("managed", 3), Optional.of("restore"), 4)),
                MemoryContracts.Memory.class);

        assertTrue(pinned.pinned());
        assertEquals(Set.of("保留标签"), corrected.tags());
        assertEquals("纠错正文", corrected.content());
        assertEquals(1, tombstones.rows().size());
        assertEquals(5, restored.revision());
    }

    @Test
    void memoryCorrectionBindsSelectedIdentityAndRevisionOutsideUserFields() {
        ViewSchema view = MemoryExtensionPresentation.managementView();
        ViewSchema.Form correction = form(view, "memory-correction");

        assertFalse(correction.fields().stream().anyMatch(field -> field.name().equals("id")));
        assertEquals(
                new ExpectedRevisionBinding.SourceRevision("memoryEditor"),
                correction.submit().expectedRevision());
        assertEquals(1, correction.submit().commandBindings().size());
        assertEquals("id", correction.submit().commandBindings().getFirst().argumentName());
        assertEquals(
                new ViewBinding("memoryEditor", "id"),
                correction.submit().commandBindings().getFirst().binding());
        assertEquals(
                List.of("id", "revision"),
                view.dataSources().stream()
                        .filter(source -> source.id().equals("memoryEditor"))
                        .findFirst()
                        .orElseThrow()
                        .argumentBindings()
                        .stream()
                        .map(com.javaclaw.extension.spi.ViewArgumentBinding::argument)
                        .toList());
    }

    @Test
    void memoryEditorRejectsStaleSelectedRevision() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        var started = support.start(new MemoryExtension());
        started.command(support.request(
                "create",
                new MemoryContracts.CreateRequest(
                        "selected",
                        MemoryContracts.MemoryKind.FACT,
                        "workspace",
                        "第一版",
                        Set.of(),
                        false,
                        Optional.empty()),
                Optional.of("selected-create"),
                0));
        started.command(support.request(
                "update/content",
                new MemoryContracts.ContentUpdateRequest(
                        "selected", MemoryContracts.MemoryKind.FACT, "workspace", "第二版"),
                Optional.of("selected-update"),
                1));

        assertThrows(
                IllegalArgumentException.class,
                () -> started.query(support.request(
                        "view.memory",
                        new ViewQueryRequest(
                                "memoryEditor", Map.of("id", "selected", "revision", "1"), "", 1, Optional.empty()),
                        Optional.empty(),
                        0)));
    }

    @Test
    void memoryManualCreateUsesStructuredTagsAndServerBoundWorkspaceSource() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        var started = support.start(new MemoryExtension());
        ThreadId threadId = ThreadId.random();
        ItemId itemId = ItemId.random();
        MemoryContracts.ManagementCreateRequest input = new MemoryContracts.ManagementCreateRequest(
                "manual",
                MemoryContracts.MemoryKind.FACT,
                "workspace",
                "可逐字核验的正文",
                List.of(new MemoryContracts.ManagementTag("tag-1", "v5")),
                true,
                List.of(new MemoryContracts.ManagementSource(
                        "source-1", threadId.toString(), itemId.toString(), "可逐字核验的正文")));

        MemoryContracts.Memory created = support.decode(
                started.command(support.request("management/create", input, Optional.of("manual-create"), 0)),
                MemoryContracts.Memory.class);

        assertEquals(Set.of("v5"), created.tags());
        assertTrue(created.pinned());
        assertEquals(support.workspaceId, created.source().orElseThrow().workspaceId());
        assertEquals(threadId, created.source().orElseThrow().threadId());
        assertEquals(itemId, created.source().orElseThrow().itemId());

        support.evidenceAvailable = false;
        MemoryContracts.ManagementCreateRequest unverifiable = new MemoryContracts.ManagementCreateRequest(
                "unverifiable",
                MemoryContracts.MemoryKind.FACT,
                "workspace",
                "没有来源",
                List.of(),
                false,
                List.of(new MemoryContracts.ManagementSource(
                        "source-2",
                        ThreadId.random().toString(),
                        ItemId.random().toString(),
                        "没有来源")));
        assertThrows(
                IllegalArgumentException.class,
                () -> started.command(
                        support.request("management/create", unverifiable, Optional.of("invalid-source"), 0)));
    }

    @Test
    void memoryManualCreateSchemaUsesStructuredTagsAndOneStructuredSource() {
        ViewSchema.Form create = form(MemoryExtensionPresentation.managementView(), "memory-create");
        ViewStructuredListField tags = assertInstanceOf(
                ViewStructuredListField.class,
                create.fields().stream()
                        .filter(field -> field.name().equals("tags"))
                        .findFirst()
                        .orElseThrow());
        ViewStructuredListField sources = assertInstanceOf(
                ViewStructuredListField.class,
                create.fields().stream()
                        .filter(field -> field.name().equals("sources"))
                        .findFirst()
                        .orElseThrow());

        assertEquals("management/create", create.submit().command());
        assertEquals(32, tags.maxRows());
        assertEquals(1, sources.maxRows());
        assertEquals(
                Set.of("threadId", "itemId", "verbatim"),
                sources.itemFields().stream()
                        .map(com.javaclaw.extension.spi.ViewStructuredItemField::name)
                        .collect(java.util.stream.Collectors.toSet()));
        assertFalse(create.fields().stream().anyMatch(field -> field.name().equals("workspaceId")));
    }

    @Test
    void skillManagementActionsSavePublishEnableDeleteAndRestoreDraft() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        var started = support.start(new SkillExtension());
        SkillContracts.Draft draft = support.decode(
                started.command(support.request(
                        "draft/save-content",
                        new SkillContracts.SaveContentRequest("managed", "管理技能", "摘要", "安全指令"),
                        Optional.of("save"),
                        0)),
                SkillContracts.Draft.class);
        SkillContracts.Draft updated = support.decode(
                started.command(support.request(
                        "draft/save-content",
                        new SkillContracts.SaveContentRequest("managed", "管理技能", "更新摘要", "更新指令"),
                        Optional.of("update"),
                        draft.revision())),
                SkillContracts.Draft.class);
        assertThrows(
                IllegalArgumentException.class,
                () -> started.command(support.request(
                        "draft/save-content",
                        new SkillContracts.SaveContentRequest("managed", "管理技能", "过期摘要", "过期指令"),
                        Optional.of("stale-skill-edit"),
                        draft.revision())));
        SkillContracts.PublishedSkill published = support.decode(
                started.command(support.request(
                        "publish",
                        new SkillContracts.PublishRequest("managed", updated.revision()),
                        Optional.of("publish"),
                        0)),
                SkillContracts.PublishedSkill.class);
        SkillContracts.PublishedSkill enabled = support.decode(
                started.command(support.request(
                        "enable/set", new SkillContracts.Key("managed"), Optional.of("enable"), published.revision())),
                SkillContracts.PublishedSkill.class);
        started.command(support.request(
                "draft/tombstone", new SkillContracts.Key("managed"), Optional.of("delete"), updated.revision()));
        SkillContracts.Draft restored = support.decode(
                started.command(support.request(
                        "draft/restore",
                        new SkillContracts.DraftRestoreRequest("managed", draft.revision()),
                        Optional.of("restore"),
                        3)),
                SkillContracts.Draft.class);

        assertTrue(enabled.enabled());
        assertEquals(4, restored.revision());
        assertTrue(restored.resources().isEmpty());
    }

    @Test
    void skillEditBindsSelectedIdentityAndCreateKeepsNewIdentityField() {
        ViewSchema view = SkillExtensionPresentation.managementView();
        ViewSchema.Form create = form(view, "draft-create");
        ViewSchema.Form edit = form(view, "draft-edit");

        assertTrue(create.fields().stream().anyMatch(field -> field.name().equals("id")));
        assertTrue(create.submit().commandBindings().isEmpty());
        assertFalse(edit.fields().stream().anyMatch(field -> field.name().equals("id")));
        assertEquals(
                new ExpectedRevisionBinding.SourceRevision("draftEditor"),
                edit.submit().expectedRevision());
        assertEquals(1, edit.submit().commandBindings().size());
        assertEquals("id", edit.submit().commandBindings().getFirst().argumentName());
        assertEquals(
                new ViewBinding("draftEditor", "id"),
                edit.submit().commandBindings().getFirst().binding());
        assertEquals(
                List.of("id", "revision"),
                view.dataSources().stream()
                        .filter(source -> source.id().equals("draftEditor"))
                        .findFirst()
                        .orElseThrow()
                        .argumentBindings()
                        .stream()
                        .map(com.javaclaw.extension.spi.ViewArgumentBinding::argument)
                        .toList());
    }

    @Test
    void skillResourceTableRemovesOnlyFromSelectedDraftRevision() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        var started = support.start(new SkillExtension());
        SkillContracts.Resource resource =
                new SkillContracts.Resource("guide.md", "text/markdown", "a".repeat(64), false);
        AttachmentRef attachment = new AttachmentRef(resource.digest(), resource.mediaType(), resource.id(), 128);
        support.claimAttachment(support.workspaceId, attachment);
        SkillContracts.Draft empty = support.decode(
                started.command(support.request(
                        "draft/save-content",
                        new SkillContracts.SaveContentRequest("resource-skill", "资源技能", "带资源", "读取已审阅资源"),
                        Optional.of("resource-create"),
                        0)),
                SkillContracts.Draft.class);
        SkillContracts.Draft created = support.decode(
                started.command(support.request(
                        "draft/resource/add",
                        new SkillContracts.AddResourceRequest(empty.id(), resource.id(), attachment, false),
                        Optional.of("resource-add"),
                        empty.revision())),
                SkillContracts.Draft.class);
        ViewQueryResult resources = skillResources(support, started, created.id(), created.revision());
        ViewSchema.Table table = table(SkillExtensionPresentation.managementView(), "draft-resources");

        assertEquals(1, resources.rows().size());
        assertTrue(resources.rows().getFirst().json().contains("\"draftRevision\":2"));
        assertEquals(
                new ExpectedRevisionBinding.RowField("draftRevision"),
                table.actions().getFirst().expectedRevision());
        assertEquals(
                Map.of("id", "draftId", "resourceId", "id"),
                table.actions().getFirst().rowArguments());

        SkillContracts.Draft removed = support.decode(
                started.command(support.request(
                        "draft/resource/remove",
                        new SkillContracts.RemoveResourceRequest(created.id(), resource.id()),
                        Optional.of("resource-remove"),
                        created.revision())),
                SkillContracts.Draft.class);
        assertTrue(removed.resources().isEmpty());
        assertThrows(
                IllegalArgumentException.class,
                () -> started.command(support.request(
                        "draft/resource/remove",
                        new SkillContracts.RemoveResourceRequest(created.id(), resource.id()),
                        Optional.of("resource-remove-stale"),
                        created.revision())));
        assertThrows(
                IllegalArgumentException.class,
                () -> skillResources(support, started, removed.id(), created.revision()));
    }

    private static ViewQueryResult skillResources(
            BuiltinExtensionTestSupport support,
            BuiltinExtensionTestSupport.Started started,
            String draftId,
            long revision)
            throws Exception {
        ViewQueryRequest query = new ViewQueryRequest(
                "draftResources",
                Map.of("id", draftId, "revision", Long.toString(revision)),
                "",
                100,
                Optional.empty());
        return support.decode(
                started.query(support.request("view.resources", query, Optional.empty(), 0)), ViewQueryResult.class);
    }

    @Test
    void memoryManagementExposesSourceStatsAndRestorableImmutableHistory() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        var started = support.start(new MemoryExtension());
        started.command(support.request(
                "create",
                new MemoryContracts.CreateRequest(
                        "history",
                        MemoryContracts.MemoryKind.FACT,
                        "workspace",
                        "第一版",
                        Set.of(),
                        false,
                        Optional.empty()),
                Optional.of("history-create"),
                0));
        started.command(support.request(
                "update/content",
                new MemoryContracts.ContentUpdateRequest(
                        "history", MemoryContracts.MemoryKind.FACT, "workspace", "第二版"),
                Optional.of("history-update"),
                1));

        ViewQueryResult history = support.decode(
                started.query(support.request(
                        "view.history",
                        new ViewQueryRequest("history", Map.of("id", "history"), "", 10, Optional.empty()),
                        Optional.empty(),
                        0)),
                ViewQueryResult.class);
        ViewQueryResult stats = support.decode(
                started.query(support.request(
                        "view.stats",
                        new ViewQueryRequest("stats", Map.of(), "", 1, Optional.empty()),
                        Optional.empty(),
                        0)),
                ViewQueryResult.class);
        ViewSchema.Table historyTable = table(MemoryExtensionPresentation.managementView(), "history");

        assertEquals(2, history.rows().size());
        assertEquals(2, history.revision());
        assertTrue(history.rows().getFirst().json().contains("\"sourceRevision\":1"));
        assertTrue(history.rows().getFirst().json().contains("\"currentRevision\":2"));
        assertTrue(stats.rows().getFirst().json().contains("\"active\":1"));
        assertEquals(
                new ExpectedRevisionBinding.RowField("currentRevision"),
                historyTable.actions().getFirst().expectedRevision());
    }

    @Test
    void skillManagementExposesRestorableDraftHistory() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        var started = support.start(new SkillExtension());
        started.command(support.request(
                "draft/save-content",
                new SkillContracts.SaveContentRequest("history", "技能", "第一版摘要", "第一版指令"),
                Optional.of("skill-history-create"),
                0));
        started.command(support.request(
                "draft/save-content",
                new SkillContracts.SaveContentRequest("history", "技能", "第二版摘要", "第二版指令"),
                Optional.of("skill-history-update"),
                1));

        ViewQueryResult history = support.decode(
                started.query(support.request(
                        "view.history",
                        new ViewQueryRequest("history", Map.of("id", "history"), "", 10, Optional.empty()),
                        Optional.empty(),
                        0)),
                ViewQueryResult.class);
        ViewSchema.Table historyTable = table(SkillExtensionPresentation.managementView(), "history");

        assertEquals(2, history.rows().size());
        assertEquals(2, history.revision());
        assertTrue(history.rows().getFirst().json().contains("\"sourceRevision\":1"));
        assertEquals(
                new ExpectedRevisionBinding.RowField("currentRevision"),
                historyTable.actions().getFirst().expectedRevision());
    }

    private static ViewSchema.Table table(ViewSchema view, String id) {
        return view.nodes().stream()
                .filter(ViewSchema.Table.class::isInstance)
                .map(ViewSchema.Table.class::cast)
                .filter(table -> table.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private static ViewSchema.Form form(ViewSchema view, String id) {
        return view.nodes().stream()
                .filter(ViewSchema.Form.class::isInstance)
                .map(ViewSchema.Form.class::cast)
                .filter(form -> form.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private static Set<String> operations(List<ExtensionContribution> contributions) {
        return contributions.stream()
                .flatMap(contribution -> switch (contribution) {
                    case ExtensionContributions.Command command -> command.operations().stream();
                    case ExtensionContributions.Orchestrator orchestrator -> orchestrator.operations().stream();
                    default -> java.util.stream.Stream.empty();
                })
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static List<ViewAction> actions(List<ExtensionContribution> contributions) {
        List<ViewAction> result = new ArrayList<>();
        contributions.stream()
                .filter(ExtensionContributions.View.class::isInstance)
                .map(ExtensionContributions.View.class::cast)
                .map(ExtensionContributions.View::view)
                .flatMap(view -> view.nodes().stream())
                .forEach(node -> result.addAll(actions(node)));
        return List.copyOf(result);
    }

    private static List<ViewAction> actions(ViewSchema.Node node) {
        return switch (node) {
            case ViewSchema.Form form -> List.of(form.submit());
            case ViewSchema.ListView list -> list.actions();
            case ViewSchema.Table table -> table.actions();
            case ViewSchema.Card card -> card.actions();
            default -> List.of();
        };
    }
}
