package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import com.javaclaw.api.AttachmentRef;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.desktop.DesktopNotificationSubscription;
import com.javaclaw.desktop.view.ViewAttachmentUploadRequest;
import com.javaclaw.desktop.view.ViewCommandInvocation;
import com.javaclaw.desktop.view.ViewData;
import com.javaclaw.desktop.view.ViewLoadRequest;
import com.javaclaw.extension.spi.ViewSchema;
import com.javaclaw.protocol.ExtensionRpcContracts;

/** 设置中心访问声明式扩展页面的异步 SDK 边界。 */
public interface ExtensionSettingsGateway {
    /**
     * 读取已启用扩展的页面目录。
     *
     * @param extensionId 精确扩展标识
     * @return 页面文档；扩展停用时为空
     */
    CompletableFuture<List<ExtensionRpcContracts.ViewDocument>> list(String extensionId);

    /**
     * 读取一个页面的权威数据。
     *
     * @param workspaceId 用户动作发起时已冻结的 Workspace
     * @param document 页面文档
     * @param schema 已通过平台策略校验的 ViewSchema v2
     * @param request 当前分页与选择状态
     * @return 页面数据
     */
    CompletableFuture<ViewData> load(
            WorkspaceId workspaceId,
            ExtensionRpcContracts.ViewDocument document,
            ViewSchema schema,
            ViewLoadRequest request);

    /**
     * 执行页面声明的受限命令。
     *
     * @param workspaceId 用户动作发起时已冻结的 Workspace
     * @param extensionId 扩展标识
     * @param invocation 已绑定 revision 的命令
     * @return 命令结果
     */
    CompletableFuture<ExtensionRpcContracts.CallResult> execute(
            WorkspaceId workspaceId, String extensionId, ViewCommandInvocation invocation);

    /**
     * 使用 SDK 把本地文件有界上传为 Core Attachment。
     *
     * @param workspaceId 选择文件时已冻结的 Workspace
     * @param request 本地平台请求；路径不得进入后续扩展 command
     * @return 无路径 Attachment 引用
     */
    CompletableFuture<AttachmentRef> upload(WorkspaceId workspaceId, ViewAttachmentUploadRequest request);

    /**
     * 订阅一个已冻结 Workspace 中 Extension 的资源失效事件。
     *
     * @param workspaceId 建立订阅时已冻结的 Workspace
     * @param extensionId 精确 Extension 标识
     * @param listener 只接收扩展标识和 Workspace 都匹配的事件
     * @return 幂等取消句柄
     */
    DesktopNotificationSubscription subscribe(
            WorkspaceId workspaceId, String extensionId, Consumer<ExtensionRpcContracts.ExtensionEvent> listener);

    /**
     * 返回始终失败的未连接边界，供本地外观页在 App Server 不可用时继续启动。
     *
     * @return 未连接边界
     */
    static ExtensionSettingsGateway disconnected() {
        return new ExtensionSettingsGateway() {
            @Override
            public CompletableFuture<List<ExtensionRpcContracts.ViewDocument>> list(String extensionId) {
                return failed();
            }

            @Override
            public CompletableFuture<ViewData> load(
                    WorkspaceId workspaceId,
                    ExtensionRpcContracts.ViewDocument document,
                    ViewSchema schema,
                    ViewLoadRequest request) {
                return failed();
            }

            @Override
            public CompletableFuture<ExtensionRpcContracts.CallResult> execute(
                    WorkspaceId workspaceId, String extensionId, ViewCommandInvocation invocation) {
                return failed();
            }

            @Override
            public CompletableFuture<AttachmentRef> upload(
                    WorkspaceId workspaceId, ViewAttachmentUploadRequest request) {
                return failed();
            }

            @Override
            public DesktopNotificationSubscription subscribe(
                    WorkspaceId workspaceId,
                    String extensionId,
                    Consumer<ExtensionRpcContracts.ExtensionEvent> listener) {
                return () -> {};
            }

            private <T> CompletableFuture<T> failed() {
                return CompletableFuture.failedFuture(new IllegalStateException("Desktop 尚未连接 JavaClaw 服务"));
            }
        };
    }
}
