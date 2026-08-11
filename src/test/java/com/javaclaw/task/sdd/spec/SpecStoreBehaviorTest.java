package com.javaclaw.task.sdd.spec;

import com.javaclaw.task.sdd.SddTestDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpecStoreBehaviorTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void changeDocumentsRoundTripAndRemainWorkspaceIsolated() throws Exception {
        Path workDirectory = Files.createDirectories(temporaryDirectory.resolve("project"));
        SddTestDatabase database = new SddTestDatabase(temporaryDirectory.resolve("database"));
        SpecStore first = new SpecStore(workDirectory.toString(), database.jdbc(), "workspace-a");
        SpecStore otherWorkspace = new SpecStore(
                workDirectory.resolve(".").toString(), database.jdbc(), "workspace-b");

        Proposal proposal = new Proposal("why", "what", "outside");
        Capability capability = capability("search", "search works");
        List<TaskItem> tasks = List.of(
                new TaskItem(1, "implement", List.of("src/App.java"), "compiles", false),
                new TaskItem(2, "verify", null, null, true));

        assertTrue(first.available());
        assertTrue(first.writeProposal("feature", "Feature", proposal));
        assertTrue(first.writeDesign("feature", "# Design\n\nStable"));
        assertTrue(first.writeTasks("feature", tasks));
        assertTrue(first.writeCapabilitySpecs("feature", List.of(capability)));
        assertEquals(List.of("feature"), first.listChangeSlugs());
        assertTrue(first.changeExists("feature"));
        assertFalse(otherWorkspace.changeExists("feature"));
        assertTrue(otherWorkspace.listChangeSlugs().isEmpty());

        OpenSpecChange loaded = first.readChange("feature", "id-1", "Feature");
        assertEquals("why", loaded.proposal().why());
        assertEquals("# Design\n\nStable", loaded.design());
        assertEquals(2, loaded.tasks().size());
        assertEquals("search", loaded.capabilities().getFirst().name());
        assertEquals("id-1", loaded.id());

        OpenSpecChange partial = first.readChange("missing", "id-2", "Missing");
        assertNull(partial.proposal());
        assertNull(partial.design());
        assertTrue(partial.tasks().isEmpty());
        assertTrue(partial.capabilities().isEmpty());
    }

    @Test
    void taskUpdatesSupportCheckingAppendingSplittingAndArchiving() throws Exception {
        Path workDirectory = Files.createDirectories(temporaryDirectory.resolve("tasks-project"));
        SddTestDatabase database = new SddTestDatabase(temporaryDirectory.resolve("tasks-db"));
        SpecStore store = new SpecStore(workDirectory.toString(), database.jdbc(), "workspace");

        assertFalse(store.checkTask("missing", 1));
        assertFalse(store.appendTasks("missing", null));
        assertFalse(store.appendTasks("missing", List.of()));
        assertFalse(store.splitTask("missing", 1, null));
        assertFalse(store.splitTask("missing", 1, List.of()));

        assertTrue(store.writeTasks("change", List.of(
                new TaskItem(1, "first", List.of(), "one", false),
                new TaskItem(2, "second", List.of(), "two", true))));
        assertFalse(store.checkTask("change", 9));
        assertFalse(store.checkTask("change", 2));
        assertTrue(store.checkTask("change", 1));
        assertTrue(store.readChange("change", null, null).tasks().getFirst().done());

        assertTrue(store.appendTasks("change", List.of("third", "fourth")));
        List<TaskItem> appended = store.readChange("change", null, null).tasks();
        assertEquals(List.of(1, 2, 3, 4), appended.stream().map(TaskItem::index).toList());
        assertFalse(store.splitTask("change", 99, List.of("never")));
        assertTrue(store.splitTask("change", 3, List.of("third-a", "third-b")));
        List<TaskItem> split = store.readChange("change", null, null).tasks();
        assertEquals(List.of("first", "second", "third-a", "third-b", "fourth"),
                split.stream().map(TaskItem::action).toList());
        assertEquals(List.of(1, 2, 3, 4, 5), split.stream().map(TaskItem::index).toList());

        assertTrue(store.writeProposal("change", "Change", new Proposal("w", "x", null)));
        assertTrue(store.writeCapabilitySpecs("change", List.of(capability("core", "works"))));
        assertTrue(store.archive("change", null));
        String proposal = database.jdbc().queryForObject("""
                SELECT doc_text FROM sdd_spec_docs
                WHERE workspace_id = 'workspace' AND slug = 'change' AND doc_path = 'proposal.md'
                """, String.class);
        assertTrue(proposal.contains("已完成"));
        assertEquals(1, database.jdbc().queryForObject("""
                SELECT COUNT(*) FROM sdd_spec_docs
                WHERE workspace_id = 'workspace' AND slug = 'change'
                  AND doc_path = 'archived/specs/core/spec.md'
                """, Integer.class));
    }

    @Test
    void invalidInputsAndMissingSchemaFailWithoutPublishingPartialState() {
        JdbcTemplate missingSchema = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:spec-missing-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1"));
        SpecStore broken = new SpecStore(temporaryDirectory.toString(), missingSchema, "workspace");

        assertFalse(broken.writeDesign("change", null));
        assertFalse(broken.writeDesign("change", " "));
        assertFalse(broken.writeCapabilitySpecs("change", null));
        assertFalse(broken.writeProposal("change", "Title", new Proposal("a", "b", "c")));
        assertFalse(broken.changeExists("change"));
        assertTrue(broken.listChangeSlugs().isEmpty());
        OpenSpecChange empty = broken.readChange("change", null, null);
        assertNull(empty.proposal());
        assertTrue(empty.tasks().isEmpty());
        assertTrue(empty.capabilities().isEmpty());

        SpecStore unavailable = new SpecStore(null, missingSchema, "workspace");
        assertFalse(unavailable.available());
        assertFalse(unavailable.writeDesign("change", "design"));
        assertFalse(unavailable.writeTasks("change", List.of()));
        assertFalse(unavailable.writeCapabilitySpecs(
                "change", List.of(capability("none", "none"))));
        assertFalse(unavailable.changeExists("change"));
        assertTrue(unavailable.listChangeSlugs().isEmpty());
        assertFalse(unavailable.checkTask("change", 1));
        assertFalse(unavailable.appendTasks("change", List.of("task")));
        assertTrue(unavailable.readChange("change", null, null).capabilities().isEmpty());
    }

    private static Capability capability(String name, String title) {
        Scenario scenario = new Scenario("scenario", "given", "when", "then",
                new Criterion(Criterion.FREEFORM, "accepted"));
        return new Capability(name, List.of(new Requirement(title, List.of(scenario))));
    }
}
