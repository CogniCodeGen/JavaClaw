package com.javaclaw.server.extension.mcp;

import java.util.Set;

/** Constants pinned to the reviewed MCP 2026-07-28 schema snapshot. */
public final class McpProtocol {
    public static final String VERSION = "2026-07-28";
    public static final String SCHEMA_COMMIT = "271ecc9accafdd9b83a3c869fa67c22953b2af80";
    public static final String SCHEMA_SHA256 = "ef70b61f99b6d2e5e3b46863822eab08dff6a45bedc7a08914e0e5b133f40203";
    public static final String SCHEMA_RESOURCE = "/com/javaclaw/server/extension/mcp/schema-2026-07-28.json";

    public static final String PROTOCOL_VERSION_META = "io.modelcontextprotocol/protocolVersion";
    public static final String CLIENT_INFO_META = "io.modelcontextprotocol/clientInfo";
    public static final String CLIENT_CAPABILITIES_META = "io.modelcontextprotocol/clientCapabilities";
    public static final String TASKS_EXTENSION = "io.modelcontextprotocol/tasks";

    public static final String DISCOVER = "server/discover";
    public static final String TOOLS_LIST = "tools/list";
    public static final String TOOLS_CALL = "tools/call";
    public static final String RESOURCES_LIST = "resources/list";
    public static final String RESOURCES_READ = "resources/read";
    public static final String RESOURCE_TEMPLATES_LIST = "resources/templates/list";
    public static final String PROMPTS_LIST = "prompts/list";
    public static final String PROMPT_GET = "prompts/get";
    public static final String COMPLETION_COMPLETE = "completion/complete";
    public static final String SUBSCRIPTIONS_LISTEN = "subscriptions/listen";
    public static final String CANCELLED = "notifications/cancelled";
    public static final String PROGRESS = "notifications/progress";
    public static final String TASKS_GET = "tasks/get";
    public static final String TASKS_UPDATE = "tasks/update";
    public static final String TASKS_CANCEL = "tasks/cancel";

    public static final int MAX_MRTR_ROUNDS = 4;
    public static final int MAX_PAGE_COUNT = 1_000;
    public static final long MAX_MESSAGE_BYTES = 16L * 1024L * 1024L;
    public static final Set<String> TASK_METHODS = Set.of(TASKS_GET, TASKS_UPDATE, TASKS_CANCEL);

    private McpProtocol() {}
}
