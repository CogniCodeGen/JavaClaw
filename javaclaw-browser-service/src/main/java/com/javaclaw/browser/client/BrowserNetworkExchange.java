package com.javaclaw.browser.client;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.browser.protocol.BrowserWorkerProtocol;

/** App Server 为隔离 Browser Worker 执行单跳、受治理 HTTPS 请求的反向端口。 */
@FunctionalInterface
public interface BrowserNetworkExchange {
    /**
     * 执行一次网络交换；实现必须重新检查 Site authority、有效权限、DNS 和实时撤权。
     *
     * @param request Worker 产生的非 Secret 请求元数据
     * @param body 原始请求 body；实现不得记录
     * @param cancellation 上层取消信号
     * @return 只回送隔离 Worker 的敏感响应
     * @throws Exception 权限、网络、取消或协议失败
     */
    BrowserNetworkResult exchange(
            BrowserWorkerProtocol.NetworkRequest request, byte[] body, CancellationToken cancellation) throws Exception;
}
