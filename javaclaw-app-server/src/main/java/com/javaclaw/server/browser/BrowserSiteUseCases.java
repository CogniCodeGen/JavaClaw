package com.javaclaw.server.browser;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.javaclaw.server.security.SecretStore;

/** 站点表单与人工登录的领域用例；不接收或返回 Cookie、原始会话 JSON、协议节点或本机路径。 */
public interface BrowserSiteUseCases {
    /** 查询工作区站点；无浏览器安装时配置仍可管理。 */
    List<BrowserSite> list(String workspaceId);

    /** 明确确认新来源范围后按版本保存；更改范围使已保存登录会话失效。 */
    BrowserSite put(BrowserSite site, long expectedRevision, boolean confirmed, String key);

    /** 禁用站点，停止其所有会话；重复请求按幂等键重放。 */
    boolean disable(String id, long revision, String key);

    /** 保存站点专属 SecretRef 槽位；调用方负责清空输入字符数组。 */
    SecretStore.SecretMetadata putSecret(String id, String name, char[] value, String key);

    /** 查询槽位 metadata，不读取值。 */
    Optional<SecretStore.SecretMetadata> secret(String id, String name);

    /** 按凭据版本清除槽位，不影响其他站点。 */
    boolean clearSecret(String id, String name, long revision, String key);

    /** 打开独立可见浏览器让用户亲自登录；必须显式确认，十分钟后自动终止。 */
    Login startLogin(String id, long revision, boolean confirmed, String key) throws Exception;

    /** 用户确认后加密保存登录状态并关闭浏览器；取消不保存状态。 */
    boolean finishLogin(String sessionId, boolean save, String key) throws Exception;

    /** 查询是否保存有与当前站点版本绑定的登录会话，不返回会话正文。 */
    Optional<SecretStore.SecretMetadata> savedSession(String id);

    /** 清除当前版本的加密登录会话，后续打开恢复为未登录。 */
    boolean clearSession(String id, long revision, String key);

    /**
     * 人工登录会话引用。
     *
     * @param sessionId 不含凭据的随机会话标识
     * @param siteId 所属站点
     * @param expiresAt 到期时间
     */
    record Login(String sessionId, String siteId, Instant expiresAt) {}
}
