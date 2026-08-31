package com.javaclaw.server.network;

import java.util.List;

/** 私网授权管理边界；RPC 仅管理显式范围，不持有 Broker 或连接实现。 */
public interface NetworkGrantUseCases {
    /** 查询一个已存在工作区的授权元数据。 */
    List<NetworkGrant> list(String workspaceId);

    /** 校验显式确认与准确端点，按版本和幂等键保存有限授权。 */
    NetworkGrant put(NetworkGrant request, long expectedRevision, boolean confirmed, String key);

    /** 撤销指定版本，下一次网络连接必须立即复核。 */
    boolean disable(String id, long revision, String key);
}
