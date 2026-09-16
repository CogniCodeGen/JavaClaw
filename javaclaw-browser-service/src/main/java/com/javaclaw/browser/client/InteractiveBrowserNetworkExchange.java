package com.javaclaw.browser.client;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.builtin.contracts.BrowserContracts;

/** 常驻浏览器的反向网络边界；HTTP(S) 目标每次都由宿主当前 Thread 租约与 Broker 重新授权。 */
@FunctionalInterface
public interface InteractiveBrowserNetworkExchange {
    /**
     * 接收被 Worker 阻断的精确 HTTPS Origin；只能提示用户授权，不能发起网络或自动重放。
     *
     * @param origin 未授权精确来源，不含路径、请求头或正文
     * @param generation 发出通知时的控制代次
     */
    default void deniedOrigin(java.net.URI origin, long generation) {}

    /**
     * 执行一次单跳网络请求，不自动重试业务写入。
     *
     * @param request 私有请求元数据
     * @param body 私有请求字节，禁止日志与模型输出
     * @param cancellation 当前控制租约的取消信号
     * @return 只返回 Worker 的网络响应
     * @throws Exception 取消、撤权或请求失败
     */
    BrowserNetworkResult exchange(BrowserContracts.NetworkRequest request, byte[] body, CancellationToken cancellation)
            throws Exception;
}
