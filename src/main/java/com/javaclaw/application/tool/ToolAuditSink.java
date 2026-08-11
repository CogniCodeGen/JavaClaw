package com.javaclaw.application.tool;

/** 工具调用审计端口；实现不得抛异常干扰工具执行结果。 */
public interface ToolAuditSink {

    void started(ToolInvocation invocation);

    void completed(ToolInvocation invocation, ToolResult result);
}
