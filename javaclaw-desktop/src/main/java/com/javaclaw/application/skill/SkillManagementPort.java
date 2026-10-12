package com.javaclaw.application.skill;

import com.javaclaw.application.skill.SkillManagementApplicationService.BundleCommand;
import com.javaclaw.application.skill.SkillManagementApplicationService.BundleItem;
import com.javaclaw.application.skill.SkillManagementApplicationService.ImportInspection;
import com.javaclaw.application.skill.SkillManagementApplicationService.ImportKind;
import com.javaclaw.application.skill.SkillManagementApplicationService.ImportResult;
import com.javaclaw.application.skill.SkillManagementApplicationService.ProposalItem;
import com.javaclaw.application.skill.SkillManagementApplicationService.ScriptDocument;
import com.javaclaw.application.skill.SkillManagementApplicationService.ScriptReport;
import com.javaclaw.application.skill.SkillManagementApplicationService.SkillDetail;
import com.javaclaw.application.skill.SkillManagementApplicationService.Snapshot;
import com.javaclaw.application.skill.SkillManagementApplicationService.UpdateSkillCommand;

import java.nio.file.Path;
import java.util.List;

/** 技能文件、提案、统计、技能包和脚本执行的应用层端口。 */
public interface SkillManagementPort {

    Snapshot snapshot();

    int pendingProposalCount();

    SkillDetail detail(String skillId);

    SkillDetail create();

    SkillDetail update(UpdateSkillCommand command);

    void delete(String skillId);

    SkillDetail rollback(String skillId, String version);

    ScriptDocument readScript(String skillId, String fileName);

    ScriptDocument createScript(String skillId, String fileName);

    void saveScript(String skillId, String fileName, String content);

    SkillDetail deleteScript(String skillId, String fileName);

    ScriptReport checkScript(String code);

    ScriptReport runScript(String skillId, String code, String arguments);

    List<ProposalItem> proposals();

    void approveProposal(String proposalId);

    void rejectProposal(String proposalId);

    AutoCloseable subscribeToProposalChanges(Runnable listener);

    List<BundleItem> bundles();

    List<BundleItem> saveBundle(BundleCommand command);

    List<BundleItem> deleteBundle(String name);

    ImportInspection inspectImport(Path source, ImportKind kind);

    ImportResult importSkill(Path source, ImportKind kind);

    Path skillsDirectory();

    Path skillDirectory(String skillId);
}
