package com.javaclaw.framework.spi;

import com.javaclaw.framework.api.PermissionSet;
import com.javaclaw.framework.api.RunRequest;

/**
 * 在 Run 内冻结一次的必需上下文来源。
 * 读取由运行时经 ToolInvocationGateway 执行，以保留权限、预算和审计边界。
 */
public interface FixedContextSource {
    String id();

    String group();

    PermissionSet requiredPermissions();

    FixedContextSnapshot read(RunRequest request);
}
