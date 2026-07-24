package com.javaclaw.workflow.model;

/** 首版公共节点类型；SYSTEM 节点由内置流程注册且不出现在用户节点面板。 */
public enum NodeType {
    START,
    END,
    AGENT,
    TOOL,
    CONDITION,
    TRANSFORM,
    HUMAN_INPUT,
    OUTPUT,
    SYSTEM
}
