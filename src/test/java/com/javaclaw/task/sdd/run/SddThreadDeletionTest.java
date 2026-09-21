package com.javaclaw.task.sdd.run;

import com.javaclaw.task.sdd.SddTestDatabase;
import com.javaclaw.task.sdd.SddThreadGuard;
import com.javaclaw.task.sdd.spec.SpecPaths;
import com.javaclaw.task.sdd.spec.SpecStore;
import com.javaclaw.task.sdd.verify.VerifyCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

class SddThreadDeletionTest {
    @TempDir Path directory;

    @Test void deletedCoordinatorRejectsRetainedWritersAndOldTaskSnapshots() {
        var db = new SddTestDatabase(directory.resolve("db"));
        var task = task("one"); var sibling = task("two");
        var index = index(db);
        index.replaceAll(List.of(task, sibling));
        String owner = SddThreadGuard.coordinator("workspace", task.id, true);
        String slug = SpecPaths.makeSlug(task.id, task.title);
        var specs = new SpecStore(directory.toString(), db.jdbc(), "workspace", owner);
        assertTrue(specs.writeDesign(slug, "private design"));
        var cache = VerifyCache.load(directory.toString(), slug, db.jdbc(), db.json(), "workspace", owner);
        cache.recordPass("scenario", "verified"); cache.save();
        tombstone(db, owner, "DELETING");

        assertFalse(specs.writeDesign(slug, "late design"));
        assertFalse(specs.changeExists(slug));
        assertNull(specs.readChange(slug, task.id, task.title).design());
        assertTrue(specs.listChangeSlugs().isEmpty());
        assertNull(cache.reuse("scenario"));
        cache.recordPass("late", "must not return"); cache.save();
        index.replaceAll(List.of(task, sibling));
        assertEquals(List.of(sibling.id), index.loadAll().stream().map(value -> value.id).toList());
        assertEquals(0, count(db, "sdd_spec_docs"));
        assertEquals(0, count(db, "sdd_verify_cache"));
        assertEquals(1, count(db, "sdd_tasks"));
        assertNull(VerifyCache.load(directory.toString(), slug, db.jdbc(), db.json(), "workspace", owner)
                .reuse("scenario"));
    }

    @Test void coldWorkspaceLoadCleansDeletedTaskArtifactsBeforeDroppingItsMetadata() {
        var db = new SddTestDatabase(directory.resolve("cold-db"));
        var task = task("cold");
        index(db).replaceAll(List.of(task));
        String slug = SpecPaths.makeSlug(task.id, task.title);
        assertTrue(new SpecStore(directory.toString(), db.jdbc(), "workspace")
                .writeDesign(slug, "legacy design"));
        tombstone(db, SddThreadGuard.coordinator("workspace", task.id, false), "DELETED");
        assertTrue(index(db).loadAll().isEmpty());
        assertEquals(0, count(db, "sdd_spec_docs"));
        assertEquals(0, count(db, "sdd_tasks"));
        index(db).replaceAll(List.of(task));
        assertTrue(index(db).loadAll().isEmpty());
    }

    @Test void threadClientDeletesColdWorkspaceArtifactsBeforeReturningWithoutTouchingOtherTasks() {
        var db = new SddTestDatabase(directory.resolve("closed-workspace"));
        var task = task("cold-client"); var retained = task("retained");
        index(db).replaceAll(List.of(task, retained));
        String slug = SpecPaths.makeSlug(task.id, task.title);
        new SpecStore(directory.toString(), db.jdbc(), "workspace").writeDesign(slug, "private design");
        var cache = VerifyCache.load(directory.toString(), slug, db.jdbc(), db.json(), "workspace");
        cache.recordPass("scenario", "passed"); cache.save();
        var runs = new com.javaclaw.framework.store.JdbcRunStore(db.jdbc(), db.transactions(),
                db.json().mapper(), java.time.Clock.systemUTC());
        var lifecycle = new com.javaclaw.framework.core.ThreadLifecycleRegistry();
        lifecycle.register(new com.javaclaw.infrastructure.thread.SddThreadArtifactCleaner(
                db.jdbc(), db.transactions(), db.json()));
        var rollout = new com.javaclaw.framework.store.ThreadRolloutProjector(db.jdbc(), runs.threads(),
                db.json().mapper(), directory.resolve("rollouts"), new com.javaclaw.framework.core.ThreadProjectionRegistry());
        var threads = new com.javaclaw.framework.core.DefaultThreadClient(runs.threads(), runs,
                new com.javaclaw.support.CapturingLifecycleClient(), lifecycle, rollout);
        var scope = new com.javaclaw.framework.api.RunScope("workspace", "local-user",
                SddThreadGuard.coordinator("workspace", task.id, true));
        threads.delete(scope);
        assertEquals(0, count(db, "sdd_spec_docs"));
        assertEquals(0, count(db, "sdd_verify_cache"));
        assertEquals(List.of(retained.id), index(db).storedTasks().stream().map(value -> value.id).toList());
        assertThrows(java.util.NoSuchElementException.class, () -> threads.get(scope));
        threads.delete(scope);
    }

    @Test void pendingArtifactWriterObservesDeletionAfterThreadLockIsReleased() throws Exception {
        var db = new SddTestDatabase(directory.resolve("race-db"));
        String owner = SddThreadGuard.coordinator("workspace", "race", true);
        tombstone(db, owner, "ACTIVE");
        var specs = new SpecStore(directory.toString(), db.jdbc(), "workspace", owner);
        CompletableFuture<Boolean> writer;
        try (var connection = db.access().open()) {
            connection.setAutoCommit(false);
            try (var query = connection.prepareStatement("SELECT status FROM agent_threads WHERE thread_id=? FOR UPDATE")) {
                query.setString(1, owner);
                try (var rows = query.executeQuery()) { assertTrue(rows.next()); }
            }
            var started = new CountDownLatch(1);
            writer = CompletableFuture.supplyAsync(() -> {
                started.countDown(); return specs.writeDesign("race", "late private data");
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> writer.get(100, TimeUnit.MILLISECONDS));
            try (var update = connection.prepareStatement("UPDATE agent_threads SET status='DELETED' WHERE thread_id=?")) {
                update.setString(1, owner); update.executeUpdate();
            }
            connection.commit();
        }
        assertFalse(writer.get(3, TimeUnit.SECONDS));
        assertEquals(0, count(db, "sdd_spec_docs"));
    }

    private SddManagedTask task(String id) {
        return new SddManagedTask(id, id, "task", directory.toString(), "auto", 0, null, "now");
    }
    private static SddTaskStore index(SddTestDatabase db) {
        return new SddTaskStore("workspace", db.jdbc(), db.transactions(), db.json());
    }
    private static void tombstone(SddTestDatabase db, String thread, String status) {
        db.jdbc().update("""
                INSERT INTO agent_threads(workspace_id,user_id,thread_id,title,status,configuration_json,created_at,updated_at)
                VALUES('workspace','local-user',?,'task',?,'{}',0,0)
                """, thread, status);
    }
    private static int count(SddTestDatabase db, String table) {
        assertTrue(List.of("sdd_tasks", "sdd_spec_docs", "sdd_verify_cache").contains(table));
        return db.jdbc().queryForObject("SELECT COUNT(*) FROM " + table + " WHERE workspace_id='workspace'", Integer.class);
    }
}
