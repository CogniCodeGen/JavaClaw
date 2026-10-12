package com.javaclaw.task.sdd.spec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;

/**
 * OpenSpec 真相层的读写入口。
 *
 * <p>JavaClaw 自身的 SDD 状态存储在全局 H2 {@code sdd_spec_docs} 表中，并按
 * {@code workspace_id} 隔离。{@code workDir} 只作为任务实际执行/验证的项目目录标识，
 * 不再承载应用状态文件。验收规格与任务进度以版本化 JSON 快照为准，Markdown 仅供阅读。</p>
 */
public final class SpecStore {

    private static final Logger log = LoggerFactory.getLogger(SpecStore.class);
    private static final String TASKS_STATE = "tasks.json";
    private static final String SPEC_STATE = "specs.json";
    private static final String PROPOSAL_STATE = "proposal.json";
    private static final String PREPARATION_STATE = "preparation.json";
    private static final String IMPLEMENTATION_EVIDENCE = "implementation-evidence.json";

    /** Host checkpoints; proposal and plan are written only after their review gate approves. */
    public enum PreparationStage { PROPOSAL, SPECIFICATIONS, DESIGN, PLAN }

    private final String workDir;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final String workspaceId;
    private final com.javaclaw.task.sdd.SddThreadGuard owner;

    public SpecStore(String workDir, JdbcTemplate jdbc, String workspaceId,
                     ObjectMapper json) {
        this(workDir, jdbc, workspaceId, null, json);
    }

    public SpecStore(String workDir, JdbcTemplate jdbc, String workspaceId,
                     String ownerThreadId, ObjectMapper json) {
        this.workDir = normalizeWorkDir(workDir);
        this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc");
        this.workspaceId = java.util.Objects.requireNonNull(workspaceId);
        this.json = java.util.Objects.requireNonNull(json, "json");
        this.owner = new com.javaclaw.task.sdd.SddThreadGuard(jdbc, workspaceId, ownerThreadId);
    }

    public String ownerThreadId() { return owner.threadId(); }

    public boolean available() {
        return workDir != null && !workDir.isBlank();
    }

    // ==================== 写入 ====================

    /** 写 proposal.md。 */
    public boolean writeProposal(String slug, String title, Proposal proposal) {
        if (proposal == null || proposal.why() == null || proposal.whatChanges() == null)
            return false;
        ObjectNode state = json.createObjectNode().put("version", 1);
        state.set("proposal", json.valueToTree(proposal));
        return replacePreparationArtifact(slug, PreparationStage.PROPOSAL,
                () -> write(slug, SpecPaths.PROPOSAL_FILE, SpecRenderer.renderProposal(title, proposal))
                        && write(slug, PROPOSAL_STATE, state.toString()));
    }

    /** 写 design.md；空设计也落盘，避免续跑时沿用先前的设计。 */
    public boolean writeDesign(String slug, String designMd) {
        return replacePreparationArtifact(slug, PreparationStage.DESIGN,
                () -> write(slug, SpecPaths.DESIGN_FILE, designMd == null ? "" : designMd));
    }

    /** 同写可读 tasks.md 与权威 tasks.json。 */
    public boolean writeTasks(String slug, List<TaskItem> tasks) {
        if (tasks == null) return false;
        if (tasks.stream().anyMatch(task -> task == null || task.index() < 1
                || task.action() == null || task.action().isBlank())) return false;
        for (int i = 0; i < tasks.size(); i++) {
            if (tasks.get(i).index() != i + 1) return false;
        }
        ObjectNode state = json.createObjectNode().put("version", 1);
        state.set("tasks", json.valueToTree(tasks));
        Supplier<Boolean> save = () -> write(slug, SpecPaths.TASKS_FILE, SpecRenderer.renderTasks(tasks))
                && write(slug, TASKS_STATE, state.toString());
        // Done-only progress preserves approval. New/split work must be reviewed on the next resume;
        // the existing in-flight implementation loop still owns its bounded refinement contract.
        return owner.ifAlive(() -> taskPlan(readTaskState(slug)).equals(taskPlan(tasks))
                ? save.get() : replacePreparationArtifact(slug, PreparationStage.PLAN, save), false);
    }

    /** 同写各能力的可读 spec.md 与权威 specs.json。 */
    public boolean writeCapabilitySpecs(String slug, List<Capability> capabilities) {
        if (capabilities == null || capabilities.isEmpty()
                || capabilities.stream().anyMatch(cap -> !validCapability(cap))) return false;
        ObjectNode state = json.createObjectNode().put("version", 1);
        state.set("capabilities", json.valueToTree(capabilities));
        return replacePreparationArtifact(slug, PreparationStage.SPECIFICATIONS, () -> {
            boolean ok = true;
            for (Capability cap : capabilities) {
                ok &= write(slug, changeSpecPath(cap.name()), SpecRenderer.renderCapabilitySpec(cap));
            }
            return ok && write(slug, SPEC_STATE, state.toString());
        });
    }

    // ==================== 准备阶段恢复 ====================

    /** A checkpoint matches the exact authoritative artifacts and their upstream dependencies. */
    public boolean preparationComplete(String slug, PreparationStage stage) {
        String revision = preparationRevision(slug, stage);
        return !revision.isBlank() && revision.equals(readPreparation(slug).path(stage.name()).asText());
    }

    public boolean preparationReady(String slug) {
        for (PreparationStage stage : PreparationStage.values()) {
            if (!preparationComplete(slug, stage)) return false;
        }
        return true;
    }

    /** Capture before review so a late approval cannot approve changed artifacts. */
    public String preparationRevision(String slug, PreparationStage stage) {
        return preparationRevision(readChange(slug, null, null), stage);
    }

    /** Fingerprint the same immutable snapshot supplied to the model or human review. */
    public String preparationRevision(OpenSpecChange change, PreparationStage stage) {
        if (change.proposal() == null) return "";
        ObjectNode basis = json.createObjectNode().put("stage", stage.name());
        basis.set("proposal", json.valueToTree(change.proposal()));
        if (stage.ordinal() >= PreparationStage.SPECIFICATIONS.ordinal()) {
            if (change.capabilities().isEmpty()) return "";
            basis.set("capabilities", json.valueToTree(change.capabilities()));
        }
        if (stage.ordinal() >= PreparationStage.DESIGN.ordinal()) {
            basis.put("design", change.design() == null ? "" : change.design());
        }
        if (stage == PreparationStage.PLAN) {
            if (change.tasks().isEmpty()) return "";
            basis.set("tasks", taskPlan(change.tasks()));
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(basis.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public boolean completePreparation(String slug, PreparationStage stage, String reviewedRevision) {
        if (reviewedRevision == null || reviewedRevision.isBlank()) return false;
        return owner.ifAlive(() -> {
            if (!reviewedRevision.equals(preparationRevision(slug, stage))) return false;
            ObjectNode state = readPreparation(slug);
            state.put(stage.name(), reviewedRevision);
            return write(slug, PREPARATION_STATE, state.toString());
        }, false);
    }

    /** Approval, original user requirements and work directory bind every implementation receipt. */
    public String implementationBasis(String slug, String description) {
        return owner.ifAlive(() -> implementationBasis(slug, description,
                readChange(slug, null, null)), "");
    }

    public String implementationBasis(String slug, String description, OpenSpecChange change) {
        return owner.ifAlive(() -> {
            if (!preparationReady(slug) || !preparationRevision(change, PreparationStage.PLAN)
                    .equals(preparationRevision(slug, PreparationStage.PLAN))) return "";
            return implementationBasisValue(change, description);
        }, "");
    }

    private String implementationBasisValue(OpenSpecChange change, String description) {
        ObjectNode basis = json.createObjectNode()
                .put("plan", preparationRevision(change, PreparationStage.PLAN))
                .put("description", description == null ? "" : description)
                .put("workDir", workDir);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(basis.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** IDs only: acceptance re-reads canonical Runs and receipts rather than trusting this document. */
    public ObjectNode implementationEvidence(String slug, String basis) {
        String stored = read(slug, IMPLEMENTATION_EVIDENCE);
        if (stored != null && basis != null && !basis.isBlank()) {
            try {
                JsonNode state = json.readTree(stored);
                if (state instanceof ObjectNode object && state.path("version").isIntegralNumber()
                        && state.path("version").canConvertToInt()
                        && state.path("version").intValue() == 1
                        && basis.equals(state.path("basis").asText())
                        && state.path("items").isObject()) return object;
            } catch (Exception ignored) { /* Legacy/malformed association cannot grant acceptance. */ }
        }
        ObjectNode empty = json.createObjectNode().put("version", 1).put("basis", basis);
        empty.putObject("items");
        return empty;
    }

    public boolean recordImplementationEvidence(String slug, String description, String basis,
                                                int index, String runId, String parentRunId) {
        if (basis == null || basis.isBlank() || index < 1 || runId == null || runId.isBlank()
                || parentRunId == null || parentRunId.isBlank()) return false;
        return owner.ifAlive(() -> {
            if (!basis.equals(implementationBasis(slug, description))
                    || readTaskState(slug).stream().noneMatch(item -> item.index() == index)) return false;
            ObjectNode state = implementationEvidence(slug, basis);
            ObjectNode items = (ObjectNode) state.path("items");
            for (var fields = items.fields(); fields.hasNext();) {
                var entry = fields.next();
                if (!entry.getKey().equals(Integer.toString(index))
                        && runId.equals(entry.getValue().path("runId").asText())) return false;
            }
            items.putObject(Integer.toString(index)).put("runId", runId).put("parentRunId", parentRunId);
            return write(slug, IMPLEMENTATION_EVIDENCE, state.toString());
        }, false);
    }

    /** Old valid outputs may be reused, but no legacy review is inferred as approved. */
    public boolean recoverLegacyPreparation(String slug) {
        return owner.ifAlive(() -> {
            if (read(slug, PREPARATION_STATE) != null) return true;
            OpenSpecChange change = readChange(slug, null, null);
            if (change.proposal() == null) return true;
            ObjectNode state = json.createObjectNode().put("version", 1);
            if (!change.capabilities().isEmpty()) {
                state.put(PreparationStage.SPECIFICATIONS.name(),
                        preparationRevision(slug, PreparationStage.SPECIFICATIONS));
                if (change.design() != null) {
                    state.put(PreparationStage.DESIGN.name(),
                            preparationRevision(slug, PreparationStage.DESIGN));
                }
            }
            return write(slug, PREPARATION_STATE, state.toString());
        }, false);
    }

    private ObjectNode readPreparation(String slug) {
        String stored = read(slug, PREPARATION_STATE);
        if (stored != null) {
            try {
                JsonNode state = json.readTree(stored);
                if (state instanceof ObjectNode object && state.path("version").isIntegralNumber()
                        && state.path("version").canConvertToInt()
                        && state.path("version").intValue() == 1
                        && state.size() <= PreparationStage.values().length + 1) {
                    boolean valid = true;
                    for (var fields = state.properties().iterator(); fields.hasNext();) {
                        var field = fields.next();
                        if (field.getKey().equals("version")) continue;
                        PreparationStage.valueOf(field.getKey());
                        valid &= field.getValue().isTextual()
                                && field.getValue().asText().matches("[0-9a-f]{64}");
                    }
                    if (valid) return object;
                }
            } catch (Exception invalid) {
                log.warn("[Spec] 准备阶段恢复快照无效 slug={}", slug);
            }
        }
        return json.createObjectNode().put("version", 1);
    }

    private JsonNode taskPlan(List<TaskItem> tasks) {
        JsonNode plan = json.valueToTree(tasks);
        plan.forEach(task -> ((ObjectNode) task).remove("done"));
        return plan;
    }

    private boolean replacePreparationArtifact(String slug, PreparationStage stage, Supplier<Boolean> save) {
        return owner.ifAlive(() -> {
            ObjectNode state = readPreparation(slug);
            for (PreparationStage affected : PreparationStage.values()) {
                if (affected.ordinal() >= stage.ordinal()) state.remove(affected.name());
            }
            return write(slug, PREPARATION_STATE, state.toString()) && save.get();
        }, false);
    }

    // ==================== 读取（折叠为派生视图） ====================

    /**
     * 读出整个变更并折叠为 {@link OpenSpecChange}。结构化快照缺失则对应控制字段为空，
     * 不影响其余部分（支持半成品 change：只有 proposal、尚无 tasks 等）。
     */
    public OpenSpecChange readChange(String slug, String id, String title) {
        Proposal proposal = readProposalState(slug);

        String design = read(slug, SpecPaths.DESIGN_FILE);
        List<TaskItem> tasks = readTaskState(slug);
        List<Capability> capabilities = readChangeCapabilities(slug);

        return new OpenSpecChange(id, slug, title, proposal, capabilities, design, tasks);
    }

    private Proposal readProposalState(String slug) {
        String state = read(slug, PROPOSAL_STATE);
        if (state == null) return null;
        try {
            JsonNode root = json.readTree(state);
            JsonNode value = root == null ? null : root.path("proposal");
            if (root == null || !root.isObject() || root.size() != 2
                    || !root.path("version").isIntegralNumber()
                    || root.path("version").intValue() != 1
                    || value == null || !value.isObject() || value.size() != 3
                    || !value.path("why").isTextual()
                    || !value.path("whatChanges").isTextual()
                    || !value.path("outOfScope").isTextual()
                            && !value.path("outOfScope").isNull()) return null;
            return json.treeToValue(value, Proposal.class);
        } catch (Exception invalid) {
            log.warn("[Spec] 结构化提案快照无效 slug={}", slug, invalid);
            return null;
        }
    }

    private List<Capability> readChangeCapabilities(String slug) {
        String state = read(slug, SPEC_STATE);
        if (state == null) return List.of();
        try {
            JsonNode root = json.readTree(state);
            if (root == null || !root.isObject() || root.size() != 2
                    || !root.path("version").isIntegralNumber()
                    || root.path("version").intValue() != 1
                    || !root.path("capabilities").isArray()
                    || root.path("capabilities").isEmpty()) return List.of();
            List<Capability> parsed = new ArrayList<>();
            for (JsonNode value : root.path("capabilities")) {
                if (!validCapabilityNode(value)) return List.of();
                Capability capability = json.treeToValue(value, Capability.class);
                if (!validCapability(capability)) return List.of();
                parsed.add(capability);
            }
            return List.copyOf(parsed);
        } catch (Exception invalid) {
            log.warn("[Spec] 结构化规格快照无效 slug={}", slug, invalid);
            return List.of();
        }
    }

    /** 列出当前 workDir 下所有变更 slug。 */
    public List<String> listChangeSlugs() {
        if (!available()) return List.of();
        try {
            return owner.ifAlive(() -> jdbc.queryForList("""
                            SELECT DISTINCT slug
                            FROM sdd_spec_docs
                            WHERE workspace_id = ? AND work_dir = ?
                            ORDER BY slug
                            """, String.class, workspaceId, workDir), List.of());
        } catch (DataAccessException e) {
            log.warn("[Spec] 从 H2 列举变更失败: {}", e.getMessage());
            return List.of();
        }
    }

    public boolean changeExists(String slug) {
        if (!available()) return false;
        try {
            Integer count = owner.ifAlive(() -> jdbc.queryForObject("""
                            SELECT COUNT(*)
                            FROM sdd_spec_docs
                            WHERE workspace_id = ? AND work_dir = ? AND slug = ?
                            """, Integer.class, workspaceId, workDir, slug), 0);
            return count != null && count > 0;
        } catch (DataAccessException e) {
            log.warn("[Spec] 检查变更存在性失败 slug={}: {}", slug, e.getMessage());
            return false;
        }
    }

    // ==================== tasks.md 勾选回写 ====================

    /**
     * 把指定编号的实现项标记为完成，写回结构化快照并更新 Markdown 投影。
     */
    public boolean checkTask(String slug, int index) {
        List<TaskItem> tasks = readTaskState(slug);
        List<TaskItem> updated = new ArrayList<>(tasks.size());
        boolean changed = false;
        for (TaskItem task : tasks) {
            if (task.index() == index && !task.done()) {
                updated.add(new TaskItem(task.index(), task.action(), task.files(), task.criterion(), true));
                changed = true;
            } else {
                updated.add(task);
            }
        }
        return changed && writeTasks(slug, updated);
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
        try {
            return owner.ifAlive(() -> {
                jdbc.update("""
                                MERGE INTO sdd_spec_docs(
                                    workspace_id, work_dir, slug, doc_path, doc_text, updated_at
                                ) KEY(workspace_id, work_dir, slug, doc_path)
                                VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                                """,
                        workspaceId, workDir, slug, docPath, content);
                log.info("[Spec] 已写入 H2: slug={}, path={}, bytes={}",
                        slug, docPath, content == null ? 0 : content.getBytes(StandardCharsets.UTF_8).length);
                return true;
            }, false);
        } catch (DataAccessException e) {
            log.warn("[Spec] 写入 H2 失败 slug={}, path={}: {}", slug, docPath, e.getMessage());
            return false;
        }
    }

    private String read(String slug, String docPath) {
        if (!available() || slug == null || slug.isBlank() || docPath == null || docPath.isBlank()) {
            return null;
        }
        try {
            return owner.ifAlive(() -> jdbc.query("""
                            SELECT doc_text
                            FROM sdd_spec_docs
                            WHERE workspace_id = ? AND work_dir = ? AND slug = ? AND doc_path = ?
                            """,
                    rows -> rows.next() ? rows.getString(1) : null,
                    workspaceId, workDir, slug, docPath), null);
        } catch (DataAccessException e) {
            log.debug("[Spec] 读取 H2 失败 slug={}, path={}: {}", slug, docPath, e.getMessage());
            return null;
        }
    }

    private static String changeSpecPath(String capability) {
        return SpecPaths.SPECS_DIR + "/" + capability + "/" + SpecPaths.SPEC_FILE;
    }

    private List<TaskItem> readTaskState(String slug) {
        String state = read(slug, TASKS_STATE);
        if (state == null) return List.of();
        try {
            JsonNode root = json.readTree(state);
            if (root == null || !root.isObject() || root.size() != 2
                    || !root.path("version").isIntegralNumber()
                    || root.path("version").intValue() != 1 || !root.path("tasks").isArray()) {
                return List.of();
            }
            List<TaskItem> tasks = new ArrayList<>();
            for (JsonNode item : root.path("tasks")) {
                if (!item.isObject() || !item.path("index").isIntegralNumber()
                        || !item.path("action").isTextual() || !item.path("done").isBoolean()
                        || !item.path("files").isArray()
                        || !item.path("criterion").isNull()
                        && !item.path("criterion").isTextual()) return List.of();
                for (JsonNode file : item.path("files")) {
                    if (!file.isTextual()) return List.of();
                }
                TaskItem task = json.treeToValue(item, TaskItem.class);
                if (task.index() != tasks.size() + 1
                        || task.action() == null || task.action().isBlank()) return List.of();
                tasks.add(task);
            }
            return List.copyOf(tasks);
        } catch (Exception invalid) {
            log.warn("[Spec] 结构化任务快照无效 slug={}", slug, invalid);
            return List.of();
        }
    }

    private static boolean validCapabilityNode(JsonNode cap) {
        if (!cap.isObject() || !cap.path("name").isTextual()
                || !cap.path("requirements").isArray()) return false;
        for (JsonNode req : cap.path("requirements")) {
            if (!req.isObject() || !req.path("title").isTextual()
                    || !req.path("scenarios").isArray()) return false;
            for (JsonNode scenario : req.path("scenarios")) {
                if (!scenario.isObject() || !scenario.path("title").isTextual()
                        || !scenario.path("given").isTextual()
                        || !scenario.path("when").isTextual()
                        || !scenario.path("then").isTextual()
                        || !scenario.path("criterion").isObject()
                        || !scenario.path("criterion").path("type").isTextual()
                        || !scenario.path("criterion").path("predicate").isTextual()) return false;
            }
        }
        return true;
    }

    private static boolean validCapability(Capability cap) {
        if (cap == null || cap.name() == null || cap.name().isBlank()
                || cap.name().contains("/") || cap.name().contains("\\")
                || cap.requirements().isEmpty()) return false;
        for (Requirement req : cap.requirements()) {
            if (req == null || req.title() == null || req.title().isBlank()
                    || req.scenarios().isEmpty()) return false;
            for (Scenario scenario : req.scenarios()) {
                if (scenario == null || scenario.title() == null || scenario.title().isBlank()
                        || scenario.criterion() == null || scenario.criterion().type() == null
                        || scenario.criterion().predicate() == null) return false;
            }
        }
        return true;
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
