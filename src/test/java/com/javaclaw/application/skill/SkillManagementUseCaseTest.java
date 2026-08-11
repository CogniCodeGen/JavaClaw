package com.javaclaw.application.skill;

import com.javaclaw.application.error.ValidationException;
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
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SkillManagementUseCaseTest {

    private final FakePort port = new FakePort();
    private final SkillManagementUseCase useCase = new SkillManagementUseCase(port);

    @Test
    void normalizesEditorAndBundleCommands() {
        useCase.update(new UpdateSkillCommand(
                " skill-1 ", " 技能一 ", " 描述 ", " 编码 ",
                List.of(" java ", "", "java", " test "), " body \n", true));

        assertEquals("skill-1", port.update.id());
        assertEquals("技能一", port.update.name());
        assertEquals(List.of("java", "test"), port.update.tags());
        assertEquals(" body \n", port.update.content());

        useCase.saveBundle(new BundleCommand(
                " old ", " bundle ", " desc ", List.of(" one ", "one", "two"),
                " instructions\n", true));
        assertEquals("old", port.bundle.originalName());
        assertEquals(List.of("one", "two"), port.bundle.skills());
        assertEquals(" instructions\n", port.bundle.extraInstructions());
    }

    @Test
    void rejectsBlankIdentifiersAndUnsafeScriptNames() {
        assertThrows(ValidationException.class, () -> useCase.detail(" "));
        assertThrows(ValidationException.class,
                () -> useCase.createScript("skill-1", "../bad.jsh"));
        assertThrows(ValidationException.class,
                () -> useCase.createScript("skill-1", "script.txt"));
        assertThrows(ValidationException.class, () -> useCase.checkScript(" \n"));
        assertThrows(ValidationException.class,
                () -> useCase.runScript("", "1 + 1", ""));
    }

    @Test
    void returnsFreshSnapshotsAfterMutations() {
        assertEquals("skill-1", useCase.create().detail().id());
        assertEquals("已删除技能", useCase.delete("skill-1").message());
        assertEquals("已回滚到 v1.0.0", useCase.rollback("skill-1", "1.0.0").message());
        assertEquals("提案已采纳", useCase.approveProposal("proposal-1").message());
        assertEquals("提案已拒绝", useCase.rejectProposal("proposal-1").message());
    }

    @Test
    void delegatesScriptImportDirectoryAndSubscriptionCapabilities() throws Exception {
        assertEquals(1, useCase.pendingProposalCount());
        assertEquals("skill-1", useCase.detail(" skill-1 ").id());
        assertEquals("main.JAVA", useCase.readScript("skill-1", "main.JAVA").fileName());
        assertEquals("已创建 helper.jsh",
                useCase.createScript("skill-1", "helper.jsh").message());
        useCase.saveScript("skill-1", "helper.jsh", null);
        assertEquals("", port.savedScriptContent);
        assertEquals("已删除 helper.jsh",
                useCase.deleteScript("skill-1", "helper.jsh").message());
        assertNotNull(useCase.checkScript("1 + 1"));
        assertNotNull(useCase.runScript("skill-1", "1 + 1", null));
        assertEquals(1, useCase.proposals().size());
        try (AutoCloseable subscription = useCase.subscribeToProposalChanges(() -> { })) {
            assertNotNull(subscription);
        }
        assertEquals(List.of(), useCase.bundles());
        assertEquals(List.of(), useCase.deleteBundle(" bundle "));

        Path source = Path.of(".");
        assertNotNull(useCase.inspectImport(source, ImportKind.DIRECTORY));
        assertNotNull(useCase.importSkill(source, ImportKind.DIRECTORY));
        assertEquals(port.directory, useCase.skillsDirectory());
        assertEquals(port.directory.resolve("skill-1"), useCase.skillDirectory("skill-1"));
    }

    @Test
    void normalizesNullCollectionsAndRejectsMissingDependencies() {
        useCase.update(new UpdateSkillCommand(
                "skill-1", "Skill", null, null, null, null, false));
        assertEquals(List.of(), port.update.tags());
        assertEquals("", port.update.content());
        useCase.saveBundle(new BundleCommand(null, "bundle", null, null, null, false));
        assertEquals(List.of(), port.bundle.skills());
        assertEquals("", port.bundle.extraInstructions());

        assertThrows(ValidationException.class,
                () -> useCase.createScript("skill-1", "folder\\bad.java"));
        assertThrows(ValidationException.class, () -> useCase.inspectImport(null, ImportKind.ZIP));
        assertThrows(NullPointerException.class, () -> useCase.inspectImport(Path.of("."), null));
        assertThrows(NullPointerException.class, () -> useCase.importSkill(Path.of("."), null));
        assertThrows(NullPointerException.class, () -> useCase.subscribeToProposalChanges(null));
        assertThrows(NullPointerException.class, () -> useCase.update(null));
        assertThrows(NullPointerException.class, () -> useCase.saveBundle(null));
        assertThrows(NullPointerException.class, () -> new SkillManagementUseCase(null));
    }

    private static final class FakePort implements SkillManagementPort {
        private final Path directory = Path.of("skills").toAbsolutePath();
        private UpdateSkillCommand update;
        private BundleCommand bundle;
        private String savedScriptContent;

        @Override public Snapshot snapshot() {
            return new Snapshot(List.of(new SkillSummary(
                    "skill-1", "技能一", "", "1.0.0", "用户", List.of(), false, true)),
                    1, directory);
        }
        @Override public int pendingProposalCount() { return 1; }
        @Override public SkillDetail detail(String skillId) { return detailValue(); }
        @Override public SkillDetail create() { return detailValue(); }
        @Override public SkillDetail update(UpdateSkillCommand command) {
            update = command;
            return detailValue();
        }
        @Override public void delete(String skillId) { }
        @Override public SkillDetail rollback(String skillId, String version) { return detailValue(); }
        @Override public ScriptDocument readScript(String skillId, String fileName) {
            return new ScriptDocument(fileName, "");
        }
        @Override public ScriptDocument createScript(String skillId, String fileName) {
            return new ScriptDocument(fileName, "");
        }
        @Override public void saveScript(String skillId, String fileName, String content) {
            savedScriptContent = content;
        }
        @Override public SkillDetail deleteScript(String skillId, String fileName) { return detailValue(); }
        @Override public ScriptReport checkScript(String code) {
            return new ScriptReport(true, false, 0, "ok", "", List.of());
        }
        @Override public ScriptReport runScript(String skillId, String code, String arguments) {
            return new ScriptReport(true, false, 60, "", "2", List.of());
        }
        @Override public List<ProposalItem> proposals() {
            return List.of(new ProposalItem("proposal-1", "edit", "技能一", "", "", false, 1));
        }
        @Override public void approveProposal(String proposalId) { }
        @Override public void rejectProposal(String proposalId) { }
        @Override public AutoCloseable subscribeToProposalChanges(Runnable listener) { return () -> { }; }
        @Override public List<BundleItem> bundles() { return List.of(); }
        @Override public List<BundleItem> saveBundle(BundleCommand command) {
            bundle = command;
            return List.of();
        }
        @Override public List<BundleItem> deleteBundle(String name) { return List.of(); }
        @Override public ImportInspection inspectImport(Path source, ImportKind kind) {
            return new ImportInspection(List.of());
        }
        @Override public ImportResult importSkill(Path source, ImportKind kind) {
            return new ImportResult(snapshot(), "ok", source);
        }
        @Override public Path skillsDirectory() { return directory; }
        @Override public Path skillDirectory(String skillId) { return directory.resolve(skillId); }

        private SkillDetail detailValue() {
            return new SkillDetail("skill-1", "技能一", "", "", List.of(), "", true,
                    "1.0.0", "用户", false, false, null, List.of("1.0.0"),
                    List.of(), "skill-1/", directory.resolve("skill-1"));
        }
    }
}
