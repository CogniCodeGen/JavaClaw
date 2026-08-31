package com.javaclaw.server.extension;

import java.util.List;

/** 有限 MCP 预授权的管理契约；执行核销属于独立 ToolAuthorizationGateway。 */
public interface ToolAuthorizationUseCases {
    /** 返回当前工作区最近真实发现、且仍有效的工具目录；不触发工具调用。 */
    List<ToolAuthorityOption> options(String workspaceId);

    /** 返回用户确认的参数模板与配额，不返回凭据。 */
    List<ToolAuthorization> list(String workspaceId);

    /** 校验确认、模板、版本和有限配额后保存；重复幂等请求不能重置使用量。 */
    ToolAuthorization put(ToolAuthorization request, boolean confirmed, String key);

    /** 撤销指定版本，已开始的外部副作用不假定能够回滚。 */
    boolean disable(String id, long revision, String key);
}
