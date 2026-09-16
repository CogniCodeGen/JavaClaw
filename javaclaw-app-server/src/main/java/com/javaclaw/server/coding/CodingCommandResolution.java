package com.javaclaw.server.coding;

/** 延迟取得命令与租约；必须在进程所有者注册、执行槽和根锁取得之后调用。 */
@FunctionalInterface
interface CodingCommandResolution {
    CodingResolvedCommand resolve() throws Exception;
}
