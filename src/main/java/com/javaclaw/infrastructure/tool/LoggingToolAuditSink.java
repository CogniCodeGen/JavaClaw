package com.javaclaw.infrastructure.tool;

import com.javaclaw.application.tool.ToolAuditSink;
import com.javaclaw.application.tool.ToolInvocation;
import com.javaclaw.application.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 仅记录来源、工具名、状态和耗时的安全审计；不记录工具参数或返回正文。 */
public final class LoggingToolAuditSink implements ToolAuditSink {

    private static final Logger log = LoggerFactory.getLogger(LoggingToolAuditSink.class);

    @Override
    public void started(ToolInvocation invocation) {
        log.info("工具调用开始 id={} tool={} source={}:{} origin={}",
                invocation.id(), invocation.toolName(), invocation.source().kind(),
                invocation.source().id(), invocation.origin().kind());
    }

    @Override
    public void completed(ToolInvocation invocation, ToolResult result) {
        log.info("工具调用结束 id={} tool={} status={} durationMs={}",
                invocation.id(), invocation.toolName(), result.status(),
                result.duration().toMillis());
    }
}
