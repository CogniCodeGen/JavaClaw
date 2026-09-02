package com.javaclaw.server.rpc;

import com.javaclaw.api.CanonicalPayload;

/** 单个 Protocol v2 方法的内部处理端口。 */
@FunctionalInterface
public interface RpcHandler {
    /**
     * 执行方法。
     *
     * @param params 规范参数
     * @return 规范结果
     * @throws Exception 用例失败
     */
    CanonicalPayload handle(CanonicalPayload params) throws Exception;
}
