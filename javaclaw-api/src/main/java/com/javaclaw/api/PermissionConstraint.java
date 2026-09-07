package com.javaclaw.api;

/** Role 只能收窄父级权限，不能授予权限。 */
public enum PermissionConstraint {
    /** 继承执行配置的安全边界。 */
    INHERIT,
    /** 由代码施加只读权限上限。 */
    READ_ONLY
}
