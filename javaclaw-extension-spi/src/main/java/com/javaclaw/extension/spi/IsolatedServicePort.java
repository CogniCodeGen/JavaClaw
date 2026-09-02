package com.javaclaw.extension.spi;

import com.javaclaw.api.CanonicalPayload;

/** 调用由 App Server 监督、与宿主 classpath 隔离的进程外服务。 */
public interface IsolatedServicePort {
    /**
     * 调用一个已装配服务。
     *
     * @param invocation 包含 caller、Workspace、有效权限、请求与取消信号的完整调用
     * @return 规范化响应
     * @throws Exception 服务失败、超时、取消或未授权
     */
    CanonicalPayload invoke(IsolatedServiceInvocation invocation) throws Exception;
}
