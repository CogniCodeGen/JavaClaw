package com.javaclaw.server.rpc;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.protocol.SessionSecretChannel;

/** 需要当前连接 Secret 解封通道的 Protocol v3 内部处理端口。 */
@FunctionalInterface
public interface SessionRpcHandler {
    /**
     * 执行会话感知方法。
     *
     * @param params 规范参数
     * @param secrets 当前连接独占的 Secret 通道
     * @return 规范结果
     * @throws Exception 用例失败
     */
    CanonicalPayload handle(CanonicalPayload params, SessionSecretChannel secrets) throws Exception;
}
