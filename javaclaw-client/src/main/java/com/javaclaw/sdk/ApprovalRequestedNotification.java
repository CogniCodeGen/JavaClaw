package com.javaclaw.sdk;

/**
 * 审批通知；回复必须引用 approvalId，不得直接调用工具。
 *
 * @param threadId 所属 Thread 标识；有效服务端响应中非空
 * @param approvalId 审批请求关联标识
 * @param reason 本次操作需要用户审批的原因
 * @param risk 服务端声明的风险级别
 */
public record ApprovalRequestedNotification(String threadId, String approvalId, String reason, String risk)
        implements ClientNotification {}
