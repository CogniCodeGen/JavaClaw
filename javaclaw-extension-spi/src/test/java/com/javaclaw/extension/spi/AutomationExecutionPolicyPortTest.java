package com.javaclaw.extension.spi;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AgentProfileRef;
import com.javaclaw.api.WorkspaceId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AutomationExecutionPolicyPortTest {
    @Test
    void profileOptionOwnsExactReferenceAndNormalizesDisplayName() {
        AgentProfileRef reference = new AgentProfileRef("developer", 7);

        AutomationProfileOption option = new AutomationProfileOption(reference, "  开发智能体  ");

        assertEquals(reference, option.profile());
        assertEquals("开发智能体", option.displayName());
    }

    @Test
    void profileOptionRejectsMissingReferenceAndBlankName() {
        AgentProfileRef reference = new AgentProfileRef("developer", 1);

        assertThrows(NullPointerException.class, () -> new AutomationProfileOption(null, "开发智能体"));
        assertThrows(NullPointerException.class, () -> new AutomationProfileOption(reference, null));
        assertThrows(IllegalArgumentException.class, () -> new AutomationProfileOption(reference, "  "));
    }

    @Test
    void freezeOnlyImplementationCannotSilentlyExposeEmptyCatalog() {
        AutomationExecutionPolicyPort freezeOnly = (workspaceId, profile, cancellation) -> null;

        assertThrows(IllegalStateException.class, () -> freezeOnly.profiles(WorkspaceId.random()));
    }
}
