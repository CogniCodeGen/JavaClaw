package com.javaclaw.application.skill;

import com.javaclaw.application.error.ValidationException;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** 技能中心输入校验与操作编排。 */
public final class SkillManagementUseCase implements SkillManagementApplicationService {

    private final SkillManagementPort skills;

    public SkillManagementUseCase(SkillManagementPort skills) {
        this.skills = Objects.requireNonNull(skills, "skills");
    }

    @Override public Snapshot snapshot() { return skills.snapshot(); }

    @Override public int pendingProposalCount() { return skills.pendingProposalCount(); }

    @Override public SkillDetail detail(String skillId) {
        return skills.detail(required(skillId, "技能 ID"));
    }

    @Override public OperationResult create() {
        SkillDetail created = skills.create();
        return new OperationResult(skills.snapshot(), created, "已创建技能");
    }

    @Override
    public OperationResult update(UpdateSkillCommand command) {
        Objects.requireNonNull(command, "command");
        UpdateSkillCommand checked = new UpdateSkillCommand(
                required(command.id(), "技能 ID"),
                required(command.name(), "技能名称"),
                normalized(command.description()),
                normalized(command.category()),
                normalizeItems(command.tags()),
                normalizedContent(command.content()),
                command.enabled());
        SkillDetail updated = skills.update(checked);
        return new OperationResult(skills.snapshot(), updated, "已保存");
    }

    @Override
    public OperationResult delete(String skillId) {
        skills.delete(required(skillId, "技能 ID"));
        return new OperationResult(skills.snapshot(), null, "已删除技能");
    }

    @Override
    public OperationResult rollback(String skillId, String version) {
        SkillDetail restored = skills.rollback(
                required(skillId, "技能 ID"), required(version, "历史版本"));
        return new OperationResult(skills.snapshot(), restored,
                "已回滚到 v" + version.strip());
    }

    @Override
    public ScriptDocument readScript(String skillId, String fileName) {
        return skills.readScript(required(skillId, "技能 ID"), scriptName(fileName));
    }

    @Override
    public ScriptMutation createScript(String skillId, String fileName) {
        String id = required(skillId, "技能 ID");
        ScriptDocument created = skills.createScript(id, scriptName(fileName));
        return new ScriptMutation(skills.detail(id), created,
                "已创建 " + created.fileName());
    }

    @Override
    public void saveScript(String skillId, String fileName, String content) {
        skills.saveScript(required(skillId, "技能 ID"), scriptName(fileName),
                content == null ? "" : content);
    }

    @Override
    public ScriptMutation deleteScript(String skillId, String fileName) {
        String id = required(skillId, "技能 ID");
        String name = scriptName(fileName);
        SkillDetail detail = skills.deleteScript(id, name);
        return new ScriptMutation(detail, null, "已删除 " + name);
    }

    @Override
    public ScriptReport checkScript(String code) {
        return skills.checkScript(requiredContent(code, "脚本内容"));
    }

    @Override
    public ScriptReport runScript(String skillId, String code, String arguments) {
        return skills.runScript(required(skillId, "技能 ID"),
                requiredContent(code, "脚本内容"), normalized(arguments));
    }

    @Override public List<ProposalItem> proposals() { return skills.proposals(); }

    @Override
    public ReviewResult approveProposal(String proposalId) {
        skills.approveProposal(required(proposalId, "提案 ID"));
        return new ReviewResult(skills.proposals(), skills.snapshot(), "提案已采纳");
    }

    @Override
    public ReviewResult rejectProposal(String proposalId) {
        skills.rejectProposal(required(proposalId, "提案 ID"));
        return new ReviewResult(skills.proposals(), skills.snapshot(), "提案已拒绝");
    }

    @Override
    public AutoCloseable subscribeToProposalChanges(Runnable listener) {
        return skills.subscribeToProposalChanges(Objects.requireNonNull(listener, "listener"));
    }

    @Override public List<BundleItem> bundles() { return skills.bundles(); }

    @Override
    public List<BundleItem> saveBundle(BundleCommand command) {
        Objects.requireNonNull(command, "command");
        BundleCommand checked = new BundleCommand(
                normalized(command.originalName()),
                required(command.name(), "技能包名称"),
                normalized(command.description()),
                normalizeItems(command.skills()),
                normalizedContent(command.extraInstructions()),
                command.enabled());
        return skills.saveBundle(checked);
    }

    @Override
    public List<BundleItem> deleteBundle(String name) {
        return skills.deleteBundle(required(name, "技能包名称"));
    }

    @Override
    public ImportInspection inspectImport(Path source, ImportKind kind) {
        return skills.inspectImport(requiredPath(source), Objects.requireNonNull(kind, "kind"));
    }

    @Override
    public ImportResult importSkill(Path source, ImportKind kind) {
        return skills.importSkill(requiredPath(source), Objects.requireNonNull(kind, "kind"));
    }

    @Override public Path skillsDirectory() { return skills.skillsDirectory(); }

    @Override
    public Path skillDirectory(String skillId) {
        return skills.skillDirectory(required(skillId, "技能 ID"));
    }

    private static Path requiredPath(Path path) {
        if (path == null) throw new ValidationException("导入来源不能为空");
        return path.toAbsolutePath().normalize();
    }

    private static String scriptName(String value) {
        String name = required(value, "脚本文件名");
        String lower = name.toLowerCase(Locale.ROOT);
        if (name.contains("/") || name.contains("\\")
                || !(lower.endsWith(".jsh") || lower.endsWith(".java"))) {
            throw new ValidationException("脚本文件名须以 .jsh 或 .java 结尾，且不能包含路径分隔符");
        }
        return name;
    }

    private static List<String> normalizeItems(List<String> values) {
        if (values == null) return List.of();
        return values.stream().map(SkillManagementUseCase::normalized)
                .filter(value -> !value.isBlank()).distinct().toList();
    }

    private static String requiredContent(String value, String label) {
        if (value == null || value.isBlank()) throw new ValidationException(label + "不能为空");
        return value;
    }

    private static String required(String value, String label) {
        String checked = normalized(value);
        if (checked.isBlank()) throw new ValidationException(label + "不能为空");
        return checked;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.strip();
    }

    private static String normalizedContent(String value) {
        return value == null ? "" : value;
    }
}
