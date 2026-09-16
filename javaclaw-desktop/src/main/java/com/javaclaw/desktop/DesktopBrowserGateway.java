package com.javaclaw.desktop;

import java.net.URI;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import com.javaclaw.api.ThreadId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.BrowserCommands;
import com.javaclaw.builtin.contracts.BrowserContracts;
import com.javaclaw.builtin.contracts.BrowserGrantContracts;
import com.javaclaw.builtin.contracts.SiteAccountContracts;
import com.javaclaw.client.extension.BrowserClient;

/** 聊天浏览器控制栏的异步 SDK 边界；完成和事件均回到 JavaFX 调度器。 */
public interface DesktopBrowserGateway {
    /**
     * @param workspace 冻结的 Workspace
     * @param thread 冻结的 Thread
     */
    record Scope(WorkspaceId workspace, ThreadId thread) {
        /** 不允许隐式使用后来选中的对话。 */
        public Scope {
            Objects.requireNonNull(workspace, "workspace");
            Objects.requireNonNull(thread, "thread");
        }
    }

    /**
     * @param scope 已冻结对话
     * @return 不含页面正文的权威状态
     */
    CompletableFuture<BrowserCommands.Status> status(Scope scope);

    /**
     * @param scope 用户点击时的对话
     * @param control 明确的控制权操作
     * @param generation 用户看到的租约代次
     * @return 操作后权威状态
     */
    CompletableFuture<BrowserCommands.Status> control(Scope scope, BrowserClient.Control control, long generation);

    /**
     * @param scope 人工操作的对话
     * @param generation 人工租约代次
     * @return 当前脱敏登录表单
     */
    CompletableFuture<BrowserCommands.LoginForms> loginForms(Scope scope, long generation);

    /**
     * @param scope 人工操作的对话
     * @param generation 人工租约代次
     * @param target 用户明确选择的表单
     * @return 已保存账号的脱敏状态
     */
    CompletableFuture<SiteAccountContracts.AccountProjection> capture(
            Scope scope, long generation, BrowserContracts.CredentialsTarget target);

    /**
     * @param scope 人工操作的对话
     * @param generation 人工租约代次
     * @return 保持登录后的账号状态
     */
    CompletableFuture<SiteAccountContracts.AccountProjection> saveLogin(Scope scope, long generation);

    /**
     * @param scope 用户管理授权的当前对话
     * @return 当前对话的最新来源授权及撤销状态
     */
    CompletableFuture<BrowserGrantContracts.GrantList> grants(Scope scope);

    /**
     * @param scope 用户输入来源时冻结的对话
     * @param origin 精确 HTTPS 来源
     * @return 服务端签发的有时限确认预览；不会授予权限
     */
    CompletableFuture<BrowserGrantContracts.Preview> previewGrant(Scope scope, URI origin);

    /**
     * @param scope 用户确认时仍有效的对话
     * @param preview 用户看到并确认的完整服务端预览
     * @return 已持久授权
     */
    CompletableFuture<BrowserGrantContracts.Grant> confirmGrant(Scope scope, BrowserGrantContracts.Preview preview);

    /**
     * @param scope 用户撤销时仍有效的对话
     * @param grant 用户看到的授权身份和安全版本
     * @return 已持久撤销版本
     */
    CompletableFuture<BrowserGrantContracts.Grant> revokeGrant(Scope scope, BrowserGrantContracts.Grant grant);

    /**
     * @param scope 已冻结对话
     * @param invalidated 只提示重读，不合并通知正文
     * @return 取消句柄
     */
    DesktopNotificationSubscription subscribe(Scope scope, Runnable invalidated);
}
