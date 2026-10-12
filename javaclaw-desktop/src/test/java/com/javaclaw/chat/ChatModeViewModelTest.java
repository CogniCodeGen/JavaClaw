package com.javaclaw.chat;

import com.javaclaw.api.conversation.PlanProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ChatModeViewModelTest {

    @Test
    void keepsModeWorkflowAndPlanStateWithoutServicesOrNodes() {
        ChatModeViewModel model = new ChatModeViewModel();
        assertEquals("chat", model.selectedModeIdProperty().get());
        assertEquals(PlanProfile.AUTO, model.planProfileProperty().get());
        assertNull(model.selectedWorkflowIdProperty().get());

        model.modes().add(new ChatModeViewModel.ModeChoice("plan", "研讨", "多智能体"));
        model.workflows().add(new ChatModeViewModel.WorkflowChoice("release", "发布流程"));
        model.selectedModeIdProperty().set("plan");
        model.selectedWorkflowIdProperty().set("release");
        model.planProfileProperty().set(PlanProfile.DEEP);

        assertEquals("研讨", model.modes().getFirst().toString());
        assertEquals("发布流程", model.workflows().getFirst().toString());
        assertEquals("plan", model.selectedModeIdProperty().get());
        assertEquals("release", model.selectedWorkflowIdProperty().get());
        assertEquals(PlanProfile.DEEP, model.planProfileProperty().get());
    }
}
