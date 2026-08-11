package com.javaclaw.application.turn;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 按显式顺序组合一轮处理步骤的轻量应用服务。
 *
 * <p>Pipeline 无共享可变状态且可复用；每次调用必须传入新的轮次状态。步骤 ID 必须唯一，
 * 便于进度、诊断和测试稳定定位。执行采用 fail-fast，不做隐式重试。</p>
 */
public final class TurnPipeline<S> {

    private final List<TurnStage<S>> stages;

    public TurnPipeline(List<TurnStage<S>> stages) {
        if (stages == null || stages.isEmpty()) {
            throw new IllegalArgumentException("TurnPipeline 至少需要一个步骤");
        }
        this.stages = List.copyOf(stages);
        Set<String> ids = new HashSet<>();
        for (TurnStage<S> stage : this.stages) {
            if (!ids.add(stage.id())) {
                throw new IllegalArgumentException("TurnStage id 重复: " + stage.id());
            }
        }
    }

    public S execute(S state) {
        java.util.Objects.requireNonNull(state, "state");
        for (TurnStage<S> stage : stages) {
            try {
                stage.execute(state);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new TurnStageException(stage.id(), interrupted);
            } catch (Exception failure) {
                throw new TurnStageException(stage.id(), failure);
            }
        }
        return state;
    }

    public List<String> stageIds() {
        return stages.stream().map(TurnStage::id).toList();
    }

    public static final class TurnStageException extends RuntimeException {
        private final String stageId;

        private TurnStageException(String stageId, Throwable cause) {
            super("TurnStage 执行失败: " + stageId, cause);
            this.stageId = stageId;
        }

        public String stageId() {
            return stageId;
        }
    }
}
