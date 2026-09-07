package com.javaclaw.protocol;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AgentRoleFileFormat;
import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.AgentRoleSpec;
import com.javaclaw.api.CapabilityNarrowing;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.PermissionConstraint;
import com.javaclaw.api.RoleLifecycle;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.WorkspaceId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRoleRpcContractsTest {
    private static final CanonicalJson JSON = new CanonicalJson();
    private static final AgentRoleRef ROLE = new AgentRoleRef("worker", 3);
    private static final WorkspaceId WORKSPACE = WorkspaceId.parse("00000000-0000-0000-0000-000000000001");
    private static final ThreadId THREAD = ThreadId.parse("00000000-0000-0000-0000-000000000002");

    @Test
    void Role生命周期契约严格往返且拒绝未知授权字段() {
        List<Object> payloads = List.of(
                new AgentRoleRpcContracts.ReadPayload(ROLE),
                new AgentRoleRpcContracts.CreatePayload("custom", spec()),
                new AgentRoleRpcContracts.UpdatePayload("custom", spec(), RoleLifecycle.DISABLED),
                new AgentRoleRpcContracts.ArchivePayload("custom"),
                new AgentRoleRpcContracts.ClonePayload(ROLE, "custom", "定制 Worker"),
                new AgentRoleRpcContracts.ListResult(List.of()));
        for (Object payload : payloads) {
            assertEquals(payload, JSON.decode(JSON.encode(payload), payload.getClass()));
            String encoded = JSON.encode(payload).json();
            String elevated = encoded.substring(0, encoded.length() - 1) + ",\"permissionGrant\":true}";
            assertThrows(ProtocolException.class, () -> JSON.decode(JSON.parse(elevated), payload.getClass()));
        }
        assertFalse(JSON.encode(spec()).json().contains("permissionProfile"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AgentRoleRpcContracts.UpdatePayload("custom", spec(), RoleLifecycle.ARCHIVED));
        assertThrows(IllegalArgumentException.class, () -> new AgentRoleRpcContracts.ClonePayload(ROLE, "custom", " "));
    }

    @Test
    void 独立执行覆盖保留继承与显式空能力的区别() {
        ExecutionOverrides inherited = ExecutionOverrides.empty();
        ExecutionOverrides disabled = new ExecutionOverrides(
                Optional.of(ROLE),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(Set.of()),
                Optional.empty());
        assertEquals(inherited, JSON.decode(JSON.encode(inherited), ExecutionOverrides.class));
        assertEquals(disabled, JSON.decode(JSON.encode(disabled), ExecutionOverrides.class));
        assertFalse(inherited.visibleCapabilities().isPresent());
        assertTrue(disabled.visibleCapabilities().orElseThrow().isEmpty());
        List<Object> payloads = List.of(
                new ExecutionRpcContracts.DefaultReadPayload(Optional.empty()),
                new ExecutionRpcContracts.DefaultUpdatePayload(Optional.of(WORKSPACE), disabled),
                new ExecutionRpcContracts.ThreadReadPayload(WORKSPACE, THREAD),
                new ExecutionRpcContracts.ThreadUpdatePayload(WORKSPACE, THREAD, inherited),
                new ExecutionRpcContracts.ReadResult(Optional.empty()),
                new PromptManifestRpcContracts.PreviewPayload(WORKSPACE, Optional.of(THREAD), disabled));
        for (Object payload : payloads) {
            assertEquals(payload, JSON.decode(JSON.encode(payload), payload.getClass()));
        }
    }

    @Test
    void 文件确认只传预览标识与显式模型映射而不重发可变定义() {
        List<Object> payloads = List.of(
                new AgentRoleFileRpcContracts.PreviewPayload(
                        "custom", "name = \"Custom\"", AgentRoleFileFormat.CODEX_PORTABLE),
                new AgentRoleFileRpcContracts.CommitPayload("preview-1", Optional.empty()),
                new AgentRoleFileRpcContracts.ExportPayload(ROLE, AgentRoleFileFormat.JAVACLAW_LOSSLESS));
        for (Object payload : payloads) {
            assertEquals(payload, JSON.decode(JSON.encode(payload), payload.getClass()));
        }
        assertEquals(Set.of("modelMapping", "previewId"), JSON.fieldNames(JSON.encode(payloads.get(1))));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AgentRoleFileRpcContracts.CommitPayload(" ", Optional.empty()));
    }

    @Test
    void 旧Profile方法与旧Turn字段不会作为兼容配置被接受() {
        NegotiatedCapabilities none = new NegotiatedCapabilities(Set.of(), Set.of());
        for (String method :
                List.of("profile/list", "profile/read", "profile/binding/read", "profile/prompt/preview")) {
            assertEquals(
                    ProtocolErrorCode.METHOD_NOT_FOUND,
                    assertThrows(ProtocolException.class, () -> MethodCatalog.require(method, none))
                            .code());
        }
        String oldPayload = "{\"threadId\":\"" + THREAD + "\",\"profile\":null,\"message\":\"hello\"}";
        assertThrows(
                ProtocolException.class,
                () -> JSON.decode(JSON.parse(oldPayload), CoreRpcContracts.TurnStartPayload.class));
    }

    private static AgentRoleSpec spec() {
        return new AgentRoleSpec(
                "Worker",
                "有限范围执行",
                "保留用户修改。",
                Optional.empty(),
                Optional.empty(),
                new CapabilityNarrowing(Optional.empty(), Optional.empty()),
                PermissionConstraint.INHERIT,
                Map.of());
    }
}
