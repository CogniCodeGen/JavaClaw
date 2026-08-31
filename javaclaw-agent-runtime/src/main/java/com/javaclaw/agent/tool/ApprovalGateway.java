package com.javaclaw.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;

import com.javaclaw.agent.runtime.ToolExecutionContext;
import com.javaclaw.sandbox.api.SandboxPolicy;

/** 与客户端连接无关的审批等待边界；默认拒绝不能替代 OS 沙箱。 */
@FunctionalInterface
public interface ApprovalGateway {
    /** Implementations register the request before invoking {@code announcePending}. */
    boolean approve(Request request, Runnable announcePending) throws Exception;

    ApprovalGateway DENY_ALL = (request, announcePending) -> false;

    /**
     * 提交给审批界面的工具执行提案，不携带直接执行能力。
     *
     * @param approvalId 非空审批标识，必须先注册等待者再发布通知
     * @param call 当前工具调用及 Thread/Turn 配置快照，调用时非空
     * @param origin 工具来源，用于策略和审计分类
     * @param risk 声明的风险级别，用于决定审批要求
     * @param arguments 工具参数 JSON；Hook 改写后必须重新通过 Schema 校验
     * @param policy 当前权限上限；后续策略只能求交收窄，不能扩大
     */
    record Request(
            String approvalId,
            ToolExecutionContext call,
            ToolOrigin origin,
            ToolRisk risk,
            JsonNode arguments,
            SandboxPolicy policy) {}
}
