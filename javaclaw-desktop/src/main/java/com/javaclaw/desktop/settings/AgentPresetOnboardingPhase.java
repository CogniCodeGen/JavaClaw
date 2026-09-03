package com.javaclaw.desktop.settings;

/** 首次智能体初始化向导的异步阶段。 */
public enum AgentPresetOnboardingPhase {
    /** 尚未读取服务端状态。 */
    INITIAL,
    /** 正在读取目录或校验已有资源。 */
    LOADING,
    /** 等待用户核对并确认权限预览。 */
    REVIEW_PERMISSIONS,
    /** 等待用户选择精确模型和工具。 */
    SELECT_CONFIGURATION,
    /** 正在持久化一个或多个可恢复步骤。 */
    APPLYING,
    /** 三个 Profile 与默认绑定均已存在且通过校验。 */
    COMPLETED,
    /** 后台请求失败，可以保留选择后重试。 */
    ERROR,
    /** 确定性资源标识已被不同内容占用。 */
    CONFLICT
}
