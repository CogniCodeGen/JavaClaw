package com.javaclaw.server.transport;

/** 将协议审批回复交给运行时的窄接口；授权仍由运行时复核，不在传输层直接扩大沙箱权限。 */
@FunctionalInterface
public interface ApprovalResponseHandler {
    /** 提交指定审批的允许或拒绝结果；返回是否接受该回复，已完成、取消或未知审批不能再次授权。 */
    boolean respond(String approvalId, boolean approved);
}
