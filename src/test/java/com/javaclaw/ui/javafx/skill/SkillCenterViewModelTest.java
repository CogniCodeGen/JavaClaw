package com.javaclaw.ui.javafx.skill;

import com.javaclaw.application.skill.SkillManagementApplicationService.SkillSummary;
import com.javaclaw.application.skill.SkillManagementApplicationService.Snapshot;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SkillCenterViewModelTest {

    @Test
    void clearsSelectionWhenSnapshotNoLongerContainsSkill() {
        SkillCenterViewModel model = new SkillCenterViewModel();
        model.selectSkill("skill-1");
        model.apply(snapshot("skill-1"));
        assertEquals(SkillCenterViewModel.Panel.EDITOR, model.panelProperty().get());

        model.apply(snapshot("skill-2"));

        assertEquals("", model.selectedSkillIdProperty().get());
        assertEquals(SkillCenterViewModel.Panel.EMPTY, model.panelProperty().get());
    }

    @Test
    void nonEditorPanelsClearSkillSelection() {
        SkillCenterViewModel model = new SkillCenterViewModel();
        model.selectSkill("skill-1");
        model.show(SkillCenterViewModel.Panel.PROPOSALS);
        assertEquals("", model.selectedSkillIdProperty().get());
        assertEquals(SkillCenterViewModel.Panel.PROPOSALS, model.panelProperty().get());
    }

    private static Snapshot snapshot(String id) {
        return new Snapshot(List.of(new SkillSummary(
                id, id, "", "1.0.0", "用户", List.of(), false, true)),
                0, Path.of("skills").toAbsolutePath());
    }
}
