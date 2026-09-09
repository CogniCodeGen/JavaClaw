package com.javaclaw.desktop.settings;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

import com.javaclaw.api.AgentRole;
import com.javaclaw.api.AgentRoleFileFormat;
import com.javaclaw.api.AgentRoleFilePreview;
import com.javaclaw.api.AgentRoleSpec;
import com.javaclaw.api.PromptOptimizationDraft;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.RoleLifecycle;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.client.RemoteRpcException;
import com.javaclaw.desktop.DesktopConfigurationChange;
import com.javaclaw.desktop.DesktopConfigurationEvents;
import com.javaclaw.desktop.DesktopNotificationSubscription;
import com.javaclaw.desktop.DesktopTestFixtures;
import com.javaclaw.protocol.JsonRpcError;
import com.javaclaw.protocol.ProtocolErrorCode;

/** Role 专用可控 SDK 夹具；延迟响应与乐观锁只用于验证草稿和请求代次，不调用模型。 */
final class RoleSettingsTestGateway {
    final DesktopConfigurationEvents configurationEvents = new DesktopConfigurationEvents();
    final List<AgentRole> roles = new ArrayList<>();
    final List<ProviderEndpoint> providers = new ArrayList<>();
    final Queue<CompletableFuture<List<AgentRole>>> roleReads = new ArrayDeque<>();
    final Queue<CompletableFuture<AgentRoleFilePreview>> previews = new ArrayDeque<>();
    final Queue<CompletableFuture<AgentRole>> writes = new ArrayDeque<>();
    final Queue<CompletableFuture<AgentRole>> imports = new ArrayDeque<>();
    final Queue<CompletableFuture<List<PromptOptimizationDraft>>> optimizationReads = new ArrayDeque<>();
    private final Map<String, AgentRoleFilePreview> previewCatalog = new HashMap<>();
    final CoreSettingsGateway core = proxy(CoreSettingsGateway.class, this::coreRequest);
    final PromptPreviewSettingsGateway prompts = proxy(PromptPreviewSettingsGateway.class, this::promptRequest);
    final PromptOptimizationSettingsGateway optimization =
            proxy(PromptOptimizationSettingsGateway.class, this::optimizationRequest);
    int roleReadCalls;
    int previewCalls;
    int writeCalls;
    int importCalls;
    AgentRoleSpec lastWrittenSpec;
    CommandOptions lastWriteOptions;
    CommandOptions lastImportOptions;
    String lastImportedPreview;

    RoleSettingsTestGateway() {
        roles.add(role("reviewer", 1));
        providers.add(TestCoreSettingsFixtures.provider(
                2, TestCoreSettingsFixtures.providerSpec(Optional.empty()), ProviderLifecycle.ACTIVE));
    }

    private Object coreRequest(Method method, Object[] args) {
        return switch (method.getName()) {
            case "onConfigurationChanged" -> subscribe(args[0]);
            case "roles" -> readRoles();
            case "providers" -> completed(List.copyOf(providers));
            case "createRole" ->
                write((String) args[0], (AgentRoleSpec) args[1], RoleLifecycle.ACTIVE, (CommandOptions) args[2]);
            case "updateRole" ->
                write((String) args[0], (AgentRoleSpec) args[1], (RoleLifecycle) args[2], (CommandOptions) args[3]);
            case "previewRoleImport" -> preview((String) args[0], (AgentRoleFileFormat) args[2]);
            case "commitRoleImport" -> commit((String) args[0], (CommandOptions) args[2]);
            default -> throw new AssertionError("未预期的 Role SDK 调用：" + method.getName());
        };
    }

    @SuppressWarnings("unchecked")
    private DesktopNotificationSubscription subscribe(Object listener) {
        return configurationEvents.subscribe((Consumer<DesktopConfigurationChange>) listener);
    }

    private CompletionStage<List<AgentRole>> readRoles() {
        roleReadCalls++;
        return roleReads.isEmpty() ? completed(List.copyOf(roles)) : roleReads.remove();
    }

    private CompletionStage<AgentRole> write(
            String id, AgentRoleSpec spec, RoleLifecycle lifecycle, CommandOptions options) {
        writeCalls++;
        lastWrittenSpec = spec;
        lastWriteOptions = options;
        if (!writes.isEmpty()) {
            return writes.remove();
        }
        return save(id, spec, lifecycle, options);
    }

    private CompletionStage<AgentRole> save(
            String id, AgentRoleSpec spec, RoleLifecycle lifecycle, CommandOptions options) {
        long actual = roles.stream()
                .filter(role -> role.id().equals(id))
                .mapToLong(AgentRole::revision)
                .findFirst()
                .orElse(0L);
        if (actual != options.expectedRevision()) {
            return CompletableFuture.failedFuture(conflict());
        }
        AgentRole saved =
                new AgentRole(id, actual + 1, lifecycle, spec, false, DesktopTestFixtures.NOW, DesktopTestFixtures.NOW);
        roles.removeIf(role -> role.id().equals(id));
        roles.add(saved);
        return completed(saved);
    }

    private CompletionStage<AgentRoleFilePreview> preview(String id, AgentRoleFileFormat format) {
        previewCalls++;
        CompletionStage<AgentRoleFilePreview> result =
                previews.isEmpty() ? completed(preview(id, "preview-" + previewCalls, format)) : previews.remove();
        return result.thenApply(value -> {
            previewCatalog.put(value.previewId(), value);
            return value;
        });
    }

    private CompletionStage<AgentRole> commit(String previewId, CommandOptions options) {
        importCalls++;
        lastImportedPreview = previewId;
        lastImportOptions = options;
        if (!imports.isEmpty()) {
            return imports.remove();
        }
        AgentRoleFilePreview preview = previewCatalog.get(previewId);
        return save(preview.roleId(), preview.spec(), RoleLifecycle.ACTIVE, options);
    }

    private Object promptRequest(Method method, Object[] args) {
        if (method.getName().equals("workspaces")) {
            return completed(List.of(DesktopTestFixtures.workspace()));
        }
        throw new AssertionError("此回归不应请求模型提示词预览");
    }

    private Object optimizationRequest(Method method, Object[] args) {
        return switch (method.getName()) {
            case "workspaces" -> completed(List.of(DesktopTestFixtures.workspace()));
            case "list" -> optimizationReads.isEmpty() ? completed(List.of()) : optimizationReads.remove();
            default -> throw new AssertionError("此回归不应启动或修改模型优化任务");
        };
    }

    static AgentRole role(String id, long revision) {
        return new AgentRole(
                id,
                revision,
                RoleLifecycle.ACTIVE,
                TestCoreSettingsFixtures.profileSpec(),
                false,
                DesktopTestFixtures.NOW,
                DesktopTestFixtures.NOW);
    }

    static AgentRoleFilePreview preview(String id, String previewId, AgentRoleFileFormat format) {
        return new AgentRoleFilePreview(
                previewId,
                id,
                TestCoreSettingsFixtures.profileSpec(),
                "a".repeat(64),
                Optional.empty(),
                List.of("developerInstructions"),
                format);
    }

    static RemoteRpcException conflict() {
        return new RemoteRpcException(new JsonRpcError(ProtocolErrorCode.REVISION_CONFLICT, "版本冲突", Optional.empty()));
    }

    private static <T> CompletableFuture<T> completed(T value) {
        return CompletableFuture.completedFuture(value);
    }

    private static <T> T proxy(Class<T> type, Request request) {
        return type.cast(Proxy.newProxyInstance(
                type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> request.invoke(method, args)));
    }

    private interface Request {
        Object invoke(Method method, Object[] args);
    }
}
