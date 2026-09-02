package com.javaclaw.api;

/** 统一执行 DNS、重定向、地址和响应大小校验的网络边界。 */
public interface NetworkBroker {
    /**
     * 按最终权限发送请求。
     *
     * @param request 请求
     * @param permission 最终有效权限
     * @param cancellation Turn 取消信号
     * @return 有界响应
     * @throws Exception 校验、连接或读取失败
     */
    BrokerResponse exchange(BrokerRequest request, PermissionProfile permission, CancellationToken cancellation)
            throws Exception;
}
