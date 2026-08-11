package com.javaclaw.task.sdd.run;

import com.javaclaw.config.DatabaseAccess;
import com.javaclaw.task.sdd.SddTestDatabase;
import com.javaclaw.task.sdd.spec.Proposal;
import com.javaclaw.task.sdd.spec.SpecPaths;
import com.javaclaw.task.sdd.spec.SpecStore;
import com.javaclaw.task.sdd.verify.VerifyCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SddTaskManagerPersistenceTest {

    @TempDir
    Path temp;

    @Test
    void 删除任务时从注入数据库清理规格和验收缓存() throws Exception {
        Path workDir = temp.resolve("workspace");
        Files.createDirectories(workDir);
        SddTestDatabase database = new SddTestDatabase(temp.resolve("database"));
        String workspaceId = "delete-workspace";
        SddManagedTask task = new SddManagedTask(
                "delete-id", "删除隔离", "验证删除目标数据库", workDir.toString(),
                "system", 0, null, "now");
        String slug = SpecPaths.makeSlug(task.id, task.title);

        SpecStore store = new SpecStore(workDir.toString(), database.jdbc(), workspaceId);
        store.writeProposal(slug, task.title, new Proposal("why", "what", ""));
        VerifyCache cache = VerifyCache.load(
                workDir.toString(), slug, database.jdbc(), database.json(), workspaceId);
        cache.syncFingerprint("test-fingerprint");
        cache.recordPass("scenario", "passed");
        cache.save();

        assertEquals(1, countRows(database.access(), "sdd_spec_docs", workspaceId, workDir, slug));
        assertEquals(1, countRows(database.access(), "sdd_verify_cache", workspaceId, workDir, slug));

        new SddTaskStore(workspaceId, database.jdbc(), database.transactions(), database.json())
                .deleteArtifacts(task);

        assertEquals(0, countRows(database.access(), "sdd_spec_docs", workspaceId, workDir, slug));
        assertEquals(0, countRows(database.access(), "sdd_verify_cache", workspaceId, workDir, slug));
    }

    @Test
    void 索引替换按工作区隔离且失败时完整回滚() {
        SddTestDatabase database = new SddTestDatabase(temp.resolve("store-database"));
        SddTaskStore firstStore = new SddTaskStore(
                "workspace-a", database.jdbc(), database.transactions(), database.json());
        SddTaskStore secondStore = new SddTaskStore(
                "workspace-b", database.jdbc(), database.transactions(), database.json());
        SddManagedTask first = task("first", "第一个任务");
        SddManagedTask second = task("second", "第二个任务");

        firstStore.replaceAll(List.of(first));
        secondStore.replaceAll(List.of(second));

        assertEquals(List.of("first"), firstStore.loadAll().stream().map(task -> task.id).toList());
        assertEquals(List.of("second"), secondStore.loadAll().stream().map(task -> task.id).toList());

        SddManagedTask duplicate = task("first", "重复主键");
        assertThrows(RuntimeException.class,
                () -> firstStore.replaceAll(List.of(first, duplicate)));

        assertEquals(List.of("first"), firstStore.loadAll().stream().map(task -> task.id).toList());
        assertEquals(List.of("second"), secondStore.loadAll().stream().map(task -> task.id).toList());
    }

    private static SddManagedTask task(String id, String title) {
        return new SddManagedTask(id, title, "测试索引事务", null,
                "auto", 0, "none", "2026-08-12 10:00:00");
    }

    private static int countRows(DatabaseAccess database, String table, String workspaceId,
                                 Path workDir, String slug) throws Exception {
        assertTrue(table.equals("sdd_spec_docs") || table.equals("sdd_verify_cache"));
        try (Connection connection = database.open();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*) FROM %s
                     WHERE workspace_id = ? AND work_dir = ? AND slug = ?
                     """.formatted(table))) {
            statement.setString(1, workspaceId);
            statement.setString(2, workDir.toAbsolutePath().normalize().toString());
            statement.setString(3, slug);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getInt(1);
            }
        }
    }
}
