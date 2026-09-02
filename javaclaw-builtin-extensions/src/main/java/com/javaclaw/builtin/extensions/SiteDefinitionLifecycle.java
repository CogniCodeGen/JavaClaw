package com.javaclaw.builtin.extensions;

import com.javaclaw.builtin.contracts.DocumentContracts;
import com.javaclaw.builtin.contracts.SiteContracts;
import com.javaclaw.extension.spi.ExtensionExecutionContext;
import com.javaclaw.extension.spi.ExtensionRequest;
import com.javaclaw.extension.spi.ExtensionResponse;
import com.javaclaw.extension.spi.IsolatedServiceInvocation;

/** 校验 Site authority revision，并在提交后实时撤销旧 Browser 会话。 */
final class SiteDefinitionLifecycle implements ManagedDocumentBehavior<SiteContracts.Site> {
    @Override
    public void afterCommit(
            ManagedDocumentResource<SiteContracts.Site> documents,
            ExtensionRequest request,
            ExtensionResponse response,
            ExtensionExecutionContext context)
            throws Exception {
        SiteContracts.AuthorityInvalidation invalidation = invalidation(documents, request, response);
        context.services()
                .invoke(new IsolatedServiceInvocation(
                        documents.extensionId(),
                        context.workspaceId(),
                        context.effectivePermissions(),
                        SiteContracts.BROWSER_INVALIDATE_SERVICE,
                        documents.payloads().encode(invalidation),
                        context.cancellation()));
    }

    private static SiteContracts.AuthorityInvalidation invalidation(
            ManagedDocumentResource<SiteContracts.Site> documents,
            ExtensionRequest request,
            ExtensionResponse response) {
        if (!"delete".equals(request.operation())) {
            throw new IllegalArgumentException("unknown Site document command");
        }
        DocumentContracts.Key key = documents.payloads().decode(request.payload(), DocumentContracts.Key.class);
        return new SiteContracts.AuthorityInvalidation(key.id(), 0);
    }

    /** 强类型管理写入提交后立即撤销旧 authority 绑定的 Browser 会话。 */
    void afterManagedCommit(
            ManagedDocumentResource<SiteContracts.Site> documents,
            ExtensionResponse response,
            ExtensionExecutionContext context)
            throws Exception {
        SiteContracts.Projection site = documents.payloads().decode(response.payload(), SiteContracts.Projection.class);
        context.services()
                .invoke(new IsolatedServiceInvocation(
                        documents.extensionId(),
                        context.workspaceId(),
                        context.effectivePermissions(),
                        SiteContracts.BROWSER_INVALIDATE_SERVICE,
                        documents
                                .payloads()
                                .encode(new SiteContracts.AuthorityInvalidation(site.id(), site.authorityRevision())),
                        context.cancellation()));
    }

    /** 根据服务端当前快照计算下一 authority revision。 */
    long nextAuthority(SiteContracts.Site next, SiteContracts.Site current) {
        boolean changed = next.enabled() != current.enabled()
                || !next.origin().equals(current.origin())
                || !next.allowedOrigins().equals(current.allowedOrigins())
                || !next.credential().equals(current.credential())
                || !next.privateNetworkGrant().equals(current.privateNetworkGrant());
        return changed ? Math.addExact(current.authorityRevision(), 1) : current.authorityRevision();
    }
}
