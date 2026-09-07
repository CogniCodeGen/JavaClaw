package com.javaclaw.builtin.extensions;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.ApprovalPolicy;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ReasoningPreference;
import com.javaclaw.extension.spi.ViewQueryRequest;
import com.javaclaw.extension.spi.ViewQueryResult;
import com.javaclaw.extension.spi.ViewSchema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutomationSelectionViewTest {
    @Test
    void 模型与权限目录提供独立精确引用且拒绝错误来源() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        var started = support.start(new PlanExtension());
        ViewQueryResult providers = query(support, started, AutomationSelectionView.PROVIDERS, "providers", "");
        ViewQueryResult permissions = query(support, started, AutomationSelectionView.PERMISSIONS, "permissions", "");
        Map<?, ?> model = support.payloads.decode(providers.rows().getFirst(), Map.class);
        Map<?, ?> permission = support.payloads.decode(permissions.rows().getFirst(), Map.class);

        assertTrue(model.containsKey("provider"));
        assertTrue(permission.containsKey("permissionProfile"));
        assertEquals(1, providers.revision());
        assertTrue(query(support, started, AutomationSelectionView.PROVIDERS, "providers", "provider/model")
                .rows()
                .isEmpty());
        assertThrows(
                IllegalArgumentException.class,
                () -> query(support, started, AutomationSelectionView.PROVIDERS, "wrong", ""));
        assertThrows(
                IllegalArgumentException.class,
                () -> query(support, started, AutomationSelectionView.PERMISSIONS, "permissions", "stale"));
    }

    @Test
    void 表单选择转换保留角色模型权限审批与预算的独立性() {
        AgentRoleRef role = new AgentRoleRef("worker", 4);
        ProviderRef provider = new ProviderRef("model-server", 2, "model");
        PermissionProfileRef permission = new PermissionProfileRef("restricted", 3);
        var request = new AutomationFormContracts.StartPayload(
                        "definition",
                        role,
                        provider,
                        permission,
                        ApprovalPolicy.EVERY_CALL,
                        ReasoningPreference.HIGH,
                        2,
                        1000,
                        300,
                        10)
                .toRequest();

        assertEquals(role, request.execution().role().orElseThrow());
        assertEquals(provider, request.execution().provider().orElseThrow());
        assertEquals(permission, request.execution().permissionProfile().orElseThrow());
        assertEquals(
                ApprovalPolicy.EVERY_CALL, request.execution().approvalPolicy().orElseThrow());
        assertEquals(ReasoningPreference.HIGH, request.execution().reasoning().orElseThrow());
        assertEquals(2, request.budget().maximumTurns());
        assertEquals(
                List.of("approvalPolicy", "reasoning"),
                AutomationSelectionView.fields("editor").stream()
                        .map(field -> field.name())
                        .toList());
        assertEquals(2, AutomationSelectionView.tables().size());
        assertTrue(AutomationSelectionView.tables().stream().allMatch(ViewSchema.Table.class::isInstance));
    }

    @Test
    void 定时表单复用原生目标和预算校验并保留独立配置() {
        AgentRoleRef role = new AgentRoleRef("worker", 4);
        ProviderRef provider = new ProviderRef("model-server", 2, "model");
        PermissionProfileRef permission = new PermissionProfileRef("restricted", 3);
        var request = new ScheduleFormSaveRequest(
                        "schedule",
                        "定时执行",
                        true,
                        com.javaclaw.builtin.contracts.ScheduleContracts.TimingKind.FIXED_INTERVAL,
                        com.javaclaw.builtin.contracts.ScheduleContracts.TargetKind.TURN_TEMPLATE,
                        com.javaclaw.builtin.contracts.ScheduleManagementContracts.TURN_TEMPLATE_EXTENSION,
                        com.javaclaw.builtin.contracts.ScheduleManagementContracts.TURN_TEMPLATE_ID,
                        0,
                        "",
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(5L),
                        Optional.of(BuiltinExtensionTestSupport.NOW),
                        role,
                        provider,
                        permission,
                        ApprovalPolicy.EVERY_CALL,
                        ReasoningPreference.HIGH,
                        "定时检查",
                        "检查工作区",
                        2,
                        1000,
                        300,
                        10,
                        List.of())
                .toRequest();

        assertEquals(role, request.execution().role().orElseThrow());
        assertEquals(provider, request.execution().provider().orElseThrow());
        assertEquals(permission, request.execution().permissionProfile().orElseThrow());
        assertEquals(
                ApprovalPolicy.EVERY_CALL, request.execution().approvalPolicy().orElseThrow());
        assertEquals(ReasoningPreference.HIGH, request.execution().reasoning().orElseThrow());
        assertEquals(2, request.budget().maximumTurns());
        assertEquals(1000, request.budget().inputTokens());
        assertEquals(
                com.javaclaw.builtin.contracts.ScheduleContracts.TimingKind.FIXED_INTERVAL,
                request.timing().kind());
    }

    private static ViewQueryResult query(
            BuiltinExtensionTestSupport support,
            BuiltinExtensionTestSupport.Started started,
            String operation,
            String source,
            String cursor)
            throws Exception {
        return support.decode(
                started.query(support.request(
                        operation,
                        new ViewQueryRequest(source, Map.of(), cursor, 1, Optional.empty()),
                        Optional.empty(),
                        0)),
                ViewQueryResult.class);
    }
}
