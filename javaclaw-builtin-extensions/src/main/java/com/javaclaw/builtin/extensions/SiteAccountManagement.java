package com.javaclaw.builtin.extensions;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.builtin.contracts.SiteAccountContracts;
import com.javaclaw.builtin.contracts.SiteContracts;
import com.javaclaw.extension.spi.ExpectedRevisionBinding;
import com.javaclaw.extension.spi.ExtensionContribution;
import com.javaclaw.extension.spi.ExtensionContributions;
import com.javaclaw.extension.spi.ExtensionExecutionContext;
import com.javaclaw.extension.spi.ExtensionRequest;
import com.javaclaw.extension.spi.ExtensionResponse;
import com.javaclaw.extension.spi.IsolatedServiceInvocation;
import com.javaclaw.extension.spi.ViewAction;
import com.javaclaw.extension.spi.ViewArgumentBinding;
import com.javaclaw.extension.spi.ViewDataSource;
import com.javaclaw.extension.spi.ViewQueryRequest;
import com.javaclaw.extension.spi.ViewQueryResult;
import com.javaclaw.extension.spi.ViewSchema;
import com.javaclaw.extension.spi.ViewSelectionMode;

/** Site 账号的显式查询、管理命令与脱敏页面；秘密仅走宿主密封命令入口。 */
final class SiteAccountManagement {
    private final ManagedDocumentResource<SiteContracts.Site> documents;

    SiteAccountManagement(ManagedDocumentResource<SiteContracts.Site> documents) {
        this.documents = documents;
    }

    List<ExtensionContribution> contributions() {
        return List.of(
                new ExtensionContributions.Query(
                        "site.accounts.query", Set.of("account/list", "account/view"), this::query),
                new ExtensionContributions.Command(
                        "site.accounts.command",
                        Set.of(
                                "account/create",
                                "account/update",
                                "account/default",
                                "account/logout",
                                "account/delete"),
                        this::command),
                new ExtensionContributions.Tool(
                        "site.accounts.tool",
                        documents.readOnlyTool(
                                "accounts", "列出网站内可选择的账号名称与登录态配置状态，不返回用户名密码", schema(), Set.of("site", "account")),
                        this::query),
                new ExtensionContributions.View("site.accounts", view()));
    }

    private ExtensionResponse query(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        if (request.operation().equals("account/view")) {
            ViewQueryRequest query = documents.payloads().decode(request.payload(), ViewQueryRequest.class);
            Object site = query.arguments().get("siteId");
            if (site == null || site.toString().isBlank()) {
                return viewResult(query.dataSourceId(), List.of());
            }
            CanonicalPayload payload =
                    documents.payloads().encode(new SiteAccountContracts.ListRequest(site.toString()));
            SiteAccountContracts.AccountList accounts = documents
                    .payloads()
                    .decode(invoke(request, context, "account/list", payload), SiteAccountContracts.AccountList.class);
            return viewResult(query.dataSourceId(), accounts.accounts());
        }
        return new ExtensionResponse(invoke(request, context, "account/list", request.payload()), 0);
    }

    private ExtensionResponse viewResult(String source, List<SiteAccountContracts.AccountProjection> accounts) {
        ViewQueryResult result = new ViewQueryResult(
                source,
                accounts.stream().map(documents.payloads()::encode).toList(),
                documents.payloads().encode(Map.of()),
                "",
                false,
                0);
        return new ExtensionResponse(documents.payloads().encode(result), 0);
    }

    private ExtensionResponse command(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        CanonicalPayload result = invoke(request, context, request.operation(), request.payload());
        SiteAccountContracts.AccountProjection account =
                documents.payloads().decode(result, SiteAccountContracts.AccountProjection.class);
        return new ExtensionResponse(result, account.revision());
    }

    private CanonicalPayload invoke(
            ExtensionRequest request, ExtensionExecutionContext context, String operation, CanonicalPayload payload)
            throws Exception {
        SiteAccountContracts.ServiceRequest task = new SiteAccountContracts.ServiceRequest(
                operation, payload, request.idempotencyKey().orElse(""), request.expectedRevision());
        return context.services()
                .invoke(new IsolatedServiceInvocation(
                        documents.extensionId(),
                        request.workspaceId(),
                        context.effectivePermissions(),
                        SiteAccountContracts.SERVICE,
                        documents.payloads().encode(task),
                        context.cancellation()));
    }

    private CanonicalPayload schema() {
        return ContractSchemaFactory.document(
                documents.payloads(),
                documents.extensionId().value() + "/accounts-query/v1",
                "Site accounts query",
                Map.of("siteId", ContractSchemaFactory.string()),
                List.of("siteId"));
    }

    private ViewSchema view() {
        List<ViewDataSource> sources = List.of(
                new ViewDataSource("documents", "view.list", Map.of(), List.of(), 100),
                new ViewDataSource(
                        "accounts",
                        "account/view",
                        Map.of(),
                        List.of(new ViewArgumentBinding("siteId", "documents", "id")),
                        100));
        ViewSchema.Table sites = new ViewSchema.Table(
                "account-sites",
                "选择网站",
                "documents",
                "id",
                List.of(new ViewSchema.Column("name", "网站", Optional.of(240))),
                ViewSelectionMode.SINGLE,
                List.of());
        ViewSchema.Table accounts = new ViewSchema.Table(
                "site-accounts",
                "网站账号",
                "accounts",
                "accountId",
                List.of(
                        new ViewSchema.Column("name", "账号", Optional.of(180)),
                        new ViewSchema.Column("defaultAccount", "默认", Optional.of(70)),
                        new ViewSchema.Column("enabled", "启用", Optional.of(70)),
                        new ViewSchema.Column("passwordConfigured", "已保存密码", Optional.of(100)),
                        new ViewSchema.Column("loginStateConfigured", "已保存登录态", Optional.of(110))),
                ViewSelectionMode.SINGLE,
                List.of(
                        action("设为默认", "account/default", false),
                        action("注销登录态", "account/logout", true),
                        action("删除账号", "account/delete", true)));
        return new ViewSchema(
                ViewSchema.CURRENT_VERSION,
                documents.extensionId().value() + ".accounts",
                "网站账号",
                sources,
                List.of(sites, accounts));
    }

    private static ViewAction action(String title, String operation, boolean dangerous) {
        return new ViewAction(
                title,
                operation,
                Map.of(),
                Map.of("siteId", "siteId", "accountId", "accountId"),
                new ExpectedRevisionBinding.RowField("revision"),
                dangerous);
    }
}
