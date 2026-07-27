package com.javaclaw.task.sdd.run;

import com.javaclaw.config.DatabaseAccess;
import com.javaclaw.config.FileDatabaseAccess;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SddTaskManagerPersistenceTest {

    @TempDir
    Path temp;

    @Test
    void 删除任务时从注入数据库清理规格和验收缓存() throws Exception {
        Path workDir = temp.resolve("workspace");
        Files.createDirectories(workDir);
        DatabaseAccess database = new FileDatabaseAccess(temp.resolve("database"));
        String workspaceId = "delete-workspace";
        SddManagedTask task = new SddManagedTask(
                "delete-id", "删除隔离", "验证删除目标数据库", workDir.toString(),
                "system", 0, null, "now");
        String slug = SpecPaths.makeSlug(task.id, task.title);

        SpecStore store = new SpecStore(workDir.toString(), database, workspaceId);
        store.writeProposal(slug, task.title, new Proposal("why", "what", ""));
        VerifyCache cache = VerifyCache.load(workDir.toString(), slug, database, workspaceId);
        cache.syncFingerprint("test-fingerprint");
        cache.recordPass("scenario", "passed");
        cache.save();

        assertEquals(1, countRows(database, "sdd_spec_docs", workspaceId, workDir, slug));
        assertEquals(1, countRows(database, "sdd_verify_cache", workspaceId, workDir, slug));

        SddTaskManager.deleteSpecDocs(database, workspaceId, task);

        assertEquals(0, countRows(database, "sdd_spec_docs", workspaceId, workDir, slug));
        assertEquals(0, countRows(database, "sdd_verify_cache", workspaceId, workDir, slug));
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
