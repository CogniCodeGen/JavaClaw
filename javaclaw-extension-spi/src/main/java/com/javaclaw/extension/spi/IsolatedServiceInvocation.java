package com.javaclaw.extension.spi;

import java.util.Objects;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.WorkspaceId;

/**
 * 进程外内置服务的一次受治理调用。
 *
 * <p>Workspace 与最终有效权限显式随调用传递，宿主在处理 Worker 的反向请求时必须继续使用这两个边界，不能从全局状态猜测或扩大权限。
 *
 * @param caller 调用扩展
 * @param workspaceId 当前 Workspace
 * @param effectivePermissions 本次调用最终有效权限
 * @param serviceId 组合根注册的服务标识
 * @param request 规范化请求
 * @param cancellation 协作式取消信号
 */
public record IsolatedServiceInvocation(
        ExtensionId caller,
        WorkspaceId workspaceId,
        PermissionProfile effectivePermissions,
        String serviceId,
        CanonicalPayload request,
        CancellationToken cancellation) {
    /** 校验调用边界。 */
    public IsolatedServiceInvocation {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(effectivePermissions, "effectivePermissions");
        serviceId = text(serviceId);
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellation, "cancellation");
    }

    private static String text(String value) {
        String checked = Objects.requireNonNull(value, "serviceId").strip();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException("serviceId must not be blank");
        }
        return checked;
    }
}
