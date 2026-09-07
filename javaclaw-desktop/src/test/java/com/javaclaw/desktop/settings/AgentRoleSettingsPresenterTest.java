package com.javaclaw.desktop.settings;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AgentRole;
import com.javaclaw.api.PermissionConstraint;
import com.javaclaw.api.RoleLifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRoleSettingsPresenterTest {
    @Test
    void 新角色可继承模型并只保存指令和能力收窄() {
        TestCoreSettingsGateway gateway = new TestCoreSettingsGateway();
        AgentRoleSettingsPresenter presenter = new AgentRoleSettingsPresenter(gateway);
        presenter.reload();
        presenter.createDraft();
        presenter.updateDraft(draft("reviewer"));
        presenter.save();
        AgentRole saved = presenter.state().selected().orElseThrow();
        assertTrue(saved.spec().model().isEmpty());
        assertTrue(saved.spec().narrowing().capabilities().isEmpty());
        assertEquals(PermissionConstraint.READ_ONLY, saved.spec().permissionConstraint());
        assertEquals(Map.of("example.note", "保留扩展"), saved.spec().extensions());
        assertFalse(presenter.state().dirty());
        assertTrue(gateway.workspaceSettings.binding.isEmpty(), "创建角色不能改写执行默认值或授予权限");
    }

    @Test
    void 内置角色拒绝编辑归档且必须复制成自定义版本() {
        TestCoreSettingsGateway gateway = new TestCoreSettingsGateway();
        AgentRole builtin = new AgentRole(
                "default",
                1,
                RoleLifecycle.ACTIVE,
                draft("default").toSpec(),
                true,
                com.javaclaw.desktop.DesktopTestFixtures.NOW,
                com.javaclaw.desktop.DesktopTestFixtures.NOW);
        gateway.profiles.add(builtin);
        AgentRoleSettingsPresenter presenter = new AgentRoleSettingsPresenter(gateway);
        presenter.reload();
        presenter.updateDraft(draft("unexpected"));
        presenter.save();
        presenter.archive();
        assertEquals(builtin, gateway.profiles.getFirst());
        assertFalse(presenter.state().dirty());
        presenter.cloneSelected("custom", "自定义角色");
        AgentRole cloned = presenter.state().selected().orElseThrow();
        assertEquals("custom", cloned.id());
        assertFalse(cloned.builtin());
        assertEquals(builtin.spec().developerInstructions(), cloned.spec().developerInstructions());
        assertEquals(builtin.spec().permissionConstraint(), cloned.spec().permissionConstraint());
        assertEquals(builtin.spec().extensions(), cloned.spec().extensions());
    }

    @Test
    void 空能力集合表示禁用而星号表示继承() {
        AgentRoleDraft inherited = draft("inherited");
        assertTrue(inherited.toSpec().narrowing().capabilities().isEmpty());
        AgentRoleDraft disabled = new AgentRoleDraft(
                "disabled",
                "禁用工具",
                "",
                "",
                Optional.empty(),
                Optional.empty(),
                "",
                "",
                PermissionConstraint.INHERIT,
                RoleLifecycle.ACTIVE,
                Map.of());
        assertTrue(disabled.toSpec().narrowing().capabilities().orElseThrow().isEmpty());
        assertTrue(disabled.toSpec().narrowing().skills().orElseThrow().isEmpty());
    }

    private static AgentRoleDraft draft(String id) {
        return new AgentRoleDraft(
                id,
                "只读审阅",
                "检查具体问题",
                "先检查事实，再区分推断与未知。",
                Optional.empty(),
                Optional.empty(),
                "*",
                "*",
                PermissionConstraint.READ_ONLY,
                RoleLifecycle.ACTIVE,
                Map.of("example.note", "保留扩展"));
    }
}
