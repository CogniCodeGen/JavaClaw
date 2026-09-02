package com.javaclaw.api;

/** 工具声明的最低风险等级。 */
public enum ToolRisk {
    /** 无副作用的查询。 */
    READ_ONLY,
    /** 仅修改 Workspace 内数据。 */
    WORKSPACE_WRITE,
    /** 访问外部网络。 */
    NETWORK,
    /** 启动受限命令或 PTY。 */
    PROCESS,
    /** 不可逆或影响 Workspace 外部的副作用。 */
    EXTERNAL_EFFECT
}
