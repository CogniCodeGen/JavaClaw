package com.javaclaw.extension.spi;

import java.net.URI;
import java.util.List;

import com.javaclaw.api.PrivateNetworkGrant;
import com.javaclaw.api.PrivateNetworkGrantRef;
import com.javaclaw.api.PrivateNetworkPurpose;
import com.javaclaw.api.WorkspaceId;

/** 可信内置扩展绑定精确私网授权版本时使用的实时校验端口。 */
public interface PrivateNetworkGrantPort {
    /**
     * 列出当前仍可绑定的授权。
     *
     * @param workspaceId 所属 Workspace
     * @param purpose 授权用途
     * @return 按授权 ID 排序的活动、未过期授权
     */
    List<PrivateNetworkGrant> available(WorkspaceId workspaceId, PrivateNetworkPurpose purpose);

    /**
     * 校验并返回与资源 Origin 精确匹配的授权版本。
     *
     * @param reference 精确授权版本
     * @param workspaceId 当前 Workspace
     * @param purpose 当前用途
     * @param origin 要绑定的精确 Origin
     * @return 当前仍可绑定的权威授权
     */
    PrivateNetworkGrant requireBindable(
            PrivateNetworkGrantRef reference, WorkspaceId workspaceId, PrivateNetworkPurpose purpose, URI origin);
}
