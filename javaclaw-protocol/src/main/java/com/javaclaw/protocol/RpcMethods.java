package com.javaclaw.protocol;

import java.util.Set;

/** 公共 JSON-RPC 方法名集合；客户端通过领域 SDK 使用，UI 不直接依赖这些常量。 */
public final class RpcMethods {
    public static final String SITE_LIST = "site/list";
    public static final String SITE_PUT = "site/put";
    public static final String SITE_DELETE = "site/delete";
    public static final String SITE_CREDENTIAL_SET = "site/credential/set";
    public static final String SITE_CREDENTIAL_READ = "site/credential/read";
    public static final String SITE_CREDENTIAL_CLEAR = "site/credential/clear";
    public static final String SITE_LOGIN_START = "site/login/start";
    public static final String SITE_LOGIN_FINISH = "site/login/finish";
    public static final String SITE_SESSION_READ = "site/session/read";
    public static final String SITE_SESSION_CLEAR = "site/session/clear";
    public static final String NETWORK_GRANT_LIST = "network/grant/list";
    public static final String NETWORK_GRANT_PUT = "network/grant/put";
    public static final String NETWORK_GRANT_DELETE = "network/grant/delete";
    public static final String TOOL_AUTHORIZATION_OPTIONS = "tool/authorization/options";
    public static final String TOOL_AUTHORIZATION_LIST = "tool/authorization/list";
    public static final String TOOL_AUTHORIZATION_PUT = "tool/authorization/put";
    public static final String TOOL_AUTHORIZATION_DELETE = "tool/authorization/delete";
    public static final String PROFILE_PROMPT_PREVIEW = "profile/prompt/preview";
    public static final String PROFILE_PROMPT_OPTIMIZE = "profile/prompt/optimize";
    public static final String INITIALIZE = "initialize";
    public static final String INITIALIZED = "initialized";
    public static final String CAPABILITIES = "server/capabilities";
    public static final String WORKSPACE_LIST = "workspace/list";
    public static final String WORKSPACE_CREATE = "workspace/create";
    public static final String WORKSPACE_READ = "workspace/read";
    public static final String WORKSPACE_UPDATE = "workspace/update";
    public static final String WORKSPACE_DELETE = "workspace/delete";
    public static final String WORKSPACE_INSTRUCTIONS_RESOLVE = "workspace/instructions/resolve";
    public static final String THREAD_START = "thread/start";
    public static final String THREAD_RESUME = "thread/resume";
    public static final String THREAD_FORK = "thread/fork";
    public static final String THREAD_READ = "thread/read";
    public static final String THREAD_LIST = "thread/list";
    public static final String THREAD_ARCHIVE = "thread/archive";
    public static final String THREAD_UNARCHIVE = "thread/unarchive";
    public static final String THREAD_DELETE = "thread/delete";
    public static final String THREAD_COMPACT_START = "thread/compact/start";
    public static final String THREAD_UPDATE = "thread/update";
    public static final String THREAD_PLAN_ADOPT = "thread/plan/adopt";
    public static final String THREAD_EXECUTION_SUMMARY = "thread/execution/summary";
    public static final String THREAD_RETRY_IN_NEW_BRANCH = "thread/retryInNewBranch";
    public static final String WORKTREE_LIST = "worktree/list";
    public static final String WORKTREE_PATCH = "worktree/patch";
    public static final String WORKTREE_CLEANUP = "worktree/cleanup";
    public static final String TURN_START = "turn/start";
    public static final String TURN_STEER = "turn/steer";
    public static final String TURN_INTERRUPT = "turn/interrupt";
    public static final String APPROVAL_RESPOND = "approval/respond";
    public static final String USER_INPUT_RESPOND = "userInput/respond";
    public static final String ATTACHMENT_UPLOAD_START = "attachment/upload/start";
    public static final String ATTACHMENT_UPLOAD_CHUNK = "attachment/upload/chunk";
    public static final String ATTACHMENT_UPLOAD_COMPLETE = "attachment/upload/complete";
    public static final String ATTACHMENT_READ = "attachment/read";
    public static final String ATTACHMENT_RELEASE = "attachment/release";
    public static final String EVENT_LIST = "event/list";
    public static final String MODEL_LIST = "model/list";
    public static final String PROVIDER_LIST = "provider/list";
    public static final String PROVIDER_CONFIGURE = "provider/configure";
    public static final String PROVIDER_CREDENTIAL_SET = "provider/credential/set";
    public static final String PROVIDER_CREDENTIAL_CLEAR = "provider/credential/clear";
    public static final String PROFILE_LIST = "profile/list";
    public static final String PROFILE_READ = "profile/read";
    public static final String PROFILE_PUT = "profile/put";
    public static final String PROFILE_DELETE = "profile/delete";
    public static final String AUTOMATION_LIST = "automation/list";
    public static final String AUTOMATION_READ = "automation/read";
    public static final String AUTOMATION_PUT = "automation/put";
    public static final String AUTOMATION_DELETE = "automation/delete";
    public static final String AUTOMATION_START = "automation/start";
    public static final String AUTOMATION_RESUME = "automation/resume";
    public static final String AUTOMATION_ITEMS = "automation/items";
    public static final String AUTOMATION_INTERRUPT = "automation/interrupt";
    public static final String SCHEDULE_LIST = "schedule/list";
    public static final String SCHEDULE_READ = "schedule/read";
    public static final String SCHEDULE_PUT = "schedule/put";
    public static final String SCHEDULE_ENABLE = "schedule/enable";
    public static final String SCHEDULE_DISABLE = "schedule/disable";
    public static final String SCHEDULE_DELETE = "schedule/delete";
    public static final String SCHEDULE_TRIGGER = "schedule/trigger";
    public static final String SCHEDULE_PREVIEW = "schedule/preview";
    public static final String KNOWLEDGE_SOURCE_LIST = "knowledge/source/list";
    public static final String KNOWLEDGE_SOURCE_IMPORT = "knowledge/source/import";
    public static final String KNOWLEDGE_SOURCE_READ = "knowledge/source/read";
    public static final String KNOWLEDGE_SOURCE_DELETE = "knowledge/source/delete";
    public static final String KNOWLEDGE_SOURCE_REINDEX = "knowledge/source/reindex";
    public static final String KNOWLEDGE_SEARCH = "knowledge/search";
    public static final String KNOWLEDGE_SOURCE_STATS = "knowledge/source/stats";
    public static final String MEMORY_LIST = "memory/list";
    public static final String MEMORY_PUT = "memory/put";
    public static final String MEMORY_DELETE = "memory/delete";
    public static final String MEMORY_READ = "memory/read";
    public static final String MEMORY_HISTORY = "memory/history";
    public static final String MEMORY_SAVE = "memory/save";
    public static final String MEMORY_RESTORE = "memory/restore";
    public static final String MEMORY_PROPOSE = "memory/propose";
    public static final String MEMORY_PROPOSALS = "memory/proposal/list";
    public static final String MEMORY_REVIEW = "memory/proposal/review";
    public static final String SKILL_LIST = "skill/list";
    public static final String KNOWLEDGE_SOURCE_HISTORY = "knowledge/source/history";
    public static final String KNOWLEDGE_SOURCE_CONTENT = "knowledge/source/content";
    public static final String SKILL_READ = "skill/read";
    public static final String SKILL_HISTORY = "skill/history";
    public static final String SKILL_RESTORE = "skill/restore";
    public static final String SKILL_RESOURCE_READ = "skill/resource/read";
    public static final String SKILL_PROPOSE = "skill/propose";
    public static final String SKILL_PROPOSALS = "skill/proposal/list";
    public static final String SKILL_REVIEW = "skill/proposal/review";
    public static final String LEARNING_READ = "learning/read";
    public static final String LEARNING_CONFIGURE = "learning/configure";
    public static final String SKILL_INSTALL = "skill/install";
    public static final String SKILL_ENABLE = "skill/enable";
    public static final String SKILL_DISABLE = "skill/disable";
    public static final String SKILL_UNINSTALL = "skill/uninstall";
    public static final String TOOL_LIST = "tool/list";
    public static final String MCP_LIST = "mcp/list";
    public static final String MCP_HEALTH = "mcp/health";
    public static final String MCP_CONFIGURE = "mcp/configure";
    public static final String MCP_DISCOVER = "mcp/discover";
    public static final String MCP_AUTHORIZE_START = "mcp/authorize/start";
    public static final String MCP_AUTHORIZE_CANCEL = "mcp/authorize/cancel";
    public static final String MCP_CREDENTIAL_SET = "mcp/credential/set";
    public static final String MCP_CREDENTIAL_READ = "mcp/credential/read";
    public static final String MCP_CREDENTIAL_CLEAR = "mcp/credential/clear";
    public static final String PLUGIN_LIST = "plugin/list";
    public static final String PLUGIN_READ = "plugin/read";
    public static final String PLUGIN_INSTALL = "plugin/install";
    public static final String PLUGIN_PREVIEW = "plugin/preview";
    public static final String PLUGIN_ENABLE = "plugin/enable";
    public static final String PLUGIN_DISABLE = "plugin/disable";
    public static final String PLUGIN_UNINSTALL = "plugin/uninstall";
    public static final String PLUGIN_HEALTH = "plugin/health";
    public static final String PLUGIN_TRUST_LIST = "plugin/trust/list";
    public static final String PLUGIN_TRUST_ADD = "plugin/trust/add";
    public static final String PLUGIN_TRUST_REMOVE = "plugin/trust/remove";
    public static final String DIAGNOSTICS_READ = "diagnostics/read";
    public static final String DIAGNOSTICS_EXPORT = "diagnostics/export";
    public static final String CONFIG_READ = "config/read";
    public static final String CONFIG_UPDATE = "config/update";

    public static final String TURN_STARTED = "turn/started";
    public static final String TURN_COMPLETED = "turn/completed";
    public static final String ITEM_STARTED = "item/started";
    public static final String ITEM_DELTA = "item/delta";
    public static final String ITEM_COMPLETED = "item/completed";
    public static final String APPROVAL_REQUESTED = "approval/requested";
    public static final String USER_INPUT_REQUESTED = "userInput/requested";
    public static final String USAGE_UPDATED = "usage/updated";
    public static final String RESYNC_REQUIRED = "resyncRequired";
    public static final String THREAD_EVENT = "thread/event";
    public static final String MCP_AUTHORIZATION_REQUESTED = "mcp/authorization/requested";
    public static final String MCP_STATUS_CHANGED = "mcp/status/changed";

    public static final Set<String> V1 = Set.of(
            INITIALIZE,
            INITIALIZED,
            CAPABILITIES,
            WORKSPACE_LIST,
            WORKSPACE_CREATE,
            WORKSPACE_READ,
            WORKSPACE_UPDATE,
            WORKSPACE_DELETE,
            WORKSPACE_INSTRUCTIONS_RESOLVE,
            THREAD_START,
            THREAD_RESUME,
            THREAD_FORK,
            THREAD_READ,
            THREAD_LIST,
            THREAD_ARCHIVE,
            THREAD_UNARCHIVE,
            THREAD_DELETE,
            THREAD_COMPACT_START,
            THREAD_UPDATE,
            THREAD_PLAN_ADOPT,
            THREAD_EXECUTION_SUMMARY,
            THREAD_RETRY_IN_NEW_BRANCH,
            WORKTREE_LIST,
            WORKTREE_PATCH,
            WORKTREE_CLEANUP,
            TURN_START,
            TURN_STEER,
            TURN_INTERRUPT,
            APPROVAL_RESPOND,
            USER_INPUT_RESPOND,
            ATTACHMENT_UPLOAD_START,
            ATTACHMENT_UPLOAD_CHUNK,
            ATTACHMENT_UPLOAD_COMPLETE,
            ATTACHMENT_READ,
            ATTACHMENT_RELEASE,
            EVENT_LIST,
            MODEL_LIST,
            PROVIDER_LIST,
            PROVIDER_CONFIGURE,
            PROVIDER_CREDENTIAL_SET,
            PROVIDER_CREDENTIAL_CLEAR,
            PROFILE_LIST,
            PROFILE_READ,
            PROFILE_PUT,
            PROFILE_DELETE,
            PROFILE_PROMPT_PREVIEW,
            PROFILE_PROMPT_OPTIMIZE,
            AUTOMATION_LIST,
            AUTOMATION_READ,
            AUTOMATION_PUT,
            AUTOMATION_DELETE,
            AUTOMATION_START,
            AUTOMATION_RESUME,
            AUTOMATION_ITEMS,
            AUTOMATION_INTERRUPT,
            SCHEDULE_LIST,
            SCHEDULE_READ,
            SCHEDULE_PUT,
            SCHEDULE_ENABLE,
            SCHEDULE_DISABLE,
            SCHEDULE_DELETE,
            SCHEDULE_TRIGGER,
            SCHEDULE_PREVIEW,
            KNOWLEDGE_SOURCE_LIST,
            KNOWLEDGE_SOURCE_IMPORT,
            KNOWLEDGE_SOURCE_READ,
            KNOWLEDGE_SOURCE_DELETE,
            KNOWLEDGE_SOURCE_REINDEX,
            KNOWLEDGE_SEARCH,
            KNOWLEDGE_SOURCE_STATS,
            MEMORY_LIST,
            MEMORY_PUT,
            MEMORY_DELETE,
            MEMORY_READ,
            MEMORY_HISTORY,
            MEMORY_SAVE,
            MEMORY_RESTORE,
            MEMORY_PROPOSE,
            MEMORY_PROPOSALS,
            MEMORY_REVIEW,
            SKILL_LIST,
            KNOWLEDGE_SOURCE_HISTORY,
            KNOWLEDGE_SOURCE_CONTENT,
            SKILL_READ,
            SKILL_HISTORY,
            SKILL_RESTORE,
            SKILL_RESOURCE_READ,
            SKILL_PROPOSE,
            SKILL_PROPOSALS,
            SKILL_REVIEW,
            LEARNING_READ,
            LEARNING_CONFIGURE,
            SKILL_INSTALL,
            SKILL_ENABLE,
            SKILL_DISABLE,
            SKILL_UNINSTALL,
            TOOL_LIST,
            MCP_LIST,
            MCP_HEALTH,
            MCP_CONFIGURE,
            MCP_DISCOVER,
            MCP_AUTHORIZE_START,
            MCP_AUTHORIZE_CANCEL,
            MCP_CREDENTIAL_SET,
            MCP_CREDENTIAL_READ,
            MCP_CREDENTIAL_CLEAR,
            PLUGIN_LIST,
            PLUGIN_READ,
            PLUGIN_INSTALL,
            PLUGIN_PREVIEW,
            PLUGIN_ENABLE,
            PLUGIN_DISABLE,
            PLUGIN_UNINSTALL,
            PLUGIN_HEALTH,
            PLUGIN_TRUST_LIST,
            PLUGIN_TRUST_ADD,
            PLUGIN_TRUST_REMOVE,
            DIAGNOSTICS_READ,
            DIAGNOSTICS_EXPORT,
            CONFIG_READ,
            CONFIG_UPDATE,
            SITE_LIST,
            SITE_PUT,
            SITE_DELETE,
            SITE_CREDENTIAL_SET,
            SITE_CREDENTIAL_READ,
            SITE_CREDENTIAL_CLEAR,
            SITE_LOGIN_START,
            SITE_LOGIN_FINISH,
            SITE_SESSION_READ,
            SITE_SESSION_CLEAR,
            NETWORK_GRANT_LIST,
            NETWORK_GRANT_PUT,
            NETWORK_GRANT_DELETE,
            TOOL_AUTHORIZATION_OPTIONS,
            TOOL_AUTHORIZATION_LIST,
            TOOL_AUTHORIZATION_PUT,
            TOOL_AUTHORIZATION_DELETE);

    private RpcMethods() {}
}
