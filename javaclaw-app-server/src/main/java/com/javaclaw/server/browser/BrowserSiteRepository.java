package com.javaclaw.server.browser;

import java.util.List;
import java.util.Optional;

/** H2 站点权威端口；更新必须有 revision，秘密和会话正文不得存入普通配置表。 */
public interface BrowserSiteRepository {
    /** 查询工作区全部站点；包括已禁用项。 */
    List<BrowserSite> list(String workspaceId);

    /** 读取配置与权限版本，不存在返回空值。 */
    Optional<BrowserSite> find(String id);

    /** 保存已验证配置；同一标识的工作区不能变更。 */
    BrowserSite put(BrowserSite site, long expectedRevision, String key);

    /** 禁用站点并递增权限版本，不递归删除用户文件。 */
    boolean disable(String id, long expectedRevision, String key);
}
