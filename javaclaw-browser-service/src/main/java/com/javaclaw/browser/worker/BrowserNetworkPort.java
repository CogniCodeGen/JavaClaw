package com.javaclaw.browser.worker;

import com.javaclaw.browser.protocol.BrowserWorkerProtocol;

/** Worker 内部把每个 HTTPS 请求反向交给宿主 Broker 的端口。 */
@FunctionalInterface
interface BrowserNetworkPort {
    BrowserNetworkChannel.NetworkResult exchange(BrowserWorkerProtocol.NetworkRequest request, byte[] body);
}
