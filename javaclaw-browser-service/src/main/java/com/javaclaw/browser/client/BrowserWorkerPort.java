package com.javaclaw.browser.client;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.builtin.contracts.SiteContracts;

/**
 * 宿主调用隔离 Browser Worker 的最小边界。
 *
 * <p>实现必须把所有 HTTP 请求反向交给 {@link BrowserNetworkExchange}，不得在 Worker 内自行联网；传入的 Browser storage state 只允许进入 Worker
 * 私有管道，且不得出现在返回值、日志或 Artifact 中。
 */
public interface BrowserWorkerPort extends AutoCloseable {
    /**
     * 执行一个受 Site authority 约束的页面快照。
     *
     * @param task 已冻结的 Site 与页面请求
     * @param storageState Vault 解密后的短生命周期 Browser state；无状态时为空数组
     * @param network 每次请求都重新授权的宿主 Broker 回调
     * @param cancellation 调用取消信号
     * @return 只包含脱敏页面内容的规范 payload
     */
    CanonicalPayload snapshot(
            SiteContracts.SnapshotTask task,
            byte[] storageState,
            BrowserNetworkExchange network,
            CancellationToken cancellation);

    /**
     * 启动最多十分钟的隔离人工登录窗口。
     *
     * @param task 已冻结的 Site authority 与稳定会话标识
     * @param storageState 可选的既有 Browser state，只进入 Worker 私有帧
     * @param network 每次请求都重新授权的宿主 Broker 回调
     * @param cancellation 启动阶段取消信号
     * @return 不含页面 URL、Cookie 或截图的会话状态
     */
    SiteContracts.LoginSession beginLogin(
            SiteContracts.LoginBeginTask task,
            byte[] storageState,
            BrowserNetworkExchange network,
            CancellationToken cancellation);

    /**
     * 读取一个登录会话的脱敏状态。
     *
     * @param sessionId 会话 UUID
     * @return 权威内存状态
     */
    SiteContracts.LoginSession loginStatus(String sessionId);

    /**
     * 要求 Worker 遮罩敏感表单并通过私有二进制帧返回 storage state。
     *
     * @param sessionId 会话 UUID
     * @param handler 直接密封状态的宿主回调
     * @param <T> 非敏感处理结果
     * @return 回调结果
     */
    <T> T saveLogin(String sessionId, BrowserStorageHandler<T> handler);

    /**
     * 取消会话并终止隔离进程。
     *
     * @param sessionId 会话 UUID
     * @return 取消后的终态
     */
    SiteContracts.LoginSession cancelLogin(String sessionId);

    /**
     * 当前发行镜像是否显式允许人工登录。
     *
     * @return 只有三平台原生 Runner 写入验证标记后才为 true
     */
    boolean interactiveLoginAvailable();

    /**
     * 启动隔离 OAuth 窗口；callback 只通过当前进程私有回调直接交 App Server。
     *
     * @param task 已冻结授权 URI、Origin 与 Endpoint revision
     * @param network 每次 HTTPS 请求都重新授权的宿主 Broker 回调
     * @param callback 不可记录、不可转发 RPC 的 callback 处理器
     * @param cancellation 启动阶段取消信号
     * @return 不含 URL、code、state 或 token 的会话状态
     */
    McpOAuthBrowserSession beginOAuth(
            McpOAuthBrowserTask task,
            BrowserNetworkExchange network,
            McpOAuthCallbackHandler callback,
            CancellationToken cancellation);

    /** @param sessionId Worker 控制 UUID @return OAuth 浏览器状态 */
    McpOAuthBrowserSession oauthStatus(String sessionId);

    /** @param sessionId Worker 控制 UUID @return 取消后的状态 */
    McpOAuthBrowserSession cancelOAuth(String sessionId);

    /**
     * 终止仍绑定旧 Endpoint revision 的 OAuth Worker。
     *
     * @param endpointId Endpoint 标识
     * @param currentRevision 当前 revision；删除时为 0
     */
    void invalidateOAuth(String endpointId, long currentRevision);

    /** @return 发行镜像是否已由原生 Runner 验证隔离 OAuth 窗口 */
    boolean oauthAvailable();

    /**
     * 终止指定 Site 中仍绑定旧 authority revision 的进程。
     *
     * @param siteId Site 标识
     * @param currentAuthorityRevision 当前 authority revision；删除时为 0
     */
    void invalidate(String siteId, long currentAuthorityRevision);

    /** 终止全部活动 Worker；必须幂等。 */
    @Override
    void close();
}
