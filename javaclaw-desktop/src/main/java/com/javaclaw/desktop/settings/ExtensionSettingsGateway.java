package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import com.javaclaw.api.AttachmentRef;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.SiteAccountContracts;
import com.javaclaw.builtin.contracts.SiteRegistrationContracts;
import com.javaclaw.client.CommandOptions;
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
     * 执行 GraphBrowsing 声明的只读查询，不创建 Turn 或扩大文件权限。
     *
     * @param workspaceId 用户动作冻结的工作区
     * @param extensionId 已启用扩展
     * @param operation schema 中的固定查询标识
     * @param arguments 受限图谱参数
     * @return 查询结果；旧边界默认拒绝
     */
    default CompletableFuture<ExtensionRpcContracts.CallResult> query(
            WorkspaceId workspaceId, String extensionId, String operation, CanonicalPayload arguments) {
        return CompletableFuture.failedFuture(new IllegalStateException("当前边界不支持图谱浏览"));
    }

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
     * 通过 SDK 密封后原子保存网站账号密码，不经普通 ViewSchema command。
     *
     * @param workspaceId 用户动作冻结的 Workspace
     * @param request 账号与安全版本
     * @param username 临时用户名；实现须复制后异步使用
     * @param password 临时密码；实现须复制后异步使用
     * @param options 当前账号版本和幂等身份
     * @return 脱敏账号状态，旧边界默认拒绝
     */
    default CompletableFuture<SiteAccountContracts.AccountProjection> setAccountCredential(
            WorkspaceId workspaceId,
            SiteAccountContracts.CredentialRequest request,
            char[] username,
            char[] password,
            CommandOptions options) {
        return CompletableFuture.failedFuture(new IllegalStateException("当前边界不支持密封账号命令"));
    }

    /**
     * 在用户冻结的工作区启动网站登记，浏览器由原生宿主打开。
     *
     * @param workspace 固定工作区
     * @param request 用户输入的 HTTPS 地址
     * @param options 本次启动的稳定幂等身份
     * @return 不含秘密的登记会话
     */
    default CompletableFuture<SiteRegistrationContracts.Session> beginRegistration(
            WorkspaceId workspace, SiteRegistrationContracts.BeginRequest request, CommandOptions options) {
        return CompletableFuture.failedFuture(new IllegalStateException("当前边界不支持网站登记"));
    }

    /**
     * 读取会话的脱敏页面、候选和完成状态。
     *
     * @param workspace 原始工作区
     * @param request 原始会话身份
     * @return 权威会话；不会通过查询重试写操作
     */
    default CompletableFuture<SiteRegistrationContracts.Session> registrationStatus(
            WorkspaceId workspace, SiteRegistrationContracts.SessionRequest request) {
        return CompletableFuture.failedFuture(new IllegalStateException("当前边界不支持网站登记"));
    }

    /**
     * 添加用户明确输入的额外来源，不信任浏览器自动发现的来源。
     *
     * @param workspace 原始工作区
     * @param request 会话代次和精确 HTTPS 来源
     * @param options 本次授权的稳定幂等身份
     * @return 更新后的会话
     */
    default CompletableFuture<SiteRegistrationContracts.Session> allowRegistrationOrigin(
            WorkspaceId workspace, SiteRegistrationContracts.OriginRequest request, CommandOptions options) {
        return CompletableFuture.failedFuture(new IllegalStateException("当前边界不支持网站登记"));
    }

    /**
     * 将网站、默认账号、选定候选和登录态一起登记；秘密不经过 Desktop。
     *
     * @param workspace 原始工作区
     * @param request 用户确认的页面版本、名称和可选凭据候选
     * @param options 本次提交的稳定幂等身份
     * @return 脱敏完成结果；不明结果只能查询恢复
     */
    default CompletableFuture<SiteRegistrationContracts.Session> completeRegistration(
            WorkspaceId workspace, SiteRegistrationContracts.CompleteRequest request, CommandOptions options) {
        return CompletableFuture.failedFuture(new IllegalStateException("当前边界不支持网站登记"));
    }

    /**
     * 取消未完成登记并释放临时会话；不会删除已完成网站。
     *
     * @param workspace 原始工作区
     * @param request 原始会话身份
     * @param options 本次取消的稳定幂等身份
     * @return 脱敏会话终态
     */
    default CompletableFuture<SiteRegistrationContracts.Session> cancelRegistration(
            WorkspaceId workspace, SiteRegistrationContracts.SessionRequest request, CommandOptions options) {
        return CompletableFuture.failedFuture(new IllegalStateException("当前边界不支持网站登记"));
    }

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
