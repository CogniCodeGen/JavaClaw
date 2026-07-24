package com.javaclaw.workflow.runtime;

import com.javaclaw.workflow.model.StatePatch;

/** 节点执行结果；interrupt 会把 run 停在当前节点，恢复输入写入状态后重跑该节点。 */
public record NodeResult(StatePatch patch, String output, Interrupt interrupt) {
    public NodeResult {
        patch = patch == null ? StatePatch.EMPTY : patch;
    }

    public static NodeResult next() { return new NodeResult(StatePatch.EMPTY, null, null); }
    public static NodeResult next(StatePatch patch) { return new NodeResult(patch, null, null); }
    public static NodeResult output(StatePatch patch, String output) { return new NodeResult(patch, output, null); }
    public static NodeResult interrupt(String prompt, String responseKey) {
        return new NodeResult(StatePatch.EMPTY, null, new Interrupt(prompt, responseKey));
    }

    public record Interrupt(String prompt, String responseKey) {
        public Interrupt {
            prompt = prompt == null ? "请提供继续执行所需的信息" : prompt;
            responseKey = responseKey == null || responseKey.isBlank() ? "human.response" : responseKey;
        }
    }
}
