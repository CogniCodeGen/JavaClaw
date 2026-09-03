package com.javaclaw.client;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AgentProfileRef;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ToolDescriptor;
import com.javaclaw.api.ToolIdentity;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.client.facade.ToolClient;
import com.javaclaw.client.testkit.ScriptedRpcConnection;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.JsonRpcResponse;
import com.javaclaw.protocol.ToolRpcContracts;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolClientTest {
    private static final CanonicalJson JSON = new CanonicalJson();
    private static final WorkspaceId WORKSPACE = WorkspaceId.parse("9f4e0135-0bfb-46ce-a84f-854e602ce275");

    @Test
    void 空查询列出固定Workspace和精确权限版本下的候选() {
        ToolDescriptor descriptor = new ToolDescriptor(
                new ToolIdentity("core", "core_search", 2),
                "只读搜索",
                new CanonicalPayload("{}"),
                new CanonicalPayload("{}"),
                ToolRisk.READ_ONLY,
                Set.of("search"));
        ScriptedRpcConnection rpc = new ScriptedRpcConnection(request -> {
            ToolRpcContracts.CatalogQuery params = JSON.decode(request.params(), ToolRpcContracts.CatalogQuery.class);
            assertEquals("tool/search", request.method());
            assertEquals(WORKSPACE, params.workspaceId());
            assertEquals("workspace-review", params.permissionProfileId());
            assertEquals(3, params.permissionProfileVersion());
            if (params.agentProfile().isEmpty()) {
                assertEquals("", params.query());
                assertEquals(25, params.limit());
            } else {
                assertEquals(
                        new AgentProfileRef("worker", 4), params.agentProfile().orElseThrow());
                assertEquals("read", params.query());
                assertEquals(10, params.limit());
            }
            return JsonRpcResponse.success(
                    request.id(), JSON.encode(new ToolRpcContracts.SearchResult(17, List.of(descriptor))));
        });
        ToolClient client = new ToolClient(new RpcClientConnection(rpc, JSON, ignored -> {}));

        assertEquals(
                List.of(descriptor), client.search(WORKSPACE, new PermissionProfileRef("workspace-review", 3), "", 25));

        var catalog = client.catalog(
                WORKSPACE,
                new PermissionProfileRef("workspace-review", 3),
                Optional.of(new AgentProfileRef("worker", 4)),
                "read",
                10);
        assertEquals(17, catalog.catalogRevision());
        assertEquals(List.of(descriptor), catalog.tools());
    }
}
