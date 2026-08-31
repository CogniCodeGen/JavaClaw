package com.javaclaw.server.network;

import java.util.List;

/** 私网授权持久端口；撤销及 revision 在 H2 原子更新，不以内存缓存延长授权。 */
public interface NetworkGrantRepository {
    /** 查询工作区授权，包括禁用和过期项以便用户审阅。 */
    List<NetworkGrant> list(String workspaceId);

    /** 按期望版本保存已校验的授权；同一幂等键不能复用为不同请求。 */
    NetworkGrant put(NetworkGrant grant, long expectedRevision, String key);

    /** 按版本禁用授权，保留审计信息；此后网络请求必须失败关闭。 */
    boolean disable(String id, long expectedRevision, String key);
}
