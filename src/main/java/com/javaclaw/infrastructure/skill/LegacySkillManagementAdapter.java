package com.javaclaw.infrastructure.skill;

import com.javaclaw.application.error.ConflictException;
import com.javaclaw.application.error.NotFoundException;
import com.javaclaw.application.error.RejectedException;
import com.javaclaw.application.skill.SkillManagementApplicationService.BundleCommand;
import com.javaclaw.application.skill.SkillManagementApplicationService.BundleItem;
import com.javaclaw.application.skill.SkillManagementApplicationService.ImportInspection;
import com.javaclaw.application.skill.SkillManagementApplicationService.ImportKind;
import com.javaclaw.application.skill.SkillManagementApplicationService.ImportResult;
import com.javaclaw.application.skill.SkillManagementApplicationService.ProposalItem;
import com.javaclaw.application.skill.SkillManagementApplicationService.ScriptDocument;
import com.javaclaw.application.skill.SkillManagementApplicationService.ScriptReport;
import com.javaclaw.application.skill.SkillManagementApplicationService.SkillDetail;
import com.javaclaw.application.skill.SkillManagementApplicationService.SkillSummary;
import com.javaclaw.application.skill.SkillManagementApplicationService.Snapshot;
import com.javaclaw.application.skill.SkillManagementApplicationService.UpdateSkillCommand;
import com.javaclaw.application.skill.SkillManagementApplicationService.Usage;
import com.javaclaw.application.skill.SkillManagementPort;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.skill.Skill;
import com.javaclaw.skill.SkillBundle;
import com.javaclaw.skill.SkillInstaller;
import com.javaclaw.skill.SkillManager;
import com.javaclaw.skill.SkillSource;
import com.javaclaw.skill.SkillUsageTracker;
import com.javaclaw.skill.curation.SkillProposal;
import com.javaclaw.skill.curation.SkillProposalQueue;
import com.javaclaw.system.JShellRunner;
import com.javaclaw.system.JShellTools;
import com.javaclaw.util.PathGuard;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 将现有技能文件模型适配为不可变应用层快照。
 *
 * <p>SkillManager 的内存集合不是并发容器，因此短查询和变更均在内部锁中串行化；JShell
 * 求值会在取得稳定前置片段后离开锁，避免长任务阻塞技能查询。</p>
 */
public final class LegacySkillManagementAdapter implements SkillManagementPort {

    private static final String NEW_SCRIPT_HEADER =
            "// %s — 可用预绑定变量：SKILL_DIR（技能目录）、ARGS（参数数组）\n";

    private final SkillManager skills;
    private final SkillUsageTracker usage;
    private final SkillProposalQueue proposals;
    private final SkillInstaller installer;
    private final AgentConfig settings;
    private final JShellRunner jshellRunner;
    private final Object skillLock = new Object();

    public LegacySkillManagementAdapter(
            SkillManager skills,
            SkillUsageTracker usage,
            SkillProposalQueue proposals,
            SkillInstaller installer,
            AgentConfig settings,
            JShellRunner jshellRunner) {
        this.skills = Objects.requireNonNull(skills, "skills");
        this.usage = Objects.requireNonNull(usage, "usage");
        this.proposals = Objects.requireNonNull(proposals, "proposals");
        this.installer = Objects.requireNonNull(installer, "installer");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.jshellRunner = Objects.requireNonNull(jshellRunner, "jshellRunner");
    }

    @Override
    public Snapshot snapshot() {
        synchronized (skillLock) {
            List<SkillSummary> items = skills.getAllSkills().stream()
                    .map(this::summary)
                    .toList();
            return new Snapshot(items, proposals.pendingCount(), skills.getSkillsDir());
        }
    }

    @Override
    public int pendingProposalCount() {
        return proposals.pendingCount();
    }

    @Override
    public SkillDetail detail(String skillId) {
        synchronized (skillLock) {
            return detail(requiredSkill(skillId));
        }
    }

    @Override
    public SkillDetail create() {
        synchronized (skillLock) {
            return detail(skills.createSkill("新技能", "", "", true));
        }
    }

    @Override
    public SkillDetail update(UpdateSkillCommand command) {
        synchronized (skillLock) {
            Skill skill = requiredSkill(command.id());
            String previousContent = text(skill.getContent());
            if (!previousContent.equals(command.content())) {
                skills.archiveVersion(skill);
                skill.setVersion(SkillManager.bumpVersion(
                        skill.getVersion(), SkillManager.BumpLevel.PATCH));
                skill.setUserModified(true);
            }
            skill.setName(command.name());
            skill.setDescription(command.description());
            skill.setCategory(command.category());
            skill.setTags(new ArrayList<>(command.tags()));
            skill.setContent(command.content());
            skill.setEnabled(command.enabled());
            skills.updateSkill(skill);
            return detail(requiredSkill(command.id()));
        }
    }

    @Override
    public void delete(String skillId) {
        synchronized (skillLock) {
            Skill skill = requiredSkill(skillId);
            Path directory = skill.getDirectory();
            skills.deleteSkill(skillId);
            if (skills.getSkill(skillId) != null || Files.exists(directory)) {
                throw new ConflictException("技能目录未能完整删除: " + directory);
            }
        }
    }

    @Override
    public SkillDetail rollback(String skillId, String version) {
        synchronized (skillLock) {
            requiredSkill(skillId);
            if (!skills.rollback(skillId, version)) {
                throw new ConflictException("无法回滚技能到 v" + version);
            }
            return detail(requiredSkill(skillId));
        }
    }

    @Override
    public ScriptDocument readScript(String skillId, String fileName) {
        synchronized (skillLock) {
            Path script = requiredScript(requiredSkill(skillId), fileName);
            try {
                return new ScriptDocument(fileName, Files.readString(script, StandardCharsets.UTF_8));
            } catch (IOException failure) {
                throw rejected("读取脚本失败", failure);
            }
        }
    }

    @Override
    public ScriptDocument createScript(String skillId, String fileName) {
        synchronized (skillLock) {
            Skill skill = requiredSkill(skillId);
            Path scriptsDirectory = safeScriptsDirectory(skill);
            Path target = scriptsDirectory.resolve(fileName).normalize();
            if (!target.startsWith(scriptsDirectory)) {
                throw new RejectedException("脚本路径越界");
            }
            String content = NEW_SCRIPT_HEADER.formatted(fileName);
            try {
                Files.createDirectories(scriptsDirectory);
                Files.writeString(target, content, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                return new ScriptDocument(fileName, content);
            } catch (FileAlreadyExistsException conflict) {
                throw new ConflictException("同名脚本已存在: " + fileName);
            } catch (IOException failure) {
                throw rejected("创建脚本失败", failure);
            }
        }
    }

    @Override
    public void saveScript(String skillId, String fileName, String content) {
        synchronized (skillLock) {
            Path script = requiredScript(requiredSkill(skillId), fileName);
            try {
                Files.writeString(script, content, StandardCharsets.UTF_8,
                        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            } catch (IOException failure) {
                throw rejected("保存脚本失败", failure);
            }
        }
    }

    @Override
    public SkillDetail deleteScript(String skillId, String fileName) {
        synchronized (skillLock) {
            Skill skill = requiredSkill(skillId);
            Path script = requiredScript(skill, fileName);
            try {
                Files.delete(script);
                return detail(skill);
            } catch (IOException failure) {
                throw rejected("删除脚本失败", failure);
            }
        }
    }

    @Override
    public ScriptReport checkScript(String code) {
        List<String> lines = JShellRunner.check(code);
        boolean success = !lines.isEmpty() && lines.getFirst().startsWith("结构检查通过");
        return new ScriptReport(success, false, 0,
                String.join("\n", lines), "", success ? List.of() : lines);
    }

    @Override
    public ScriptReport runScript(String skillId, String code, String arguments) {
        List<String> preamble;
        synchronized (skillLock) {
            preamble = JShellTools.buildPreamble(requiredSkill(skillId), arguments);
        }
        int timeout = settings.getJshellExecTimeoutSeconds();
        JShellRunner.ExecResult result = jshellRunner.run(code, preamble, timeout);
        return new ScriptReport(result.success(), result.timedOut(), timeout,
                result.output(), result.lastValue(), result.problems());
    }

    @Override
    public List<ProposalItem> proposals() {
        return proposals.pending().stream().map(LegacySkillManagementAdapter::proposal).toList();
    }

    @Override
    public void approveProposal(String proposalId) {
        ensurePendingProposal(proposalId);
        String error = proposals.approve(proposalId);
        if (error != null) throw new ConflictException(error);
        synchronized (skillLock) {
            skills.reload();
        }
    }

    @Override
    public void rejectProposal(String proposalId) {
        ensurePendingProposal(proposalId);
        proposals.reject(proposalId);
    }

    @Override
    public AutoCloseable subscribeToProposalChanges(Runnable listener) {
        return proposals.addPendingChangedListener(listener);
    }

    @Override
    public List<BundleItem> bundles() {
        synchronized (skillLock) {
            return skills.getBundles().stream().map(LegacySkillManagementAdapter::bundle).toList();
        }
    }

    @Override
    public List<BundleItem> saveBundle(BundleCommand command) {
        synchronized (skillLock) {
            List<SkillBundle> updated = new ArrayList<>(skills.getBundles());
            updated.removeIf(bundle -> command.originalName().equals(bundle.name)
                    || command.name().equals(bundle.name));
            updated.add(new SkillBundle(command.name(), command.description(),
                    new ArrayList<>(command.skills()), command.extraInstructions(), command.enabled()));
            skills.saveBundles(updated);
            return skills.getBundles().stream().map(LegacySkillManagementAdapter::bundle).toList();
        }
    }

    @Override
    public List<BundleItem> deleteBundle(String name) {
        synchronized (skillLock) {
            List<SkillBundle> updated = new ArrayList<>(skills.getBundles());
            boolean removed = updated.removeIf(bundle -> name.equals(bundle.name));
            if (!removed) throw new NotFoundException("技能包不存在: " + name);
            skills.saveBundles(updated);
            return skills.getBundles().stream().map(LegacySkillManagementAdapter::bundle).toList();
        }
    }

    @Override
    public ImportInspection inspectImport(Path source, ImportKind kind) {
        SkillInstaller.InspectionResult result = kind == ImportKind.DIRECTORY
                ? installer.inspectDirectory(source) : installer.inspectZip(source);
        if (!result.ok()) throw new RejectedException(result.message());
        return new ImportInspection(result.scripts());
    }

    @Override
    public ImportResult importSkill(Path source, ImportKind kind) {
        SkillInstaller.InstallResult result;
        synchronized (skillLock) {
            result = kind == ImportKind.DIRECTORY
                    ? installer.installFromDirectory(source, null)
                    : installer.installFromZip(source, null);
        }
        if (!result.ok()) throw new RejectedException(result.message());
        return new ImportResult(snapshot(), result.message(), result.installedDir());
    }

    @Override
    public Path skillsDirectory() {
        return skills.getSkillsDir();
    }

    @Override
    public Path skillDirectory(String skillId) {
        synchronized (skillLock) {
            return requiredSkill(skillId).getDirectory();
        }
    }

    private SkillDetail detail(Skill skill) {
        var stat = usage.peek(skill.getName());
        Usage usageSnapshot = stat == null ? Usage.empty() : new Usage(
                stat.routeHits.get(), stat.reads.get(), stat.samples(), stat.successRate());
        List<String> scripts = JShellTools.listScripts(skill).stream().sorted().toList();
        return new SkillDetail(
                skill.getId(), skill.getName(), text(skill.getDescription()), skill.getCategory(),
                skill.getTags(), text(skill.getContent()), skill.isEnabled(), skill.getVersion(),
                skill.getSource().getDisplayName(), skill.getSource() == SkillSource.AGENT,
                skill.isUserModified(), usageSnapshot, skills.listHistory(skill.getId()), scripts,
                directoryStructure(skill), skill.getDirectory());
    }

    private SkillSummary summary(Skill skill) {
        return new SkillSummary(skill.getId(), skill.getName(), text(skill.getDescription()),
                skill.getVersion(), skill.getSource().getDisplayName(), skill.getTags(),
                skill.getSource() == SkillSource.AGENT, skill.isEnabled());
    }

    private Skill requiredSkill(String id) {
        Skill skill = skills.getSkill(id);
        if (skill == null) throw new NotFoundException("技能不存在: " + id);
        return skill;
    }

    private static Path safeScriptsDirectory(Skill skill) {
        Path directory = Objects.requireNonNull(skill.getDirectory(), "技能目录")
                .toAbsolutePath().normalize();
        Path scripts = directory.resolve(Skill.SCRIPTS_DIR).normalize();
        if (!scripts.startsWith(directory) || !PathGuard.isInside(directory, scripts)) {
            throw new RejectedException("技能 scripts 目录越界");
        }
        return scripts;
    }

    private static Path requiredScript(Skill skill, String fileName) {
        Path script = JShellTools.resolveScript(skill, fileName);
        if (script == null) throw new NotFoundException("脚本不存在: " + fileName);
        return script;
    }

    private void ensurePendingProposal(String proposalId) {
        boolean exists = proposals.pending().stream().anyMatch(item -> proposalId.equals(item.id));
        if (!exists) throw new NotFoundException("待审提案不存在: " + proposalId);
    }

    private static ProposalItem proposal(SkillProposal proposal) {
        var request = proposal.request;
        if (request == null) {
            return new ProposalItem(proposal.id, "", "", "", "", false, proposal.createdAt);
        }
        return new ProposalItem(proposal.id, request.action, request.skillName, request.reason,
                request.previewText(), request.userModifiedWarning, proposal.createdAt);
    }

    private static BundleItem bundle(SkillBundle bundle) {
        return new BundleItem(bundle.name, bundle.description, bundle.skills,
                bundle.extraInstructions, bundle.enabled);
    }

    private static String directoryStructure(Skill skill) {
        StringBuilder value = new StringBuilder(skill.getId()).append("/\n  ├─ SKILL.md");
        if (skill.hasScripts()) value.append("\n  ├─ scripts/");
        if (skill.hasReferences()) value.append("\n  ├─ references/");
        if (skill.hasAssets()) value.append("\n  ├─ assets/");
        return value.append("\n\n可在技能目录中手动添加 scripts/、references/、assets/ 子目录")
                .toString();
    }

    private static RejectedException rejected(String action, Exception failure) {
        return new RejectedException(action + "：" + message(failure), failure);
    }

    private static String message(Throwable failure) {
        return failure.getMessage() == null ? failure.getClass().getSimpleName()
                : failure.getMessage();
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
