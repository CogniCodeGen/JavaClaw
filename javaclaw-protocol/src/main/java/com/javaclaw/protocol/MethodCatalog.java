package com.javaclaw.protocol;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JavaClaw 6.0 的精简 Core 与通用 Extension 方法目录。 */
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
        addStreamAndPreviewMethods(methods);
        addInteractionAndExtensionMethods(methods);
        return Collections.unmodifiableMap(new LinkedHashMap<>(methods));
    }

    private static void addStreamAndPreviewMethods(Map<String, RpcMethod> methods) {
        for (String name : List.of(
                TurnStreamRpcContracts.SUBSCRIBE,
                TurnStreamRpcContracts.UNSUBSCRIBE,
                TurnStreamRpcContracts.LIST,
                TurnStreamRpcContracts.EVENT)) {
            RpcMethodKind kind = name.equals(TurnStreamRpcContracts.LIST)
                    ? RpcMethodKind.QUERY
                    : name.equals(TurnStreamRpcContracts.EVENT) ? RpcMethodKind.NOTIFICATION : RpcMethodKind.COMMAND;
            methods.put(
                    name, new RpcMethod(name, kind, java.util.Optional.of(TurnStreamRpcContracts.CAPABILITY), false));
        }
        methods.put(
                TurnStreamRpcContracts.ITEM_HISTORY,
                new RpcMethod(
                        TurnStreamRpcContracts.ITEM_HISTORY,
                        RpcMethodKind.QUERY,
                        java.util.Optional.of(TurnStreamRpcContracts.CAPABILITY),
                        false));
        methods.put(
                DocumentPreviewRpcContracts.INVALIDATED,
                new RpcMethod(
                        DocumentPreviewRpcContracts.INVALIDATED,
                        RpcMethodKind.NOTIFICATION,
                        java.util.Optional.of(DocumentPreviewRpcContracts.CAPABILITY),
                        false));
        for (String name : List.of(
                DocumentPreviewRpcContracts.RESOLVE,
                DocumentPreviewRpcContracts.READ,
                DocumentPreviewRpcContracts.RESOURCE,
                DocumentPreviewRpcContracts.RENEW,
                DocumentPreviewRpcContracts.CLOSE)) {
            RpcMethodKind kind =
                    name.equals(DocumentPreviewRpcContracts.READ) ? RpcMethodKind.QUERY : RpcMethodKind.COMMAND;
            methods.put(
                    name,
                    new RpcMethod(name, kind, java.util.Optional.of(DocumentPreviewRpcContracts.CAPABILITY), false));
        }
        for (String name : List.of("attachment/metadata", "attachment/readChunk")) {
            methods.put(
                    name,
                    new RpcMethod(
                            name,
                            RpcMethodKind.QUERY,
                            java.util.Optional.of(DocumentPreviewRpcContracts.CAPABILITY),
                            false));
        }
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
        addRoleAndExecutionMethods(methods);
        add(methods, "agent/spawn", RpcMethodKind.COMMAND);
        add(methods, "agent/wait", RpcMethodKind.QUERY);
        add(methods, "agent/interrupt", RpcMethodKind.COMMAND);
        addPromptOptimizationMethods(methods);
        add(methods, "provider/list", RpcMethodKind.QUERY);
        add(methods, "provider/read", RpcMethodKind.QUERY);
        add(methods, "provider/create", RpcMethodKind.COMMAND);
        add(methods, "provider/update", RpcMethodKind.COMMAND);
        add(methods, "provider/archive", RpcMethodKind.COMMAND);
        add(methods, ProviderModelDiscoveryRpcContracts.START_METHOD, RpcMethodKind.COMMAND);
        add(methods, ProviderModelDiscoveryRpcContracts.READ_METHOD, RpcMethodKind.QUERY);
        add(methods, ProviderModelDiscoveryRpcContracts.CANCEL_METHOD, RpcMethodKind.COMMAND);
        add(methods, "provider/embeddingBinding/read", RpcMethodKind.QUERY);
        add(methods, "provider/embeddingBinding/update", RpcMethodKind.COMMAND);
        addContextMethods(methods);
        add(methods, "provider/credential/set", RpcMethodKind.COMMAND);
        add(methods, "provider/credential/clear", RpcMethodKind.COMMAND);
        add(methods, "provider/status", RpcMethodKind.QUERY);
        add(methods, "provider/probe", RpcMethodKind.QUERY);
        add(methods, ProviderVerificationRpcContracts.METHOD, RpcMethodKind.COMMAND);
        addPermissionProfileMethods(methods);
    }

    private static void addContextMethods(Map<String, RpcMethod> methods) {
        for (RpcMethod method : List.of(
                new RpcMethod(
                        ProviderContextRpcContracts.READ_METHOD,
                        RpcMethodKind.QUERY,
                        java.util.Optional.of(ProviderContextRpcContracts.CAPABILITY),
                        false),
                new RpcMethod(
                        ProviderContextRpcContracts.UPDATE_METHOD,
                        RpcMethodKind.COMMAND,
                        java.util.Optional.of(ProviderContextRpcContracts.CAPABILITY),
                        false))) {
            methods.put(method.name(), method);
        }
    }

    private static void addRoleAndExecutionMethods(Map<String, RpcMethod> methods) {
        add(methods, "agent/role/list", RpcMethodKind.QUERY);
        add(methods, "agent/role/read", RpcMethodKind.QUERY);
        add(methods, "agent/role/create", RpcMethodKind.COMMAND);
        add(methods, "agent/role/update", RpcMethodKind.COMMAND);
        add(methods, "agent/role/archive", RpcMethodKind.COMMAND);
        add(methods, "agent/role/clone", RpcMethodKind.COMMAND);
        add(methods, "agent/role/import/preview", RpcMethodKind.QUERY);
        add(methods, "agent/role/import/commit", RpcMethodKind.COMMAND);
        add(methods, "agent/role/export", RpcMethodKind.QUERY);
        add(methods, "execution/default/read", RpcMethodKind.QUERY);
        add(methods, "execution/preview", RpcMethodKind.QUERY);
        add(methods, "execution/default/update", RpcMethodKind.COMMAND);
        add(methods, "execution/subagent/read", RpcMethodKind.QUERY);
        add(methods, "execution/subagent/update", RpcMethodKind.COMMAND);
        add(methods, "thread/execution/read", RpcMethodKind.QUERY);
        add(methods, "thread/execution/update", RpcMethodKind.COMMAND);
        add(methods, "prompt/manifest/preview", RpcMethodKind.QUERY);
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
        add(methods, "agent/role/prompt/optimization/start", RpcMethodKind.COMMAND);
        add(methods, "agent/role/prompt/optimization/read", RpcMethodKind.QUERY);
        add(methods, "agent/role/prompt/optimization/list", RpcMethodKind.QUERY);
        add(methods, "agent/role/prompt/optimization/cancel", RpcMethodKind.COMMAND);
        add(methods, "agent/role/prompt/optimization/adopt", RpcMethodKind.COMMAND);
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
        add(methods, "permissionProfile/preset/list", RpcMethodKind.QUERY);
        add(methods, "permissionProfile/preset/preview", RpcMethodKind.QUERY);
        add(methods, "permissionProfile/preset/instantiate", RpcMethodKind.COMMAND);
        add(methods, "permissionProfile/read", RpcMethodKind.QUERY);
        add(methods, "permissionProfile/history", RpcMethodKind.QUERY);
        add(methods, "permissionProfile/clone", RpcMethodKind.COMMAND);
        add(methods, "permissionProfile/update", RpcMethodKind.COMMAND);
        add(methods, "permissionProfile/diff", RpcMethodKind.QUERY);
        add(methods, "permissionProfile/effectivePreview", RpcMethodKind.QUERY);
    }

    private static void add(Map<String, RpcMethod> methods, String name, RpcMethodKind kind) {
        RpcMethod previous = methods.putIfAbsent(name, new RpcMethod(name, kind, java.util.Optional.empty(), false));
        if (previous != null) {
            throw new IllegalStateException("duplicate Protocol method: " + name);
        }
    }
}
