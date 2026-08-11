package com.javaclaw.application.tool;

/** 在工具执行前完成风险与来源授权。 */
@FunctionalInterface
public interface ToolAuthorizer {

    ToolAuthorization authorize(ToolInvocation invocation);
}
