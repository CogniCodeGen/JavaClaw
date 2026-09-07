package com.javaclaw.extension.spi;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.WorkspaceId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AutomationExecutionPolicyPortTest {
    @Test
    void profileOptionOwnsExactReferenceAndNormalizesDisplayName() {
        AgentRoleRef reference = new AgentRoleRef("developer", 7);

        AutomationRoleOption option = new AutomationRoleOption(reference, "  开发智能体  ");

        assertEquals(reference, option.role());
        assertEquals("开发智能体", option.displayName());
    }

    @Test
    void profileOptionRejectsMissingReferenceAndBlankName() {
        AgentRoleRef reference = new AgentRoleRef("developer", 1);

        assertThrows(NullPointerException.class, () -> new AutomationRoleOption(null, "开发智能体"));
        assertThrows(NullPointerException.class, () -> new AutomationRoleOption(reference, null));
        assertThrows(IllegalArgumentException.class, () -> new AutomationRoleOption(reference, "  "));
    }

    @Test
    void freezeOnlyImplementationCannotSilentlyExposeEmptyCatalog() {
        AutomationExecutionPolicyPort freezeOnly = (workspaceId, profile, cancellation) -> null;

        assertThrows(IllegalStateException.class, () -> freezeOnly.roles(WorkspaceId.random()));
    }
}
