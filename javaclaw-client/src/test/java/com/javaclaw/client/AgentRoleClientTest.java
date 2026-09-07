package com.javaclaw.client;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AgentRole;
import com.javaclaw.api.AgentRoleFileExport;
import com.javaclaw.api.AgentRoleFileFormat;
import com.javaclaw.api.AgentRoleFilePreview;
import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.AgentRoleSpec;
import com.javaclaw.api.CapabilityNarrowing;
import com.javaclaw.api.PermissionConstraint;
import com.javaclaw.api.RoleLifecycle;
import com.javaclaw.client.facade.AgentRoleClient;
import com.javaclaw.client.testkit.ScriptedRpcConnection;
import com.javaclaw.protocol.AgentRoleFileRpcContracts;
import com.javaclaw.protocol.AgentRoleRpcContracts;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.JsonRpcRequest;
import com.javaclaw.protocol.JsonRpcResponse;
import com.javaclaw.protocol.WriteCommand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AgentRoleClientTest {
    private static final CanonicalJson JSON = new CanonicalJson();
    private static final AgentRoleRef SOURCE = new AgentRoleRef("worker", 1);
    private static final AgentRole ROLE = role();

    @Test
    void 角色生命周期复用幂等信封且精确传递版本() {
        List<String> methods = new ArrayList<>();
        ScriptedRpcConnection rpc = new ScriptedRpcConnection(request -> {
            methods.add(request.method());
            if (request.method().equals("agent/role/list")) {
                return JsonRpcResponse.success(
                        request.id(), JSON.encode(new AgentRoleRpcContracts.ListResult(List.of(ROLE))));
            }
            if (request.method().equals("agent/role/read")) {
                assertEquals(
                        SOURCE,
                        JSON.decode(request.params(), AgentRoleRpcContracts.ReadPayload.class)
                                .role());
            } else {
                WriteCommand command = JSON.decode(request.params(), WriteCommand.class);
                assertEquals("role-command", command.idempotencyKey());
                assertEquals(1, command.expectedRevision());
                assertFalse(command.payload().json().contains("credential"));
            }
            return JsonRpcResponse.success(request.id(), JSON.encode(ROLE));
        });
        AgentRoleClient client = new AgentRoleClient(new RpcClientConnection(rpc, JSON, ignored -> {}));
        CommandOptions options = new CommandOptions("role-command", 1);

        assertEquals(List.of(ROLE), client.list());
        assertEquals(ROLE, client.read(SOURCE.id(), SOURCE.revision()));
        assertEquals(ROLE, client.create("custom", ROLE.spec(), options));
        assertEquals(ROLE, client.update("custom", ROLE.spec(), RoleLifecycle.DISABLED, options));
        assertEquals(ROLE, client.archive("custom", options));
        assertEquals(ROLE, client.clone(SOURCE, "custom", "Custom", options));
        assertEquals(
                List.of(
                        "agent/role/list",
                        "agent/role/read",
                        "agent/role/create",
                        "agent/role/update",
                        "agent/role/archive",
                        "agent/role/clone"),
                methods);
    }

    @Test
    void 文件预览和确认提交分离且导出不提交任意路径() {
        AgentRoleFilePreview preview = new AgentRoleFilePreview(
                "preview",
                "custom",
                ROLE.spec(),
                "a".repeat(64),
                Optional.empty(),
                List.of("developerInstructions"),
                AgentRoleFileFormat.CODEX_PORTABLE);
        AgentRoleFileExport exported = new AgentRoleFileExport(
                "custom.agent.toml", "name = \"Custom\"", "a".repeat(64), AgentRoleFileFormat.JAVACLAW_LOSSLESS);
        ScriptedRpcConnection rpc = new ScriptedRpcConnection(request -> switch (request.method()) {
            case "agent/role/import/preview" -> {
                AgentRoleFileRpcContracts.PreviewPayload payload =
                        JSON.decode(request.params(), AgentRoleFileRpcContracts.PreviewPayload.class);
                assertEquals("custom", payload.roleId());
                yield JsonRpcResponse.success(request.id(), JSON.encode(preview));
            }
            case "agent/role/import/commit" -> commitResponse(request);
            case "agent/role/export" -> {
                assertEquals(
                        SOURCE,
                        JSON.decode(request.params(), AgentRoleFileRpcContracts.ExportPayload.class)
                                .role());
                yield JsonRpcResponse.success(request.id(), JSON.encode(exported));
            }
            default -> throw new AssertionError(request.method());
        });
        AgentRoleClient client = new AgentRoleClient(new RpcClientConnection(rpc, JSON, ignored -> {}));

        assertEquals(preview, client.importPreview("custom", "name = \"Custom\"", AgentRoleFileFormat.CODEX_PORTABLE));
        assertEquals(ROLE, client.importCommit(preview.previewId(), Optional.empty(), new CommandOptions("commit", 0)));
        assertEquals(exported, client.export(SOURCE, AgentRoleFileFormat.JAVACLAW_LOSSLESS));
    }

    private static JsonRpcResponse commitResponse(JsonRpcRequest request) {
        WriteCommand command = JSON.decode(request.params(), WriteCommand.class);
        assertEquals(0, command.expectedRevision());
        AgentRoleFileRpcContracts.CommitPayload payload =
                JSON.decode(command.payload(), AgentRoleFileRpcContracts.CommitPayload.class);
        assertEquals("preview", payload.previewId());
        assertEquals(Optional.empty(), payload.modelMapping());
        return JsonRpcResponse.success(request.id(), JSON.encode(ROLE));
    }

    private static AgentRole role() {
        AgentRoleSpec spec = new AgentRoleSpec(
                "Custom",
                "可复用角色",
                "保留用户修改。",
                Optional.empty(),
                Optional.empty(),
                new CapabilityNarrowing(Optional.empty(), Optional.empty()),
                PermissionConstraint.INHERIT,
                Map.of());
        Instant now = Instant.parse("2026-09-07T00:00:00Z");
        return new AgentRole("custom", 1, RoleLifecycle.ACTIVE, spec, false, now, now);
    }
}
