package com.javaclaw.application.agent;

import com.javaclaw.application.agent.AgentManagementApplicationService.Agent;

import java.util.List;

/** 工作区智能体定义的持久化端口。 */
public interface AgentDefinitionPort {

    List<Agent> list();

    Agent create(String name);

    void update(Agent agent);

    boolean delete(String id);
}
