package com.javaclaw.ui.javafx.skill;

import com.javaclaw.application.skill.SkillManagementApplicationService;

import java.nio.file.Path;
import java.util.List;

/** Mutable fake kept separate so controller behavior tests remain focused on UI scenarios. */
final class FakeSkillManagementService implements SkillManagementApplicationService {
    final Path root = Path.of("skills-test").toAbsolutePath();
    volatile SkillDetail detail = detail("skill-1", List.of("one.jsh"), Usage.empty(),
            true, false, false);
    volatile OperationResult createResult = result(detail, "已创建");
    volatile OperationResult updateResult = result(detail, "已保存");
    volatile ImportInspection inspection = new ImportInspection(List.of());
    volatile Path importInstalledDirectory = root.resolve("skill-1");
    volatile ScriptReport checkReport = new ScriptReport(
            true, false, 0, "ok", "", List.of());
    volatile ScriptReport runReport = new ScriptReport(
            true, false, 0, "", "2", List.of());
    volatile RuntimeException snapshotFailure;
    volatile RuntimeException createFailure;
    volatile RuntimeException updateFailure;
    volatile RuntimeException inspectFailure;
    volatile RuntimeException importFailure;
    volatile RuntimeException saveScriptFailure;
    volatile RuntimeException readFailure;
    volatile RuntimeException runFailure;
    volatile RuntimeException bundleFailure;
    volatile RuntimeException proposalFailure;
    volatile Runnable proposalListener;
    volatile boolean subscriptionClosed;
    volatile boolean subscriptionCloseThrows;
    volatile int snapshotCalls;
    volatile int detailCalls;
    volatile int createCalls;
    volatile int updateCalls;
    volatile int rollbackCalls;
    volatile int deleteCalls;
    volatile int readCalls;
    volatile int createScriptCalls;
    volatile int saveScriptCalls;
    volatile int deleteScriptCalls;
    volatile int checkCalls;
    volatile int runCalls;
    volatile int proposalLoads;
    volatile int approveCalls;
    volatile int rejectCalls;
    volatile int bundleLoads;
    volatile int saveBundleCalls;
    volatile int deleteBundleCalls;
    volatile int inspectCalls;
    volatile int importCalls;
    volatile UpdateSkillCommand lastUpdate;
    volatile BundleCommand lastBundle;

    Snapshot snapshotValue() {
        return new Snapshot(List.of(new SkillSummary(
                detail.id(), detail.name(), detail.description(), detail.version(),
                detail.source(), detail.tags(), detail.agentCreated(), detail.enabled())), 2, root);
    }

    OperationResult result(SkillDetail value, String message) {
        return new OperationResult(snapshotValue(), value, message);
    }

    SkillDetail detailWithScripts(List<String> scripts) {
        return detail(detail.id(), scripts, detail.usage(), detail.enabled(),
                detail.agentCreated(), detail.userModified());
    }

    SkillDetail detailWithUsage(Usage usage, boolean agentCreated, boolean userModified) {
        return detail(detail.id(), detail.scripts(), usage, detail.enabled(),
                agentCreated, userModified);
    }

    SkillDetail otherDetail() {
        return detail("other", List.of(), Usage.empty(), true, false, false);
    }

    private static SkillDetail detail(
            String id, List<String> scripts, Usage usage, boolean enabled,
            boolean agentCreated, boolean userModified) {
        Path directory = Path.of("skills-test", id).toAbsolutePath();
        return new SkillDetail(id, "测试技能", "描述", "测试", List.of("java"),
                "正文", enabled, "1.0.0", "用户", agentCreated, userModified, usage,
                List.of("0.9.0"), scripts, id + "/\n  ├─ SKILL.md", directory);
    }

    @Override
    public Snapshot snapshot() {
        snapshotCalls++;
        if (snapshotFailure != null) throw snapshotFailure;
        return snapshotValue();
    }

    @Override public int pendingProposalCount() { return 2; }
    @Override public SkillDetail detail(String skillId) { detailCalls++; return detail; }

    @Override
    public OperationResult create() {
        createCalls++;
        if (createFailure != null) throw createFailure;
        return createResult;
    }

    @Override
    public OperationResult update(UpdateSkillCommand command) {
        updateCalls++;
        lastUpdate = command;
        if (updateFailure != null) throw updateFailure;
        return updateResult;
    }

    @Override
    public OperationResult delete(String skillId) {
        deleteCalls++;
        return new OperationResult(new Snapshot(List.of(), 0, root), null, "已删除");
    }

    @Override
    public OperationResult rollback(String skillId, String version) {
        rollbackCalls++;
        return result(detail, "已回滚");
    }

    @Override
    public ScriptDocument readScript(String skillId, String fileName) {
        readCalls++;
        if (readFailure != null) throw readFailure;
        return new ScriptDocument(fileName, "loaded: " + fileName);
    }

    @Override
    public ScriptMutation createScript(String skillId, String fileName) {
        createScriptCalls++;
        SkillDetail updated = detailWithScripts(List.of("one.jsh", fileName));
        return new ScriptMutation(updated, new ScriptDocument(fileName, ""), "已创建");
    }

    @Override
    public void saveScript(String skillId, String fileName, String content) {
        saveScriptCalls++;
        if (saveScriptFailure != null) throw saveScriptFailure;
    }

    @Override
    public ScriptMutation deleteScript(String skillId, String fileName) {
        deleteScriptCalls++;
        return new ScriptMutation(detailWithScripts(List.of()), null, "已删除");
    }

    @Override public ScriptReport checkScript(String code) { checkCalls++; return checkReport; }

    @Override
    public ScriptReport runScript(String skillId, String code, String arguments) {
        runCalls++;
        if (runFailure != null) throw runFailure;
        return runReport;
    }

    @Override
    public List<ProposalItem> proposals() {
        proposalLoads++;
        if (proposalFailure != null) throw proposalFailure;
        return proposalsValue();
    }

    @Override
    public ReviewResult approveProposal(String proposalId) {
        approveCalls++;
        if (proposalFailure != null) throw proposalFailure;
        return new ReviewResult(List.of(), snapshotValue(), "已采纳");
    }

    @Override
    public ReviewResult rejectProposal(String proposalId) {
        rejectCalls++;
        if (proposalFailure != null) throw proposalFailure;
        return new ReviewResult(List.of(), snapshotValue(), "已拒绝");
    }

    @Override
    public AutoCloseable subscribeToProposalChanges(Runnable listener) {
        proposalListener = listener;
        return () -> {
            subscriptionClosed = true;
            if (subscriptionCloseThrows) throw new IllegalStateException("close failed");
        };
    }

    @Override
    public List<BundleItem> bundles() {
        bundleLoads++;
        if (bundleFailure != null) throw bundleFailure;
        return bundlesValue();
    }

    @Override
    public List<BundleItem> saveBundle(BundleCommand command) {
        saveBundleCalls++;
        lastBundle = command;
        if (bundleFailure != null) throw bundleFailure;
        return List.of(new BundleItem(command.name(), command.description(), command.skills(),
                command.extraInstructions(), command.enabled()));
    }

    @Override
    public List<BundleItem> deleteBundle(String name) {
        deleteBundleCalls++;
        if (bundleFailure != null) throw bundleFailure;
        return List.of();
    }

    @Override
    public ImportInspection inspectImport(Path source, ImportKind kind) {
        inspectCalls++;
        if (inspectFailure != null) throw inspectFailure;
        return inspection;
    }

    @Override
    public ImportResult importSkill(Path source, ImportKind kind) {
        importCalls++;
        if (importFailure != null) throw importFailure;
        return new ImportResult(snapshotValue(), "已导入", importInstalledDirectory);
    }

    @Override public Path skillsDirectory() { return root; }
    @Override public Path skillDirectory(String skillId) { return root.resolve(skillId); }

    private List<ProposalItem> proposalsValue() {
        return List.of(
                new ProposalItem("p1", "edit", "测试技能", "优化", "preview", false, 1),
                new ProposalItem("p2", "create", "新技能", "", "preview", true, 2));
    }

    private List<BundleItem> bundlesValue() {
        return List.of(
                new BundleItem("测试包", "描述", List.of("测试技能"), "", true),
                new BundleItem("禁用包", "", List.of(), "", false));
    }

    void emitProposalChange() {
        Runnable listener = proposalListener;
        if (listener != null) listener.run();
    }
}
