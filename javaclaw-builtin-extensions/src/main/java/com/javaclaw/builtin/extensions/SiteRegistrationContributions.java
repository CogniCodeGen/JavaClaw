package com.javaclaw.builtin.extensions;

import java.util.List;
import java.util.Set;

import com.javaclaw.builtin.contracts.SiteContracts;
import com.javaclaw.builtin.contracts.SiteRegistrationContracts;
import com.javaclaw.extension.spi.ExtensionContribution;
import com.javaclaw.extension.spi.ExtensionContributions;
import com.javaclaw.extension.spi.ExtensionExecutionContext;
import com.javaclaw.extension.spi.ExtensionRequest;
import com.javaclaw.extension.spi.ExtensionResponse;
import com.javaclaw.extension.spi.IsolatedServiceCallScope;
import com.javaclaw.extension.spi.IsolatedServiceInvocation;

/** 网站登记只提供设置页命令，不向模型发布工具；秘密始终留在宿主与 Worker 的私有通道。 */
final class SiteRegistrationContributions {
    private final ManagedDocumentResource<SiteContracts.Site> documents;

    SiteRegistrationContributions(ManagedDocumentResource<SiteContracts.Site> documents) {
        this.documents = documents;
    }

    List<ExtensionContribution> contributions() {
        return List.of(
                new ExtensionContributions.Query("site.registration.query", Set.of("registration.status"), this::query),
                new ExtensionContributions.Command(
                        "site.registration.command",
                        Set.of(
                                "registration.begin",
                                "registration.origin",
                                "registration.complete",
                                "registration.cancel"),
                        this::command));
    }

    private ExtensionResponse query(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        requireSettings(request, context);
        if (request.idempotencyKey().isPresent()) {
            throw new IllegalArgumentException("网站登记查询不能携带写入身份");
        }
        return invoke(request, context);
    }

    private ExtensionResponse command(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        requireSettings(request, context);
        if (request.idempotencyKey().isEmpty()) {
            throw new IllegalArgumentException("网站登记命令需要幂等身份");
        }
        // 外部窗口和最终密文提交由宿主管理；扩展不得提前写文档或独立提交成功回执。
        return invoke(request, context);
    }

    private ExtensionResponse invoke(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        var task = new SiteRegistrationContracts.ServiceRequest(
                request.operation(), request.payload(), request.idempotencyKey());
        var response = context.services()
                .invoke(new IsolatedServiceInvocation(
                        documents.extensionId(),
                        context.workspaceId(),
                        context.effectivePermissions(),
                        SiteRegistrationContracts.SERVICE,
                        documents.payloads().encode(task),
                        context.cancellation(),
                        IsolatedServiceCallScope.from(request)));
        documents.payloads().decode(response, SiteRegistrationContracts.Session.class);
        return new ExtensionResponse(response, 0);
    }

    private static void requireSettings(ExtensionRequest request, ExtensionExecutionContext context) {
        if (!request.workspaceId().equals(context.workspaceId())
                || request.threadId().isPresent()
                || request.turnId().isPresent()
                || request.unattendedExecutionScope().isPresent()) {
            throw new SecurityException("网站登记仅允许当前 Workspace 设置页的显式人工操作");
        }
        if (request.expectedRevision() != 0) {
            throw new IllegalArgumentException("网站登记通过会话代次检查并发，扩展文档版本必须为零");
        }
    }
}
