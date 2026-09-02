package com.javaclaw.protocol;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JavaClaw 5.0 的精简 Core 与通用 Extension 方法目录。 */
public final class MethodCatalog {
    private static final Map<String, RpcMethod> METHODS = create();

    private MethodCatalog() {}

    /**
     * 返回稳定排序的方法目录。
     *
     * @return 不可变目录
     */
    public static List<RpcMethod> methods() {
        return METHODS.values().stream().toList();
    }

    /**
     * 要求方法存在且能力已协商。
     *
     * @param methodName 方法名
     * @param capabilities 会话能力
     * @return 方法描述
     */
    public static RpcMethod require(String methodName, NegotiatedCapabilities capabilities) {
        RpcMethod method = METHODS.get(methodName);
        if (method == null) {
            throw new ProtocolException(ProtocolErrorCode.METHOD_NOT_FOUND, "unknown method: " + methodName);
        }
        method.capability().ifPresent(capabilities::require);
        return method;
    }

    private static Map<String, RpcMethod> create() {
        LinkedHashMap<String, RpcMethod> methods = new LinkedHashMap<>();
        addPlatformMethods(methods);
        addInteractionAndExtensionMethods(methods);
        return Map.copyOf(methods);
    }

    private static void addPlatformMethods(Map<String, RpcMethod> methods) {
        add(methods, "initialize/session", RpcMethodKind.COMMAND);
        add(methods, "workspace/list", RpcMethodKind.QUERY);
        add(methods, "workspace/create", RpcMethodKind.COMMAND);
        add(methods, "workspace/rename", RpcMethodKind.COMMAND);
        add(methods, "workspace/archive", RpcMethodKind.COMMAND);
        add(methods, "workspace/instructions/read", RpcMethodKind.QUERY);
        add(methods, "workspace/instructions/settings/read", RpcMethodKind.QUERY);
        add(methods, "workspace/instructions/settings/update", RpcMethodKind.COMMAND);
        add(methods, "thread/list", RpcMethodKind.QUERY);
        add(methods, "thread/create", RpcMethodKind.COMMAND);
        add(methods, "thread/read", RpcMethodKind.QUERY);
        add(methods, "turn/start", RpcMethodKind.COMMAND);
        add(methods, "turn/read", RpcMethodKind.QUERY);
        add(methods, "turn/cancel", RpcMethodKind.COMMAND);
        add(methods, "turn/input/list", RpcMethodKind.QUERY);
        add(methods, "turn/input/resolve", RpcMethodKind.COMMAND);
        add(methods, "item/list", RpcMethodKind.QUERY);
        addAttachmentMethods(methods);
        addCredentialMethods(methods);
        add(methods, "profile/list", RpcMethodKind.QUERY);
        add(methods, "profile/read", RpcMethodKind.QUERY);
        add(methods, "profile/create", RpcMethodKind.COMMAND);
        add(methods, "profile/update", RpcMethodKind.COMMAND);
        add(methods, "profile/archive", RpcMethodKind.COMMAND);
        add(methods, "profile/prompt/preview", RpcMethodKind.QUERY);
        addPromptOptimizationMethods(methods);
        add(methods, "profile/binding/read", RpcMethodKind.QUERY);
        add(methods, "profile/binding/update", RpcMethodKind.COMMAND);
        add(methods, "provider/list", RpcMethodKind.QUERY);
        add(methods, "provider/read", RpcMethodKind.QUERY);
        add(methods, "provider/create", RpcMethodKind.COMMAND);
        add(methods, "provider/update", RpcMethodKind.COMMAND);
        add(methods, "provider/archive", RpcMethodKind.COMMAND);
        add(methods, "provider/credential/set", RpcMethodKind.COMMAND);
        add(methods, "provider/credential/clear", RpcMethodKind.COMMAND);
        add(methods, "provider/status", RpcMethodKind.QUERY);
        add(methods, "provider/probe", RpcMethodKind.QUERY);
        add(methods, ProviderVerificationRpcContracts.METHOD, RpcMethodKind.COMMAND);
        addPermissionProfileMethods(methods);
    }

    private static void addAttachmentMethods(Map<String, RpcMethod> methods) {
        add(methods, "attachment/upload/begin", RpcMethodKind.COMMAND);
        add(methods, "attachment/upload/chunk", RpcMethodKind.COMMAND);
        add(methods, "attachment/upload/complete", RpcMethodKind.COMMAND);
        add(methods, "attachment/upload/abort", RpcMethodKind.COMMAND);
        add(methods, "attachment/upload/read", RpcMethodKind.QUERY);
        add(methods, "attachment/read", RpcMethodKind.QUERY);
    }

    private static void addPromptOptimizationMethods(Map<String, RpcMethod> methods) {
        add(methods, "profile/prompt/optimization/start", RpcMethodKind.COMMAND);
        add(methods, "profile/prompt/optimization/read", RpcMethodKind.QUERY);
        add(methods, "profile/prompt/optimization/list", RpcMethodKind.QUERY);
        add(methods, "profile/prompt/optimization/cancel", RpcMethodKind.COMMAND);
        add(methods, "profile/prompt/optimization/adopt", RpcMethodKind.COMMAND);
    }

    private static void addInteractionAndExtensionMethods(Map<String, RpcMethod> methods) {
        addSecurityGrantMethods(methods);
        addMcpMethods(methods);
        add(methods, "approval/list", RpcMethodKind.QUERY);
        add(methods, "approval/resolve", RpcMethodKind.COMMAND);
        add(methods, "extension/list", RpcMethodKind.QUERY);
        add(methods, "extension/query", RpcMethodKind.QUERY);
        add(methods, "extension/command", RpcMethodKind.COMMAND);
        add(methods, "extension/schema/read", RpcMethodKind.QUERY);
        add(methods, "extension/view/list", RpcMethodKind.QUERY);
        add(methods, "extension/job/list", RpcMethodKind.QUERY);
        add(methods, "extension/job/read", RpcMethodKind.QUERY);
        add(methods, "extension/job/pause", RpcMethodKind.COMMAND);
        add(methods, "extension/job/resume", RpcMethodKind.COMMAND);
        add(methods, "extension/job/cancel", RpcMethodKind.COMMAND);
        addBuiltinExtensionMethods(methods);
        addBundleMethods(methods);
        add(methods, "tool/search", RpcMethodKind.QUERY);
        add(methods, "worktree/list", RpcMethodKind.QUERY);
        add(methods, "worktree/read", RpcMethodKind.QUERY);
        add(methods, "worktree/interrupt", RpcMethodKind.COMMAND);
        add(methods, "worktree/patch/export", RpcMethodKind.COMMAND);
        add(methods, "worktree/backup", RpcMethodKind.COMMAND);
        add(methods, "worktree/cleanup", RpcMethodKind.COMMAND);
        add(methods, "diagnostics/read", RpcMethodKind.QUERY);
        add(methods, "diagnostics/loginStartup/repair", RpcMethodKind.COMMAND);
        add(methods, "diagnostics/launcher/read", RpcMethodKind.QUERY);
        add(methods, "diagnostics/server/stop", RpcMethodKind.COMMAND);
        add(methods, "thread/rollout/export", RpcMethodKind.COMMAND);
        add(methods, "extension/event", RpcMethodKind.NOTIFICATION);
    }

    private static void addBuiltinExtensionMethods(Map<String, RpcMethod> methods) {
        add(methods, "extension/builtin/list", RpcMethodKind.QUERY);
        add(methods, "extension/builtin/read", RpcMethodKind.QUERY);
        add(methods, "extension/builtin/enable", RpcMethodKind.COMMAND);
        add(methods, "extension/builtin/disable", RpcMethodKind.COMMAND);
    }

    private static void addBundleMethods(Map<String, RpcMethod> methods) {
        add(methods, "extension/bundle/list", RpcMethodKind.QUERY);
        add(methods, "extension/bundle/read", RpcMethodKind.QUERY);
        add(methods, "extension/bundle/stage", RpcMethodKind.COMMAND);
        add(methods, "extension/bundle/install", RpcMethodKind.COMMAND);
        add(methods, "extension/bundle/upgrade", RpcMethodKind.COMMAND);
        add(methods, "extension/bundle/health/probe", RpcMethodKind.COMMAND);
        add(methods, "extension/bundle/enable", RpcMethodKind.COMMAND);
        add(methods, "extension/bundle/disable", RpcMethodKind.COMMAND);
        add(methods, "extension/bundle/uninstall", RpcMethodKind.COMMAND);
        add(methods, "extension/bundle/trash/list", RpcMethodKind.QUERY);
        add(methods, "extension/bundle/trash/read", RpcMethodKind.QUERY);
        add(methods, "extension/bundle/trash/restore", RpcMethodKind.COMMAND);
        add(methods, "extension/bundle/trash/purge", RpcMethodKind.COMMAND);
        add(methods, "extension/trustKey/list", RpcMethodKind.QUERY);
        add(methods, "extension/trustKey/read", RpcMethodKind.QUERY);
        add(methods, "extension/trustKey/import", RpcMethodKind.COMMAND);
        add(methods, "extension/trustKey/revoke", RpcMethodKind.COMMAND);
    }

    private static void addMcpMethods(Map<String, RpcMethod> methods) {
        add(methods, "mcp/endpoint/list", RpcMethodKind.QUERY);
        add(methods, "mcp/endpoint/read", RpcMethodKind.QUERY);
        add(methods, "mcp/endpoint/history", RpcMethodKind.QUERY);
        add(methods, "mcp/endpoint/create", RpcMethodKind.COMMAND);
        add(methods, "mcp/endpoint/update", RpcMethodKind.COMMAND);
        add(methods, "mcp/stdio/register", RpcMethodKind.COMMAND);
        add(methods, "mcp/endpoint/enable", RpcMethodKind.COMMAND);
        add(methods, "mcp/endpoint/disable", RpcMethodKind.COMMAND);
        add(methods, "mcp/health/read", RpcMethodKind.QUERY);
        add(methods, "mcp/health/probe", RpcMethodKind.QUERY);
        add(methods, "mcp/catalog/list", RpcMethodKind.QUERY);
        add(methods, "mcp/catalog/refresh/read", RpcMethodKind.QUERY);
        add(methods, "mcp/catalog/refresh", RpcMethodKind.COMMAND);
        add(methods, "mcp/resource/list", RpcMethodKind.QUERY);
        add(methods, "mcp/resource/read", RpcMethodKind.QUERY);
        add(methods, "mcp/prompt/list", RpcMethodKind.QUERY);
        add(methods, "mcp/prompt/get", RpcMethodKind.QUERY);
        add(methods, "mcp/oauth/read", RpcMethodKind.QUERY);
        add(methods, "mcp/oauth/start", RpcMethodKind.COMMAND);
        add(methods, "mcp/oauth/cancel", RpcMethodKind.COMMAND);
    }

    private static void addSecurityGrantMethods(Map<String, RpcMethod> methods) {
        add(methods, "privateNetworkGrant/preview", RpcMethodKind.QUERY);
        add(methods, "privateNetworkGrant/list", RpcMethodKind.QUERY);
        add(methods, "privateNetworkGrant/history", RpcMethodKind.QUERY);
        add(methods, "privateNetworkGrant/create", RpcMethodKind.COMMAND);
        add(methods, "privateNetworkGrant/revoke", RpcMethodKind.COMMAND);
        add(methods, "unattendedToolGrant/list", RpcMethodKind.QUERY);
        add(methods, "unattendedToolGrant/history", RpcMethodKind.QUERY);
        add(methods, "unattendedToolGrant/create", RpcMethodKind.COMMAND);
        add(methods, "unattendedToolGrant/revoke", RpcMethodKind.COMMAND);
        add(methods, "permissionDecision/list", RpcMethodKind.QUERY);
    }

    private static void addCredentialMethods(Map<String, RpcMethod> methods) {
        add(methods, "credential/status", RpcMethodKind.QUERY);
        add(methods, "credential/read", RpcMethodKind.QUERY);
        add(methods, "credential/list", RpcMethodKind.QUERY);
        add(methods, "credential/create", RpcMethodKind.COMMAND);
        add(methods, "credential/rotate", RpcMethodKind.COMMAND);
        add(methods, "credential/clear", RpcMethodKind.COMMAND);
        add(methods, "vault/refresh", RpcMethodKind.QUERY);
        add(methods, "vault/masterKey/rotate", RpcMethodKind.COMMAND);
        add(methods, "vault/reset", RpcMethodKind.COMMAND);
    }

    private static void addPermissionProfileMethods(Map<String, RpcMethod> methods) {
        add(methods, "permissionProfile/list", RpcMethodKind.QUERY);
        add(methods, "permissionProfile/read", RpcMethodKind.QUERY);
        add(methods, "permissionProfile/history", RpcMethodKind.QUERY);
        add(methods, "permissionProfile/clone", RpcMethodKind.COMMAND);
        add(methods, "permissionProfile/update", RpcMethodKind.COMMAND);
        add(methods, "permissionProfile/diff", RpcMethodKind.QUERY);
        add(methods, "permissionProfile/effectivePreview", RpcMethodKind.QUERY);
    }

    private static void add(Map<String, RpcMethod> methods, String name, RpcMethodKind kind) {
        methods.put(name, new RpcMethod(name, kind, java.util.Optional.empty(), false));
    }
}
