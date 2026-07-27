package com.javaclaw.task.sdd.spec;

import com.javaclaw.config.AppDatabase;
import com.javaclaw.config.AppDatabaseAccess;
import com.javaclaw.config.DatabaseAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OpenSpec 真相层的读写入口。
 *
 * <p>JavaClaw 自身的 SDD 状态存储在全局 H2 {@code sdd_spec_docs} 表中，并按
 * {@code workspace_id} 隔离。{@code workDir} 只作为任务实际执行/验证的项目目录标识，
 * 不再承载应用状态文件。</p>
 */
public final class SpecStore {

    private static final Logger log = LoggerFactory.getLogger(SpecStore.class);

    private static final Pattern TASK_CHECKBOX =
            Pattern.compile("^(\\s*[-*]\\s*\\[)([ xX])(\\]\\s*(\\d+)[.、].*)$");

    private final String workDir;
    private final DatabaseAccess database;
    private final String workspaceId;

    public SpecStore(String workDir) {
        this(workDir, new AppDatabaseAccess(), AppDatabase.currentWorkspaceId());
    }

    public SpecStore(String workDir, DatabaseAccess database, String workspaceId) {
        this.workDir = normalizeWorkDir(workDir);
        this.database = java.util.Objects.requireNonNull(database);
        this.workspaceId = java.util.Objects.requireNonNull(workspaceId);
    }

    public boolean available() {
        return workDir != null && !workDir.isBlank();
    }

    // ==================== 写入 ====================

    /** 写 proposal.md。 */
    public boolean writeProposal(String slug, String title, Proposal proposal) {
        return write(slug, SpecPaths.PROPOSAL_FILE, SpecRenderer.renderProposal(title, proposal));
    }

    /** 写 design.md（markdown 原文；null/空白时跳过）。 */
    public boolean writeDesign(String slug, String designMd) {
        if (designMd == null || designMd.isBlank()) return false;
        return write(slug, SpecPaths.DESIGN_FILE, designMd);
    }

    /** 写 tasks.md（覆盖；每次重新拆解都重写）。 */
    public boolean writeTasks(String slug, List<TaskItem> tasks) {
        return write(slug, SpecPaths.TASKS_FILE, SpecRenderer.renderTasks(tasks));
    }

    /** 写各能力的 changes/{slug}/specs/{能力}/spec.md。 */
    public boolean writeCapabilitySpecs(String slug, List<Capability> capabilities) {
        if (capabilities == null) return false;
        boolean ok = true;
        for (Capability cap : capabilities) {
            ok &= write(slug, changeSpecPath(cap.name()), SpecRenderer.renderCapabilitySpec(cap));
        }
        return ok;
    }

    // ==================== 读取（折叠为派生视图） ====================

    /**
     * 读出整个变更并折叠为 {@link OpenSpecChange}。任一文档缺失则对应字段为空，
     * 不影响其余部分（支持半成品 change：只有 proposal、尚无 tasks 等）。
     */
    public OpenSpecChange readChange(String slug, String id, String title) {
        Proposal proposal = null;
        String proposalMd = read(slug, SpecPaths.PROPOSAL_FILE);
        if (proposalMd != null) proposal = SpecParser.parseProposal(proposalMd);

        String design = read(slug, SpecPaths.DESIGN_FILE);
        List<TaskItem> tasks = SpecParser.parseTasks(read(slug, SpecPaths.TASKS_FILE));
        List<Capability> capabilities = readChangeCapabilities(slug);

        return new OpenSpecChange(id, slug, title, proposal, capabilities, design, tasks);
    }

    private List<Capability> readChangeCapabilities(String slug) {
        List<Capability> out = new ArrayList<>();
        if (!available()) return out;
        String prefix = SpecPaths.SPECS_DIR + "/";
        String suffix = "/" + SpecPaths.SPEC_FILE;
        try (Connection c = database.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT doc_path, doc_text
                     FROM sdd_spec_docs
                     WHERE workspace_id = ? AND work_dir = ? AND slug = ? AND doc_path LIKE ?
                     ORDER BY doc_path
                     """)) {
            ps.setString(1, workspaceId);
            ps.setString(2, workDir);
            ps.setString(3, slug);
            ps.setString(4, prefix + "%" + suffix);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String path = rs.getString("doc_path");
                    if (!path.startsWith(prefix) || !path.endsWith(suffix)) continue;
                    String name = path.substring(prefix.length(), path.length() - suffix.length());
                    out.add(SpecParser.parseCapabilitySpec(rs.getString("doc_text"), name));
                }
            }
        } catch (Exception e) {
            log.warn("[Spec] 从 H2 列举能力规格失败 slug={}: {}", slug, e.getMessage());
        }
        return out;
    }

    /** 列出当前 workDir 下所有变更 slug。 */
    public List<String> listChangeSlugs() {
        List<String> out = new ArrayList<>();
        if (!available()) return out;
        try (Connection c = database.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT DISTINCT slug
                     FROM sdd_spec_docs
                     WHERE workspace_id = ? AND work_dir = ?
                     ORDER BY slug
                     """)) {
            ps.setString(1, workspaceId);
            ps.setString(2, workDir);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString("slug"));
            }
        } catch (Exception e) {
            log.warn("[Spec] 从 H2 列举变更失败: {}", e.getMessage());
        }
        return out;
    }

    public boolean changeExists(String slug) {
        if (!available()) return false;
        try (Connection c = database.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT 1
                     FROM sdd_spec_docs
                     WHERE workspace_id = ? AND work_dir = ? AND slug = ?
                     LIMIT 1
                     """)) {
            ps.setString(1, workspaceId);
            ps.setString(2, workDir);
            ps.setString(3, slug);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            log.warn("[Spec] 检查变更存在性失败 slug={}: {}", slug, e.getMessage());
            return false;
        }
    }

    // ==================== tasks.md 勾选回写 ====================

    /**
     * 把指定编号的实现项勾选为完成（{@code [ ]} → {@code [x]}），写回 H2。
     */
    public boolean checkTask(String slug, int index) {
        String md = read(slug, SpecPaths.TASKS_FILE);
        if (md == null) return false;
        String[] lines = md.split("\n", -1);
        boolean changed = false;
        for (int i = 0; i < lines.length; i++) {
            Matcher m = TASK_CHECKBOX.matcher(lines[i]);
            if (m.matches() && Integer.parseInt(m.group(4)) == index) {
                if (m.group(2).isBlank()) {
                    lines[i] = m.group(1) + "x" + m.group(3);
                    changed = true;
                }
                break;
            }
        }
        if (!changed) return false;
        return write(slug, SpecPaths.TASKS_FILE, String.join("\n", lines));
    }

    /**
     * 追加新实现项到 tasks.md 末尾（重规划补做：验收未过时补的修复项）。
     */
    public boolean appendTasks(String slug, List<String> actions) {
        if (actions == null || actions.isEmpty()) return false;
        OpenSpecChange change = readChange(slug, null, null);
        List<TaskItem> merged = new ArrayList<>(change.tasks());
        int next = merged.stream().mapToInt(TaskItem::index).max().orElse(0) + 1;
        for (String action : actions) {
            merged.add(new TaskItem(next++, action, List.of(), null, false));
        }
        return writeTasks(slug, merged);
    }

    /**
     * 懒拆解：把某个过大的实现项就地替换为若干子项，并对全表重新编号。
     */
    public boolean splitTask(String slug, int parentIndex, List<String> childActions) {
        if (childActions == null || childActions.isEmpty()) return false;
        OpenSpecChange change = readChange(slug, null, null);
        List<TaskItem> old = change.tasks();
        if (old.stream().noneMatch(t -> t.index() == parentIndex)) return false;

        List<TaskItem> rebuilt = new ArrayList<>();
        int n = 1;
        for (TaskItem t : old) {
            if (t.index() == parentIndex) {
                for (String action : childActions) {
                    rebuilt.add(new TaskItem(n++, action, List.of(), null, false));
                }
            } else {
                rebuilt.add(new TaskItem(n++, t.action(), t.files(), t.criterion(), t.done()));
            }
        }
        return writeTasks(slug, rebuilt);
    }

    // ==================== 归档 ====================

    /**
     * 变更通过验收后归档：把变更能力规格复制到当前规格文档，并标注 proposal 已完成。
     */
    public boolean archive(String slug, String completionStamp) {
        boolean ok = true;
        for (Capability cap : readChangeCapabilities(slug)) {
            ok &= write(slug, archivedSpecPath(cap.name()), SpecRenderer.renderCapabilitySpec(cap));
        }
        String md = read(slug, SpecPaths.PROPOSAL_FILE);
        if (md != null) {
            ok &= write(slug, SpecPaths.PROPOSAL_FILE,
                    md + "\n\n---\n\n> **已完成**：" + (completionStamp == null ? "" : completionStamp) + "\n");
        }
        return ok;
    }

    // ==================== H2 读写 ====================

    private boolean write(String slug, String docPath, String content) {
        if (!available() || slug == null || slug.isBlank() || docPath == null || docPath.isBlank()) {
            return false;
        }
        try (Connection c = database.open();
             PreparedStatement ps = c.prepareStatement("""
                     MERGE INTO sdd_spec_docs(workspace_id, work_dir, slug, doc_path, doc_text, updated_at)
                     KEY(workspace_id, work_dir, slug, doc_path)
                     VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                     """)) {
            ps.setString(1, workspaceId);
            ps.setString(2, workDir);
            ps.setString(3, slug);
            ps.setString(4, docPath);
            ps.setString(5, content);
            ps.executeUpdate();
            log.info("[Spec] 已写入 H2: slug={}, path={}, bytes={}",
                    slug, docPath, content == null ? 0 : content.getBytes(StandardCharsets.UTF_8).length);
            return true;
        } catch (Exception e) {
            log.warn("[Spec] 写入 H2 失败 slug={}, path={}: {}", slug, docPath, e.getMessage());
            return false;
        }
    }

    private String read(String slug, String docPath) {
        if (!available() || slug == null || slug.isBlank() || docPath == null || docPath.isBlank()) {
            return null;
        }
        try (Connection c = database.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT doc_text
                     FROM sdd_spec_docs
                     WHERE workspace_id = ? AND work_dir = ? AND slug = ? AND doc_path = ?
                     """)) {
            ps.setString(1, workspaceId);
            ps.setString(2, workDir);
            ps.setString(3, slug);
            ps.setString(4, docPath);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (Exception e) {
            log.debug("[Spec] 读取 H2 失败 slug={}, path={}: {}", slug, docPath, e.getMessage());
            return null;
        }
    }

    private static String changeSpecPath(String capability) {
        return SpecPaths.SPECS_DIR + "/" + capability + "/" + SpecPaths.SPEC_FILE;
    }

    private static String archivedSpecPath(String capability) {
        return "archived/" + SpecPaths.SPECS_DIR + "/" + capability + "/" + SpecPaths.SPEC_FILE;
    }

    private static String normalizeWorkDir(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Path.of(raw).toAbsolutePath().normalize().toString();
        } catch (Exception e) {
            return raw.trim();
        }
    }
}
