package com.javaclaw.desktop.settings;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

import com.javaclaw.api.ExecutionConfiguration;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.ExecutionPreview;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.desktop.DesktopConfigurationChange;
import com.javaclaw.desktop.DesktopNotificationSubscription;

/** 聊天配置与模型向导的异步 SDK 边界；完成回调均在 UI 调度器上执行。 */
public interface ChatConfigurationGateway {
    /** @param listener 配置失效监听者 @return 不拥有 SDK 会话的可关闭订阅 */
    default DesktopNotificationSubscription onConfigurationChanged(Consumer<DesktopConfigurationChange> listener) {
        return () -> {};
    }

    /**
     * 保存固定对话的完整覆盖，不能用新目录 revision 替代草稿的原版本。
     *
     * @param workspaceId 固定工作区 @param threadId 固定对话 @param execution 完整覆盖
     * @param options 原版本和幂等键 @return 权威保存结果
     */
    default CompletionStage<ExecutionConfiguration> updateThreadExecution(
            WorkspaceId workspaceId, ThreadId threadId, ExecutionOverrides execution, CommandOptions options) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("会话配置更新不可用"));
    }

    /**
     * 预览当前配置，不读取 Prompt、不调用模型；发送仍由服务端重新校验。
     *
     * @param workspaceId 固定工作区 @param threadId 可选对话 @param execution 临时覆盖
     * @return 实际选择、锁定原因与阻塞项
     */
    default CompletionStage<ExecutionPreview> previewChatExecution(
            WorkspaceId workspaceId, Optional<ThreadId> threadId, ExecutionOverrides execution) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("执行配置预览不可用"));
    }

    /**
     * 将精确模型用于目标工作区的对话，同时记为新对话默认值；必要时创建对话并导航。
     *
     * @param workspaceId 固定工作区，缺省时必须先选择 @param threadId 可复用的固定对话
     * @param model 已保存的精确模型引用 @return 应用和导航完成；失败时已保存模型仍保留
     */
    default CompletionStage<Void> useModel(
            Optional<WorkspaceId> workspaceId, Optional<ThreadId> threadId, ProviderRef model) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("模型应用不可用"));
    }

    /**
     * 保存当前对话选择，并仅将模型与思考记为日常默认。
     *
     * @param workspaceId 固定工作区 @param threadId 固定对话 @param execution 完整对话覆盖
     * @param options 草稿基线版本与幂等键 @param remember 是否更新日常模型和思考默认值
     * @return 当前对话的权威保存结果
     */
    default CompletionStage<ExecutionConfiguration> rememberChatSelection(
            WorkspaceId workspaceId,
            ThreadId threadId,
            ExecutionOverrides execution,
            CommandOptions options,
            boolean remember) {
        return updateThreadExecution(workspaceId, threadId, execution, options);
    }

    /** @param name 工作区名称 @param root 用户选择的绝对目录 @return 已创建的工作区 */
    default CompletionStage<Workspace> createModelWorkspace(String name, Path root) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("创建工作区不可用"));
    }
}
