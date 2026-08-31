package com.javaclaw.agent.knowledge;

import com.javaclaw.agent.runtime.ToolExecutionContext;
import com.javaclaw.core.api.ToolExecutionResult;
import com.javaclaw.sandbox.api.SandboxPolicy;

/** 固定第一方脚本 Worker 的执行端口；资源正文不是宿主机命令行，不允许选择额外 JVM 参数。 */
@FunctionalInterface
public interface SkillScriptGateway {
    /** 在当前 Turn 权限与预算内执行已确认的 Java 资源；实现必须经过 Sandbox Supervisor。 */
    ToolExecutionResult execute(SkillResource resource, ToolExecutionContext context, SandboxPolicy policy)
            throws Exception;
}
