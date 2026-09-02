package com.javaclaw.api;

/** 有效权限预览按固定顺序展示的治理层。 */
public enum PermissionLayerKind {
    /** 平台编译进产品的最高资源和能力上限。 */
    SYSTEM_CEILING,
    /** Workspace 根目录和 Workspace 范围约束。 */
    WORKSPACE,
    /** 冻结 Profile revision 与其实时最新 revision。 */
    PROFILE,
    /** 当前 Turn 显式授予但不能扩大的权限。 */
    TURN_GRANT,
    /** 工具声明的最小能力与风险约束。 */
    TOOL_DECLARATION
}
