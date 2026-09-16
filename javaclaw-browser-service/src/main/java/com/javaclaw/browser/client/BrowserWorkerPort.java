package com.javaclaw.browser.client;

import java.net.URI;
import java.util.List;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.builtin.contracts.BrowserContracts;
import com.javaclaw.builtin.contracts.SiteContracts;

/**
 * 宿主调用隔离 Browser Worker 的最小边界。
 *
 * <p>实现必须把所有 HTTP 请求反向交给 {@link BrowserNetworkExchange}，不得在 Worker 内自行联网；传入的 Browser storage state 只允许进入 Worker
 * 私有管道，且不得出现在返回值、日志或 Artifact 中。
 */
public interface BrowserWorkerPort extends AutoCloseable {
    /** @return 用户主动网站登记边界；未通过原生可见浏览器验证时明确拒绝 */
    default BrowserRegistrationPort registrations() {
        throw new UnsupportedOperationException("Browser registration is unavailable");
    }

    /**
     * 创建 Thread 独占的可见浏览器；窗口跨 Turn 保留，所有网络须有当前租约。
     *
     * @param task 会话所有权与初始租约
     * @param storageState Vault 解密状态，只进入私有帧
     * @param network 每次请求重新授权的宿主 Broker
     * @param cancellation 启动取消信号
     * @return 初始脱敏页面
     */
    default BrowserActionResult openInteractive(
            BrowserContracts.OpenTask task,
            byte[] storageState,
            InteractiveBrowserNetworkExchange network,
            CancellationToken cancellation) {
        throw new UnsupportedOperationException("Interactive Browser is unavailable");
    }

    /**
     * 在已有会话中串行执行一个动作；不得自动重放业务操作。
     *
     * @param sessionId 宿主已核验的会话
     * @param action 受限页面动作
     * @param privateInput 上传或秘密填充的私有字节，其他操作必须为空
     * @param cancellation 动作取消信号
     * @return 脱敏观察与可选附件
     */
    default BrowserActionResult actInteractive(
            String sessionId, BrowserContracts.Action action, byte[] privateInput, CancellationToken cancellation) {
        throw new UnsupportedOperationException("Interactive Browser is unavailable");
    }

    /**
     * 在 Worker 执行瞬间重新核验冻结租约，阻断接管后迟到的模型操作。
     *
     * @param sessionId 目标会话
     * @param expectedLease 提交时的可信租约
     * @param action 受限页面动作
     * @param privateInput 私有上传字节
     * @param cancellation 取消信号
     * @return 当前租约下的脱敏观察
     */
    default BrowserActionResult actInteractive(
            String sessionId,
            BrowserContracts.AccessLease expectedLease,
            BrowserContracts.Action action,
            byte[] privateInput,
            CancellationToken cancellation) {
        throw new UnsupportedOperationException("Interactive Browser is unavailable");
    }

    /**
     * 更新控制权或来源授权；代次须递增，失效后禁止网络。
     *
     * @param sessionId 目标会话
     * @param lease 宿主授权的新租约
     * @param cancellation 控制操作取消信号
     * @return 更新后的会话状态
     */
    default BrowserContracts.SessionView updateInteractiveLease(
            String sessionId, BrowserContracts.AccessLease lease, CancellationToken cancellation) {
        throw new UnsupportedOperationException("Interactive Browser is unavailable");
    }

    /** @param sessionId 会话标识 @return 不执行页面脚本的会话状态 */
    default BrowserContracts.SessionView interactiveStatus(String sessionId) {
        throw new UnsupportedOperationException("Interactive Browser is unavailable");
    }

    /** @param sessionId 会话标识 @return 关闭后的状态；终止整棵进程树 */
    default BrowserContracts.SessionView closeInteractive(String sessionId) {
        throw new UnsupportedOperationException("Interactive Browser is unavailable");
    }

    /** @return 当前镜像是否具备经过验证的常驻可见浏览器能力 */
    default boolean interactiveAvailable() {
        return false;
    }

    /**
     * 只向私有回调交付当前 Context 的含 IndexedDB 登录状态。
     *
     * @param sessionId 已核验保存授权的会话
     * @param handler 验证保存 lease 并密封状态的回调
     * @param <T> 非敏感回执类型
     * @return 回调结果
     */
    default <T> T saveInteractiveState(String sessionId, BrowserStorageHandler<T> handler) {
        throw new UnsupportedOperationException("Interactive Browser is unavailable");
    }

    /**
     * 用户在人工接管中明确确认后，私有捕获其选中的用户名和密码输入。
     *
     * @param sessionId 已核验保存授权的会话
     * @param expectedLease 用户操作冻结的 HUMAN 租约
     * @param expectedOrigin 账号站点的精确 HTTPS Origin
     * @param target 用户选中的输入引用
     * @param handler 接收 UTF-8 用户名、单个 NUL、UTF-8 密码的密封回调；不得记录原文
     * @param <T> 非敏感回执类型
     * @return 回调结果
     */
    default <T> T captureInteractiveCredentials(
            String sessionId,
            BrowserContracts.AccessLease expectedLease,
            URI expectedOrigin,
            BrowserContracts.CredentialsTarget target,
            BrowserStorageHandler<T> handler) {
        throw new UnsupportedOperationException("Interactive Browser is unavailable");
    }

    /**
     * 用户明确请求后枚举当前页登录表单；不读取或返回输入值，不作为模型工具。
     *
     * @param sessionId 目标会话
     * @param expectedLease 用户操作冻结的 HUMAN 租约
     * @param expectedOrigin 用户确认的精确 HTTPS Origin
     * @return 同页面同表单的字段描述与当前引用
     */
    default List<BrowserContracts.LoginForm> prepareInteractiveCredentials(
            String sessionId, BrowserContracts.AccessLease expectedLease, URI expectedOrigin) {
        throw new UnsupportedOperationException("Interactive Browser is unavailable");
    }

    /**
     * 填充宿主解密的单个秘密字段；值不进入普通 DTO。
     *
     * @param sessionId 目标会话
     * @param target 当前输入引用
     * @param secret UTF-8 私有值
     * @param cancellation 取消信号
     * @return 不含秘密的页面观察
     */
    default BrowserActionResult fillInteractiveSecret(
            String sessionId, BrowserContracts.Target target, byte[] secret, CancellationToken cancellation) {
        return actInteractive(
                sessionId,
                new BrowserContracts.Action(
                        BrowserContracts.Operation.FILL_SECRET, target, BrowserContracts.ActionInput.text("")),
                secret,
                cancellation);
    }

    /**
     * 在同一 actor 命令内校验两个当前引用并填入凭据，避免第一次填写使第二个引用失效。
     *
     * @param sessionId 目标会话
     * @param expectedLease 宿主冻结的 ASSISTANT 租约
     * @param expectedOrigin 账号站点的精确 HTTPS Origin
     * @param target 当前用户名与密码引用
     * @param credentials UTF-8 用户名、单个 NUL、UTF-8 密码；最大 64 KiB，不进入普通 DTO
     * @param cancellation 取消信号
     * @return 不含凭据的页面观察
     */
    default BrowserActionResult fillInteractiveCredentials(
            String sessionId,
            BrowserContracts.AccessLease expectedLease,
            URI expectedOrigin,
            BrowserContracts.CredentialsTarget target,
            byte[] credentials,
            CancellationToken cancellation) {
        throw new UnsupportedOperationException("Interactive Browser is unavailable");
    }

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
