package com.javaclaw.protocol;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.ToolDescriptor;
import com.javaclaw.api.ToolIdentity;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.WorkspaceId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtensionRpcContractsTest {
    private static final WorkspaceId WORKSPACE_ID = new WorkspaceId(new UUID(1, 1));
    private static final ThreadId THREAD_ID = new ThreadId(new UUID(1, 2));
    private static final TurnId TURN_ID = new TurnId(new UUID(1, 3));
    private static final CanonicalPayload EMPTY = new CanonicalPayload("{}");

    @Test
    void extension调用与结果规范化标识并保留上下文() {
        ExtensionRpcContracts.CallPayload call = new ExtensionRpcContracts.CallPayload(
                " plan ", WORKSPACE_ID, Optional.of(THREAD_ID), Optional.of(TURN_ID), " query ", EMPTY);
        ExtensionRpcContracts.CallResult result = new ExtensionRpcContracts.CallResult(EMPTY, 0);

        assertEquals("plan", call.extensionId());
        assertEquals("query", call.operation());
        assertEquals(Optional.of(THREAD_ID), call.threadId());
        assertEquals(Optional.of(TURN_ID), call.turnId());
        assertEquals(0, result.revision());
        assertThrows(IllegalArgumentException.class, () -> new ExtensionRpcContracts.CallResult(EMPTY, -1));
        assertThrows(NullPointerException.class, () -> new ExtensionRpcContracts.CallResult(null, 0));
    }

    @Test
    void extension调用拒绝空标识和缺失上下文容器() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExtensionRpcContracts.CallPayload(
                        " ", WORKSPACE_ID, Optional.empty(), Optional.empty(), "query", EMPTY));
        assertThrows(
                NullPointerException.class,
                () -> new ExtensionRpcContracts.CallPayload(
                        "plan", null, Optional.empty(), Optional.empty(), "query", EMPTY));
        assertThrows(
                NullPointerException.class,
                () -> new ExtensionRpcContracts.CallPayload(
                        "plan", WORKSPACE_ID, null, Optional.empty(), "query", EMPTY));
        assertThrows(
                NullPointerException.class,
                () -> new ExtensionRpcContracts.CallPayload(
                        "plan", WORKSPACE_ID, Optional.empty(), null, "query", EMPTY));
        assertThrows(
                NullPointerException.class,
                () -> new ExtensionRpcContracts.CallPayload(
                        "plan", WORKSPACE_ID, Optional.empty(), Optional.empty(), "query", null));
    }

    @Test
    void extension事件只携带资源定位和权威版本() {
        ExtensionRpcContracts.ExtensionEvent event = new ExtensionRpcContracts.ExtensionEvent(
                WORKSPACE_ID, " plan ", " workspace ", WORKSPACE_ID.toString(), " definition.put ", 3);
        CanonicalPayload encoded = new CanonicalJson().encode(event);

        assertEquals("plan", event.extensionId());
        assertEquals("workspace", event.scope());
        assertEquals(3, event.revision());
        assertEquals(
                Set.of("workspaceId", "extensionId", "scope", "resourceId", "operation", "revision"),
                new CanonicalJson().fieldNames(encoded));
        assertFalse(encoded.json().contains("payload"));
        assertFalse(encoded.json().toLowerCase(java.util.Locale.ROOT).contains("secret"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExtensionRpcContracts.ExtensionEvent(
                        WORKSPACE_ID, "plan", "workspace", WORKSPACE_ID.toString(), "put", 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExtensionRpcContracts.ExtensionEvent(
                        WORKSPACE_ID, "plan", " ", WORKSPACE_ID.toString(), "put", 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExtensionRpcContracts.ExtensionEvent(
                        WORKSPACE_ID, "plan", "workspace", "unsafe value", "put", 1));
    }

    @Test
    void extension摘要Schema与View结果完整往返() {
        ExtensionRpcContracts.Summary summary = new ExtensionRpcContracts.Summary(
                " plan ", " Plan ", " 5.0 ", 1, " enabled ", " builtin ", Set.of("TOOL"));
        ExtensionRpcContracts.SchemaReadPayload read =
                new ExtensionRpcContracts.SchemaReadPayload(" plan ", " plan/schema@1 ");
        ExtensionRpcContracts.SchemaResult schema =
                new ExtensionRpcContracts.SchemaResult("plan", "plan/schema@1", EMPTY);
        ExtensionRpcContracts.ViewDocument view = new ExtensionRpcContracts.ViewDocument("plan", "plan.main", EMPTY);

        assertEquals("plan", summary.id());
        assertEquals("Plan", summary.displayName());
        assertEquals("plan/schema@1", read.schemaId());
        assertEquals(EMPTY, schema.schema());
        assertEquals("plan.main", view.viewId());
        assertEquals(
                Optional.of("plan"), new ExtensionRpcContracts.ViewListPayload(Optional.of(" plan ")).extensionId());
        assertTrue(new ExtensionRpcContracts.ViewListPayload(Optional.empty())
                .extensionId()
                .isEmpty());
        assertEquals(List.of(summary), new ExtensionRpcContracts.ListResult(List.of(summary)).extensions());
        assertEquals(List.of(view), new ExtensionRpcContracts.ViewListResult(List.of(view)).views());
    }

    @Test
    void extension摘要Schema与View拒绝非法版本和空字段() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExtensionRpcContracts.Summary("plan", "Plan", "5", 0, "enabled", "builtin", Set.of()));
        assertThrows(
                NullPointerException.class,
                () -> new ExtensionRpcContracts.Summary("plan", "Plan", "5", 1, "enabled", "builtin", null));
        assertThrows(IllegalArgumentException.class, () -> new ExtensionRpcContracts.SchemaReadPayload(" ", "schema"));
        assertThrows(NullPointerException.class, () -> new ExtensionRpcContracts.SchemaResult("plan", "schema", null));
        assertThrows(NullPointerException.class, () -> new ExtensionRpcContracts.ViewListPayload(null));
        assertThrows(IllegalArgumentException.class, () -> new ExtensionRpcContracts.ViewListPayload(Optional.of(" ")));
        assertThrows(NullPointerException.class, () -> new ExtensionRpcContracts.ViewDocument("plan", "view", null));
        assertThrows(NullPointerException.class, () -> new ExtensionRpcContracts.ListResult(null));
        assertThrows(NullPointerException.class, () -> new ExtensionRpcContracts.ViewListResult(null));
    }

    @Test
    void tool搜索校验关键词版本与页大小() {
        ToolRpcContracts.SearchArguments arguments = new ToolRpcContracts.SearchArguments(" files ", 100);
        ToolRpcContracts.CatalogQuery query =
                new ToolRpcContracts.CatalogQuery(WORKSPACE_ID, " profile ", 1, " code ", 1);

        assertEquals("files", arguments.query());
        assertEquals("profile", query.permissionProfileId());
        assertThrows(IllegalArgumentException.class, () -> new ToolRpcContracts.SearchArguments(" ", 1));
        assertThrows(IllegalArgumentException.class, () -> new ToolRpcContracts.SearchArguments("x", 0));
        assertThrows(IllegalArgumentException.class, () -> new ToolRpcContracts.SearchArguments("x", 101));
        assertThrows(NullPointerException.class, () -> new ToolRpcContracts.CatalogQuery(null, "profile", 1, "x", 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolRpcContracts.CatalogQuery(WORKSPACE_ID, "profile", 0, "x", 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolRpcContracts.CatalogQuery(WORKSPACE_ID, "profile", 1, "x", 101));
    }

    @Test
    void tool搜索结果取得列表所有权() {
        ToolDescriptor descriptor = new ToolDescriptor(
                new ToolIdentity("core", "read_file", 1), "读取文件", EMPTY, EMPTY, ToolRisk.READ_ONLY, Set.of("file"));
        ToolRpcContracts.SearchResult result = new ToolRpcContracts.SearchResult(List.of(descriptor));

        assertEquals(List.of(descriptor), result.tools());
        assertThrows(NullPointerException.class, () -> new ToolRpcContracts.SearchResult(null));
    }
}
