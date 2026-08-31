package com.javaclaw.agent.tool;

import com.javaclaw.agent.runtime.ToolExecutionContext;
import com.javaclaw.sandbox.api.NetworkBroker;

/** 根据服务端解析的 Workspace 与用途选择 Broker；模型参数不能借用其他工作区的私网授权。 */
@FunctionalInterface
public interface ScopedNetworkGateway {
    /** 返回当前工具上下文的网络能力；每次 DNS 和连接仍需由 Broker 重新核验授权。 */
    NetworkBroker broker(ToolExecutionContext context);
}
