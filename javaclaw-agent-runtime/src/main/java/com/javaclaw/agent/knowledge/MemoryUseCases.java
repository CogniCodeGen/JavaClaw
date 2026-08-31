package com.javaclaw.agent.knowledge;

import java.util.List;

/** 记忆版本与确认流程的业务入口；客户端不能通过此接口绕过来源校验或扩大自动写入范围。 */
public interface MemoryUseCases {
    /** 读取当前记忆、来源与用户固定状态。 */
    MemoryRepository.MemoryDocument readMemory(String id);

    /** 读取不可变历史版本，供对比与显式恢复。 */
    List<MemoryRepository.MemoryDocument> memoryHistory(String id);

    /** 显式保存用户编辑的记忆；expectedRevision 保护已固定内容不被陈旧表单覆盖。 */
    MemoryRepository.MemoryDocument saveMemory(MemoryRepository.MemoryDraft draft, long expectedRevision, String key);

    /** 将历史内容恢复为新版本；不会回拨修订号。 */
    MemoryRepository.MemoryDocument restoreMemory(String id, long sourceRevision, long expectedRevision, String key);

    /** 提交有来源的建议，不自动覆盖；低风险自动维护使用独立的内部服务入口。 */
    MemoryRepository.MemoryProposal proposeMemory(
            MemoryRepository.MemoryDraft draft, long expectedRevision, String reason, String key);

    /** 查询工作区的提案及处理状态。 */
    List<MemoryRepository.MemoryProposal> memoryProposals(String workspaceId);

    /** 按提案与目标双重版本校验接受或拒绝，旧提案不能覆盖新内容。 */
    MemoryRepository.MemoryProposal reviewMemoryProposal(String id, boolean accept, long expectedRevision, String key);
}
