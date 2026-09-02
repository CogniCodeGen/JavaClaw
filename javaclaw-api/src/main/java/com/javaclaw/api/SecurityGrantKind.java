package com.javaclaw.api;

/** 权限决策引用的授权类型。 */
public enum SecurityGrantKind {
    /** 精确 Origin 与 DNS 地址集合的临时私网授权。 */
    PRIVATE_NETWORK,
    /** 仅供 Schedule 使用的限额工具授权。 */
    UNATTENDED_TOOL
}
