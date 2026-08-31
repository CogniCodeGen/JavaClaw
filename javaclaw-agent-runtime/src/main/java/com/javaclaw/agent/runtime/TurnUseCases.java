package com.javaclaw.agent.runtime;

import com.javaclaw.core.api.AgentTurn;
import com.javaclaw.core.api.TurnId;
import com.javaclaw.core.api.TurnInput;
import com.javaclaw.core.api.TurnStartCommand;

/** Turn scheduling, steering and cancellation boundary. */
public interface TurnUseCases {
    /**
     * 幂等地排队并异步执行 Turn；同 Thread 只允许一个活动 Turn，目录必须与 Thread 一致。 受管子 Thread 必须有活动父 Turn，其预算和截止时间从父作用域分配；用户对话 fork
     * 不继承取消生命周期。
     *
     * @return 已持久化的排队状态，或相同幂等键对应的已有 Turn
     * @throws IllegalStateException Thread 不活跃、存在其他活动 Turn 或安全配额耗尽
     */
    AgentTurn startTurn(TurnStartCommand command);

    /** 向仍活动且未取消的 Turn 追加输入并持久化用户 Item；不可接收时返回 false。 */
    boolean steer(TurnId turnId, TurnInput input);

    /** 协作式取消指定 Turn 并传播到活动子 Thread；首次取消返回 true，无活动执行或已取消返回 false。 */
    boolean interrupt(TurnId turnId);
}
