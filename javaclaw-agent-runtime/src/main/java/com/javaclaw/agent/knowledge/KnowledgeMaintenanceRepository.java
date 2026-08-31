package com.javaclaw.agent.knowledge;

import java.util.List;

/** 低优先级知识维护的持久收件箱；任务去重与来源核验不能依赖易丢失的内存通知。 */
public interface KnowledgeMaintenanceRepository {
    /** 分批收录升级之后已完成的普通对话；不扫描 3.x 或无限回溯旧历史。 */
    void discover();

    /** 返回有界待处理记录，包括需要核对终态的 STARTED 任务。 */
    List<Job> pending(int limit);

    /** 在启动模型之前固定维护 Thread；崩溃重启使用同一 Thread 和 Turn 幂等键。 */
    void started(String sourceTurnId, String threadId);

    /** 记录真实终态及非敏感原因；失败和中断不自动重发模型请求。 */
    void finish(String sourceTurnId, String state, String reason);

    /** 判断工作区是否有前台活动 Turn，维护应等待而不是抢占交互额度。 */
    boolean foregroundActive(String workspaceId);

    /** 只返回同工作区、同来源 Turn 的真实用户陈述或成功工具证据；不含助手自述与凭据。 */
    List<Evidence> evidence(String sourceTurnId, String workspaceId);

    /**
     * 已持久化的维护任务，不包含私有上下文。
     *
     * @param sourceTurnId 唯一来源 Turn
     * @param workspaceId 所属工作区
     * @param state PENDING 或 STARTED
     * @param maintenanceThreadId 已启动的维护 Thread，尚未启动可为空
     */
    record Job(String sourceTurnId, String workspaceId, String state, String maintenanceThreadId) {}

    /**
     * 不可变的有界来源摘要；保留 Item 标识以便仓库在写入时复核。
     *
     * @param itemId 来源 Item 标识
     * @param kind userMessage、commandExecution、evaluation 等真实来源类型
     * @param text 完整的短文本，超长或含秘密的记录不纳入维护输入
     * @param skillEvidence 是否为已验证的可学习执行证据
     */
    record Evidence(String itemId, String kind, String text, boolean skillEvidence) {}
}
