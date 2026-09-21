package com.javaclaw.browser.client;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.builtin.contracts.BrowserContracts;
import com.javaclaw.builtin.contracts.SiteRegistrationContracts;

/** 仅用于用户主动添加网站的 Workspace 临时浏览器边界，不具有 Thread 或 Turn 执行身份。 */
public interface BrowserRegistrationPort {
    /**
     * @param task 唯一会话、Workspace、地址及 HUMAN 租约
     * @param network 每次请求重新授权的 Broker
     * @param cancellation 启动取消信号
     * @return 已打开的脱敏会话
     */
    SiteRegistrationContracts.WorkerStatus begin(
            SiteRegistrationContracts.WorkerTask task,
            InteractiveBrowserNetworkExchange network,
            CancellationToken cancellation);

    /**
     * @param sessionId 登记身份
     * @return 实际向 Worker 查询的当前状态，不使用页面缓存代替
     */
    SiteRegistrationContracts.WorkerStatus status(String sessionId);

    /**
     * @param sessionId 登记身份
     * @param lease 用户明确授权后的下一代 HUMAN 租约
     * @param cancellation 更新取消信号
     * @return Worker 确认的当前授权与页面
     */
    SiteRegistrationContracts.WorkerStatus updateLease(
            String sessionId, BrowserContracts.AccessLease lease, CancellationToken cancellation);

    /**
     * @param sessionId 登记身份
     * @param request 用户确认的授权、页面版本及可选凭据候选
     * @param handler 私有密封与事务提交回调
     * @param <T> 非敏感回执类型
     * @return 一次回调的结果；失败不自动重放，完成后回收进程树
     */
    <T> T complete(
            String sessionId, SiteRegistrationContracts.CompleteRequest request, BrowserRegistrationHandler<T> handler);

    /**
     * @param sessionId 登记身份
     * @return 已取消的状态；终止进程树并丢弃全部临时秘密
     */
    SiteRegistrationContracts.WorkerStatus cancel(String sessionId);
}
