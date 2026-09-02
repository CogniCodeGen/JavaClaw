package com.javaclaw.api;

/**
 * 指向精确私网授权版本的不可变引用。
 *
 * <p>调用执行前必须重新读取该版本并检查状态、期限、Workspace、用途、Origin 与 DNS 地址集合；只保存 ID 或沿用调用开始时的授权结果都不能满足实时撤权要求。
 *
 * @param id 私网授权稳定标识
 * @param revision 必须精确匹配的授权版本
 */
public record PrivateNetworkGrantRef(String id, long revision) {
    /** 校验授权身份。 */
    public PrivateNetworkGrantRef {
        id = Preconditions.identifier(id, "id");
        revision = Preconditions.positive(revision, "revision");
    }
}
