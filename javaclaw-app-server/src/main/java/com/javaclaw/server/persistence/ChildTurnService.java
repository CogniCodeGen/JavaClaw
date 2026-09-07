package com.javaclaw.server.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.runtime.ReservedChildBudget;
import com.javaclaw.runtime.TurnExecutionPhase;

/**
 * 子 Thread 与父预算的同事务创建者，复用 Core 幂等账本和 Thread 行映射。
 *
 * <p>锁顺序固定为命令锁、父预算账户、H2 父 Turn 和 checkpoint。事务提交前不发布子 Thread，失败不扣内存预算。
 */
public final class ChildTurnService {
    private final H2Transactions transactions;
    private final LiveTurnBudgets budgets;
    private final CanonicalJson json;
    private final Clock clock;
    private final IdempotentCommandStore commands = new IdempotentCommandStore();
    private final TurnRepository turns = new TurnRepository();
    private final ThreadRepository threads = new ThreadRepository();

    /**
     * 创建同实例预算与持久化服务。
     *
     * @param database data-v6 权威数据库
     * @param budgets 与 Harness 共用的活动账户
     * @param json 规范 JSON
     * @param clock 平台时钟
     */
    public ChildTurnService(H2Database database, LiveTurnBudgets budgets, CanonicalJson json, Clock clock) {
        transactions = new H2Transactions(database);
        this.budgets = Objects.requireNonNull(budgets, "budgets");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 预留预算并创建子 Thread；同键重试返回原 Thread，绝不重复扣减。
     *
     * @param identity 幂等身份，expected revision 必须匹配父 Turn
     * @param request 服务端已解析的有限配置
     * @return 已提交但尚未执行模型的子 Thread
     */
    public ConversationThread reserve(CommandIdentity identity, ChildThreadReservation request) {
        synchronized (CommandLocks.forKey(identity.idempotencyKey())) {
            Optional<ConversationThread> recovered = execute(connection -> commands.recover(connection, identity)
                    .map(payload -> json.decode(payload, ConversationThread.class)));
            if (recovered.isPresent()) {
                return recovered.orElseThrow();
            }
            var budget = request.configuration().budget();
            return budgets.reserve(
                    request.parentTurnId(),
                    new ReservedChildBudget(budget.inputTokens(), budget.outputTokens(), budget.toolCalls()),
                    () -> execute(connection -> create(connection, identity, request)));
        }
    }

    /**
     * 在重新解析之前恢复已提交的创建意图，Role 更新或归档不能改变幂等重试。
     *
     * @param identity 原创建身份
     * @return 已预留子 Thread，缺失时才允许解析新配置
     */
    public Optional<ConversationThread> recover(CommandIdentity identity) {
        return execute(connection ->
                commands.recover(connection, identity).map(payload -> json.decode(payload, ConversationThread.class)));
    }

    /**
     * 读取一个预留子 Thread 已创建的唯一 Turn，恢复时不重新解析 Role 或预算。
     *
     * @param childThreadId 通过幂等创建恢复的子 Thread
     * @return 已存在的 Turn，未开始时为空
     */
    public Optional<AgentTurn> started(com.javaclaw.api.ThreadId childThreadId) {
        return execute(connection -> turns.findByThread(connection, childThreadId));
    }

    private ConversationThread create(Connection connection, CommandIdentity identity, ChildThreadReservation request)
            throws Exception {
        AgentTurn parent = turns.lock(connection, request.parentTurnId());
        if (parent.revision() != identity.expectedRevision()) {
            throw PersistenceException.revisionConflict("父 Turn revision 已改变");
        }
        if (parent.status() != TurnStatus.RUNNING || turns.hasCancellation(connection, parent.id())) {
            throw PersistenceException.invalidRequest("父 Turn 已停止或收到取消请求");
        }
        requireSafePhase(connection, parent);
        if (clock.instant()
                .plus(request.configuration().budget().wallTime())
                .isAfter(parent.createdAt().plus(parent.budget().wallTime()))) {
            throw PersistenceException.invalidRequest("子任务不得延长父 Turn 截止时间");
        }
        ConversationThread parentThread =
                threads.find(connection, parent.threadId()).orElseThrow();
        ConversationThread child = threads.insert(
                connection,
                parentThread.workspaceId(),
                Optional.of(parent.threadId()),
                request.executionIntent(),
                request.title(),
                clock.instant());
        new ChildTurnReservationRepository(json)
                .insert(connection, parent.id(), child.id(), request.configuration(), request.toolCatalog());
        commands.record(connection, identity, json.encode(child), clock.instant());
        return child;
    }

    private static void requireSafePhase(Connection connection, AgentTurn parent) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT PHASE FROM CORE.TURN_EXECUTION_CHECKPOINT WHERE TURN_ID = ? FOR UPDATE")) {
            statement.setString(1, parent.id().toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()
                        || !TurnExecutionPhase.valueOf(result.getString(1)).toolBatchPhase()) {
                    throw PersistenceException.invalidRequest("父模型调用期间不能预留子任务，请在工具执行边界重试");
                }
            }
        }
    }

    private <T> T execute(H2Transactions.SqlWork<T> work) {
        try {
            return transactions.execute(work);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new PersistenceException("子任务创建事务失败", failure);
        }
    }
}
