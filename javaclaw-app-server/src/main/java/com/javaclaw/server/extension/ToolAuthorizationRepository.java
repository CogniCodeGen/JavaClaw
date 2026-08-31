package com.javaclaw.server.extension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.javaclaw.agent.tool.ToolAuthorizationGateway;

/** 预授权与核销凭据的持久边界；额度与凭据在单事务中更新，不能先执行再核销。 */
public interface ToolAuthorizationRepository {
    /** 查询工作区授权，包括已禁用及耗尽记录。 */
    List<ToolAuthorization> list(String workspaceId);

    /** 按 expectedRevision 和幂等键保存；核销次数只能保留，不能由客户端清零。 */
    ToolAuthorization put(ToolAuthorization request, long expectedRevision, String key);

    /** 按版本撤销，不删除历史核销凭据。 */
    boolean disable(String id, long expectedRevision, String key);

    /** 锁定准确版本并核销；期限、启用状态与剩余额度必须在同一事务内重检。 */
    Optional<ToolAuthorizationGateway.Receipt> consume(
            String id, long revision, String invocationId, String argumentsSha256, Instant now);
}
