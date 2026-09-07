package com.javaclaw.server.role;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AgentRole;
import com.javaclaw.api.PermissionConstraint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuiltinAgentRolesTest {
    @Test
    void releaseContainsFourImmutableRolesWithAGeneralFallbackAndACodeEnforcedReadOnlyExplorer() {
        List<AgentRole> roles = new BuiltinAgentRoles().list();
        assertEquals(
                List.of("default", "worker", "explorer", "software-engineer"),
                roles.stream().map(AgentRole::id).toList());
        assertTrue(roles.stream().allMatch(AgentRole::builtin));
        assertTrue(roles.getFirst().spec().developerInstructions().contains("不要假定请求一定是编程任务"));
        assertEquals(PermissionConstraint.READ_ONLY, roles.get(2).spec().permissionConstraint());
        assertTrue(roles.get(1).spec().developerInstructions().contains("不得覆盖他人的修改"));
        assertTrue(roles.get(3).spec().developerInstructions().contains("UI 布局"));
        assertTrue(BuiltinAgentRoles.contains("explorer"));
        assertFalse(BuiltinAgentRoles.contains("custom"));
        assertEquals(roles, new BuiltinAgentRoles().list());
    }
}
