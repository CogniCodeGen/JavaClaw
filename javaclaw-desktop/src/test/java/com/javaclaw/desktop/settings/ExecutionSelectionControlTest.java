package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import javafx.scene.control.ComboBox;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.AgentRole;
import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.ApprovalPolicy;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ReasoningPreference;
import com.javaclaw.api.RoleLifecycle;
import com.javaclaw.desktop.FxTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionSelectionControlTest {
    @Test
    void 默认通用角色与模型权限独立且模型锁定有说明() {
        FxTestSupport.run(() -> {
            TestCoreSettingsGateway gateway = new TestCoreSettingsGateway();
            AgentRole role = new AgentRole(
                    "default",
                    1,
                    RoleLifecycle.ACTIVE,
                    TestCoreSettingsGateway.profileSpec(),
                    true,
                    com.javaclaw.desktop.DesktopTestFixtures.NOW,
                    com.javaclaw.desktop.DesktopTestFixtures.NOW);
            ExecutionSelectionControl control = new ExecutionSelectionControl();
            control.setCatalog(List.of(role), gateway.providers, gateway.permissions);
            control.selectDefaultRole();
            assertEquals("default", control.value().role().orElseThrow().id());
            assertTrue(control.value().permissionProfile().isEmpty());
            assertTrue(control.value().provider().isEmpty());
            ComboBox<?> model = (ComboBox<?>) control.lookup("#executionModel");
            assertTrue(model.isDisabled());
            assertTrue(model.getTooltip().getText().contains("由 Agent 锁定"));
            control.setValue(ExecutionOverrides.empty());
            assertFalse(model.isDisabled());
        });
    }

    @Test
    void 选择模型不会自动创建角色或改写权限() {
        FxTestSupport.run(() -> {
            TestCoreSettingsGateway gateway = new TestCoreSettingsGateway();
            ExecutionSelectionControl control = new ExecutionSelectionControl();
            control.setCatalog(List.of(), gateway.providers, gateway.permissions);
            ComboBox<ProviderRef> model = combo(control, "executionModel");
            ComboBox<PermissionProfile> permissions = combo(control, "executionPermission");
            permissions.setValue(gateway.permissions.getFirst());
            var permission = control.value().permissionProfile();
            model.setValue(model.getItems().getFirst());
            assertEquals(permission, control.value().permissionProfile());
            assertTrue(control.value().role().isEmpty());
            assertTrue(control.value().provider().isPresent());
            assertTrue(gateway.profiles.isEmpty());
        });
    }

    @Test
    void 修改推理和模型保留目录中不可用的精确引用及隐藏限制() {
        FxTestSupport.run(() -> {
            TestCoreSettingsGateway gateway = new TestCoreSettingsGateway();
            ExecutionOverrides selected = new ExecutionOverrides(
                    Optional.of(new AgentRoleRef("removed-role", 7)),
                    Optional.empty(),
                    Optional.of(new PermissionProfileRef("removed-permission", 4)),
                    Optional.of(ApprovalPolicy.EVERY_CALL),
                    Optional.empty(),
                    Optional.of(Set.of("core/tool/search")),
                    Optional.empty());
            ExecutionSelectionControl control = new ExecutionSelectionControl();
            control.setCatalog(List.of(), gateway.providers, gateway.permissions);
            control.setValue(selected);
            ComboBox<ReasoningPreference> reasoning = combo(control, "executionReasoning");
            reasoning.setValue(ReasoningPreference.HIGH);
            ComboBox<ProviderRef> model = combo(control, "executionModel");
            model.setValue(model.getItems().getFirst());

            assertEquals(selected.role(), control.value().role());
            assertEquals(selected.permissionProfile(), control.value().permissionProfile());
            assertEquals(selected.approvalPolicy(), control.value().approvalPolicy());
            assertEquals(selected.visibleCapabilities(), control.value().visibleCapabilities());
            assertEquals(Optional.of(ReasoningPreference.HIGH), control.value().reasoning());
            assertTrue(combo(control, "executionRole").getPromptText().contains("removed-role@7 不可用"));
            assertTrue(combo(control, "executionPermission").getPromptText().contains("removed-permission@4 不可用"));
        });
    }

    @Test
    void 上级角色锁定不写入显式覆盖且无效显式角色不能冒用上级锁定() {
        FxTestSupport.run(() -> {
            ExecutionSelectionControl control = new ExecutionSelectionControl();
            AgentRole inherited = new AgentRole(
                    "inherited",
                    3,
                    RoleLifecycle.ACTIVE,
                    TestCoreSettingsGateway.profileSpec(),
                    true,
                    com.javaclaw.desktop.DesktopTestFixtures.NOW,
                    com.javaclaw.desktop.DesktopTestFixtures.NOW);
            control.showInheritedRole(Optional.of(inherited));
            assertTrue(combo(control, "executionModel").isDisabled());
            assertTrue(control.value().role().isEmpty());
            control.setValue(new ExecutionOverrides(
                    Optional.of(new AgentRoleRef("unavailable", 2)),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty()));
            assertFalse(combo(control, "executionModel").isDisabled());
            control.setValue(ExecutionOverrides.empty());
            assertTrue(combo(control, "executionModel").isDisabled());
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> ComboBox<T> combo(ExecutionSelectionControl control, String id) {
        return (ComboBox<T>) control.lookup("#" + id);
    }
}
