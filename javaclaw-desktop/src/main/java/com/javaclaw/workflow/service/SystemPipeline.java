package com.javaclaw.workflow.service;

import com.javaclaw.workflow.runtime.NodeExecutionContext;
import com.javaclaw.workflow.runtime.NodeResult;

/** 内置模式把代码定义的领域阶段挂入系统图节点的桥接端口。 */
@FunctionalInterface
public interface SystemPipeline {
    /**
     * 执行一个真实的系统阶段。系统图中的每个 SYSTEM 节点都会调用本方法；
     * 不允许执行器自行把未执行阶段标记为完成。
     */
    NodeResult executeStage(String stageId, NodeExecutionContext context) throws Exception;
}
