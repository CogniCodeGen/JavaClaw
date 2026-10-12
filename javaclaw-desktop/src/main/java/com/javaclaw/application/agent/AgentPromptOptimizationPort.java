package com.javaclaw.application.agent;

/** 阻塞式提示词优化端口；实现负责模型调用与用量记录。 */
@FunctionalInterface
public interface AgentPromptOptimizationPort {

    String optimize(String name, String description, String draft);
}
