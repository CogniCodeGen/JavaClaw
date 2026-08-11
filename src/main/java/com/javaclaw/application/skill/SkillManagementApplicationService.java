package com.javaclaw.application.skill;

import java.nio.file.Path;
import java.util.List;

/**
 * 技能中心的应用层入口。
 *
 * <p>所有方法都可能访问文件或数据库，调用方必须从托管 I/O 任务执行；脚本运行还会启动
 * 独立 JShell 进程。返回值均为不可变快照，不得跨工作区 Context 生命周期缓存。提案监听器
 * 可能从任意后台线程回调，订阅方关闭页面时必须关闭返回的句柄。</p>
 */
public interface SkillManagementApplicationService {

    Snapshot snapshot();

    /** 内存计数，只读且非阻塞，可从 FX 状态栏刷新调用。 */
    int pendingProposalCount();

    SkillDetail detail(String skillId);

    OperationResult create();

    OperationResult update(UpdateSkillCommand command);

    OperationResult delete(String skillId);

    OperationResult rollback(String skillId, String version);

    ScriptDocument readScript(String skillId, String fileName);

    ScriptMutation createScript(String skillId, String fileName);

    void saveScript(String skillId, String fileName, String content);

    ScriptMutation deleteScript(String skillId, String fileName);

    ScriptReport checkScript(String code);

    ScriptReport runScript(String skillId, String code, String arguments);

    List<ProposalItem> proposals();

    ReviewResult approveProposal(String proposalId);

    ReviewResult rejectProposal(String proposalId);

    AutoCloseable subscribeToProposalChanges(Runnable listener);

    List<BundleItem> bundles();

    List<BundleItem> saveBundle(BundleCommand command);

    List<BundleItem> deleteBundle(String name);

    ImportInspection inspectImport(Path source, ImportKind kind);

    ImportResult importSkill(Path source, ImportKind kind);

    Path skillsDirectory();

    Path skillDirectory(String skillId);

    record Snapshot(List<SkillSummary> skills, int pendingProposalCount, Path skillsDirectory) {
        public Snapshot {
            skills = List.copyOf(skills == null ? List.of() : skills);
            pendingProposalCount = Math.max(0, pendingProposalCount);
            skillsDirectory = java.util.Objects.requireNonNull(skillsDirectory, "skillsDirectory");
        }

        public SkillSummary find(String id) {
            return skills.stream().filter(skill -> skill.id().equals(id)).findFirst().orElse(null);
        }
    }

    record SkillSummary(
            String id,
            String name,
            String description,
            String version,
            String source,
            List<String> tags,
            boolean agentCreated,
            boolean enabled) {
        public SkillSummary {
            id = text(id);
            name = text(name);
            description = text(description);
            version = text(version);
            source = text(source);
            tags = List.copyOf(tags == null ? List.of() : tags);
        }
    }

    record SkillDetail(
            String id,
            String name,
            String description,
            String category,
            List<String> tags,
            String content,
            boolean enabled,
            String version,
            String source,
            boolean agentCreated,
            boolean userModified,
            Usage usage,
            List<String> history,
            List<String> scripts,
            String directoryStructure,
            Path directory) {
        public SkillDetail {
            id = text(id);
            name = text(name);
            description = text(description);
            category = text(category);
            tags = List.copyOf(tags == null ? List.of() : tags);
            content = text(content);
            version = text(version);
            source = text(source);
            usage = usage == null ? Usage.empty() : usage;
            history = List.copyOf(history == null ? List.of() : history);
            scripts = List.copyOf(scripts == null ? List.of() : scripts);
            directoryStructure = text(directoryStructure);
            directory = java.util.Objects.requireNonNull(directory, "directory");
        }
    }

    record Usage(long routeHits, long reads, long samples, double successRate) {
        public static Usage empty() {
            return new Usage(0, 0, 0, -1);
        }
    }

    record UpdateSkillCommand(
            String id,
            String name,
            String description,
            String category,
            List<String> tags,
            String content,
            boolean enabled) {
        public UpdateSkillCommand {
            tags = List.copyOf(tags == null ? List.of() : tags);
        }
    }

    record OperationResult(Snapshot snapshot, SkillDetail detail, String message) {
        public OperationResult {
            snapshot = java.util.Objects.requireNonNull(snapshot, "snapshot");
            message = text(message);
        }
    }

    record ScriptDocument(String fileName, String content) {
        public ScriptDocument {
            fileName = text(fileName);
            content = text(content);
        }
    }

    record ScriptMutation(SkillDetail detail, ScriptDocument document, String message) {
        public ScriptMutation {
            detail = java.util.Objects.requireNonNull(detail, "detail");
            message = text(message);
        }
    }

    record ScriptReport(
            boolean success,
            boolean timedOut,
            int timeoutSeconds,
            String output,
            String lastValue,
            List<String> problems) {
        public ScriptReport {
            timeoutSeconds = Math.max(0, timeoutSeconds);
            output = text(output);
            lastValue = text(lastValue);
            problems = List.copyOf(problems == null ? List.of() : problems);
        }
    }

    record ProposalItem(
            String id,
            String action,
            String skillName,
            String reason,
            String preview,
            boolean userModifiedWarning,
            long createdAt) {
        public ProposalItem {
            id = text(id);
            action = text(action);
            skillName = text(skillName);
            reason = text(reason);
            preview = text(preview);
        }
    }

    record ReviewResult(List<ProposalItem> proposals, Snapshot snapshot, String message) {
        public ReviewResult {
            proposals = List.copyOf(proposals == null ? List.of() : proposals);
            snapshot = java.util.Objects.requireNonNull(snapshot, "snapshot");
            message = text(message);
        }
    }

    record BundleItem(
            String name,
            String description,
            List<String> skills,
            String extraInstructions,
            boolean enabled) {
        public BundleItem {
            name = text(name);
            description = text(description);
            skills = List.copyOf(skills == null ? List.of() : skills);
            extraInstructions = text(extraInstructions);
        }
    }

    record BundleCommand(
            String originalName,
            String name,
            String description,
            List<String> skills,
            String extraInstructions,
            boolean enabled) {
        public BundleCommand {
            skills = List.copyOf(skills == null ? List.of() : skills);
        }
    }

    enum ImportKind {
        DIRECTORY,
        ZIP
    }

    record ImportInspection(List<String> scripts) {
        public ImportInspection {
            scripts = List.copyOf(scripts == null ? List.of() : scripts);
        }

        public boolean containsScripts() {
            return !scripts.isEmpty();
        }
    }

    record ImportResult(Snapshot snapshot, String message, Path installedDirectory) {
        public ImportResult {
            snapshot = java.util.Objects.requireNonNull(snapshot, "snapshot");
            message = text(message);
        }
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
