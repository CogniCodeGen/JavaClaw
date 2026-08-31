package com.javaclaw.agent.tool;

import java.util.Objects;

import com.javaclaw.core.api.ToolDescriptor;
import com.javaclaw.sandbox.api.SandboxPolicy;

/**
 * 本 Turn 工具的完整治理描述；可见快照不等于永久执行授权，availability 每次调用都要复核。
 *
 * @param descriptor 模型可见名称和 Schema，非空
 * @param origin 工具来源，用于策略和审计分类
 * @param risk 声明的风险级别，用于决定审批要求
 * @param requestsApproval 工具是否显式请求审批
 * @param sandboxCeiling 该工具的沙箱权限上限，非空
 * @param availability 执行前权限/版本复核回调，非空
 * @param effect 由第一方实现审阅确认的业务副作用；不信任外部服务的 readOnlyHint
 * @param handler 通过治理后才可调用的执行处理器，非空
 */
public record RegisteredTool(
        ToolDescriptor descriptor,
        ToolOrigin origin,
        ToolRisk risk,
        boolean requestsApproval,
        SandboxPolicy sandboxCeiling,
        ToolAvailability availability,
        ToolEffect effect,
        ToolHandler handler) {
    /** 要求描述符、策略、来源、风险及执行句柄完整；不在注册时授权未来调用。 */
    public RegisteredTool {
        descriptor = Objects.requireNonNull(descriptor, "descriptor");
        origin = Objects.requireNonNull(origin, "origin");
        risk = Objects.requireNonNull(risk, "risk");
        sandboxCeiling = Objects.requireNonNull(sandboxCeiling, "sandboxCeiling");
        availability = Objects.requireNonNull(availability, "availability");
        effect = Objects.requireNonNull(effect, "effect");
        if (effect == ToolEffect.READ_ONLY && origin != ToolOrigin.BUILTIN) {
            throw new IllegalArgumentException("external read-only annotations are not an execution guarantee");
        }
        handler = Objects.requireNonNull(handler, "handler");
    }

    /** 注册无需动态撤销检查的固定工具；外部插件应使用显式 availability 的完整构造器。 */
    public RegisteredTool(
            ToolDescriptor descriptor,
            ToolOrigin origin,
            ToolRisk risk,
            boolean requestsApproval,
            SandboxPolicy sandboxCeiling,
            ToolHandler handler) {
        this(
                descriptor,
                origin,
                risk,
                requestsApproval,
                sandboxCeiling,
                ToolAvailability.ALWAYS,
                ToolEffect.UNKNOWN,
                handler);
    }

    /** 未经第一方效果审阅的动态工具保持 UNKNOWN；低风险或签名不能隐式授予 PLAN 执行权。 */
    public RegisteredTool(
            ToolDescriptor descriptor,
            ToolOrigin origin,
            ToolRisk risk,
            boolean requestsApproval,
            SandboxPolicy sandboxCeiling,
            ToolAvailability availability,
            ToolHandler handler) {
        this(descriptor, origin, risk, requestsApproval, sandboxCeiling, availability, ToolEffect.UNKNOWN, handler);
    }

    /** 仅第一方只读实现可显式采用此契约；沙箱、审批、Schema 和执行前撤销检查仍然保留。 */
    public RegisteredTool readOnly() {
        return new RegisteredTool(
                descriptor,
                origin,
                risk,
                requestsApproval,
                sandboxCeiling,
                availability,
                ToolEffect.READ_ONLY,
                handler);
    }
}
