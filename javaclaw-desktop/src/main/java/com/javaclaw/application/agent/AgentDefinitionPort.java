package com.javaclaw.application.agent;

import com.javaclaw.application.agent.AgentManagementApplicationService.Agent;
import com.javaclaw.framework.api.CapabilityForm;

import java.util.List;

/** 工作区智能体定义的持久化端口。 */
public interface AgentDefinitionPort {

    default List<CapabilityForm> capabilityForms() { return List.of(); }

    List<Agent> list();

    Agent create(String name);

    void update(Agent agent);

    boolean delete(String id);
}
