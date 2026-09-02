package com.javaclaw.builtin.extensions;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import com.javaclaw.builtin.contracts.SiteContracts;
import com.javaclaw.builtin.contracts.SiteManagementContracts;
import com.javaclaw.extension.spi.ExpectedRevisionBinding;
import com.javaclaw.extension.spi.ViewAction;
import com.javaclaw.extension.spi.ViewArgumentBinding;
import com.javaclaw.extension.spi.ViewBinding;
import com.javaclaw.extension.spi.ViewCommandBinding;
import com.javaclaw.extension.spi.ViewCondition;
import com.javaclaw.extension.spi.ViewConditionOperator;
import com.javaclaw.extension.spi.ViewDataSource;
import com.javaclaw.extension.spi.ViewField;
import com.javaclaw.extension.spi.ViewFieldType;
import com.javaclaw.extension.spi.ViewFieldValidation;
import com.javaclaw.extension.spi.ViewFormField;
import com.javaclaw.extension.spi.ViewOption;
import com.javaclaw.extension.spi.ViewOptionSource;
import com.javaclaw.extension.spi.ViewSchema;
import com.javaclaw.extension.spi.ViewSelectionMode;
import com.javaclaw.extension.spi.ViewStructuredItemField;
import com.javaclaw.extension.spi.ViewStructuredItemType;
import com.javaclaw.extension.spi.ViewStructuredItemValidation;
import com.javaclaw.extension.spi.ViewStructuredListField;

/** Site 管理页面的纯 ViewSchema v2 描述，不参与领域事务或权限解析。 */
final class SiteManagementView {
    private SiteManagementView() {}

    static ViewSchema create(String extensionId) {
        return new ViewSchema(
                ViewSchema.CURRENT_VERSION,
                extensionId + ".management",
                "Site 管理",
                dataSources(),
                List.of(
                        new ViewSchema.Card(
                                "site-authority-boundary",
                                "权限来源",
                                "Workspace 由当前请求隐式绑定。凭据与私网授权只绑定已有资源的精确版本；Origin 变化会清除旧绑定并立即使旧 Browser 会话失效。",
                                List.of()),
                        sitesTable(),
                        form("site-create", "新建 Site", SiteManagement.NEW_SOURCE, true),
                        form("site-edit", "编辑 Site", SiteManagement.EDIT_SOURCE, false),
                        credentialForm(),
                        privateNetworkForm()));
    }

    static SiteEditor editor(SiteContracts.Site site) {
        List<URI> origins = List.copyOf(site.allowedOrigins());
        List<SiteManagementContracts.AllowedOrigin> rows = IntStream.range(0, origins.size())
                .mapToObj(index -> new SiteManagementContracts.AllowedOrigin(
                        "origin-" + Math.addExact(index, 1), origins.get(index)))
                .toList();
        return new SiteEditor(
                site.id(),
                site.name(),
                site.origin(),
                rows,
                site.enabled(),
                site.revision(),
                site.authorityRevision(),
                site.credential().kind() != SiteContracts.CredentialKind.NONE,
                site.privateNetworkGrant().isPresent(),
                SiteContracts.CredentialKind.BEARER,
                "",
                "",
                "");
    }

    private static List<ViewDataSource> dataSources() {
        return List.of(
                new ViewDataSource("documents", "view.list", Map.of(), List.of(), 100),
                new ViewDataSource(SiteManagement.NEW_SOURCE, SiteManagement.VIEW_NEW, Map.of(), List.of(), 1),
                new ViewDataSource(
                        SiteManagement.EDIT_SOURCE,
                        SiteManagement.VIEW_SELECTED,
                        Map.of(),
                        List.of(
                                new ViewArgumentBinding("id", "documents", "id"),
                                new ViewArgumentBinding("revision", "documents", "revision")),
                        1),
                new ViewDataSource(
                        SiteManagement.CREDENTIAL_SOURCE, SiteManagement.VIEW_CREDENTIALS, Map.of(), List.of(), 100),
                new ViewDataSource(
                        SiteManagement.PRIVATE_NETWORK_SOURCE,
                        SiteManagement.VIEW_PRIVATE_NETWORK,
                        Map.of(),
                        List.of(),
                        100));
    }

    private static ViewSchema.Table sitesTable() {
        ViewAction clearCredential = new ViewAction(
                "清除凭据",
                SiteManagement.CREDENTIAL_CLEAR,
                Map.of(),
                Map.of("siteId", "id", "expectedAuthorityRevision", "authorityRevision"),
                new ExpectedRevisionBinding.RowField("revision"),
                true);
        ViewAction clearPrivateNetwork = new ViewAction(
                "清除私网授权",
                SiteManagement.PRIVATE_NETWORK_CLEAR,
                Map.of(),
                Map.of("siteId", "id", "expectedAuthorityRevision", "authorityRevision"),
                new ExpectedRevisionBinding.RowField("revision"),
                true);
        ViewAction delete = new ViewAction(
                "删除", "delete", Map.of(), Map.of("id", "id"), new ExpectedRevisionBinding.RowField("revision"), true);
        return new ViewSchema.Table(
                "sites",
                "Site",
                "documents",
                "id",
                List.of(
                        new ViewSchema.Column("name", "名称", Optional.of(200)),
                        new ViewSchema.Column("origin", "主 Origin", Optional.of(300)),
                        new ViewSchema.Column("enabled", "启用", Optional.of(80)),
                        new ViewSchema.Column("hasCredential", "已配置凭据", Optional.of(110)),
                        new ViewSchema.Column("hasPrivateGrant", "私网授权", Optional.of(100)),
                        new ViewSchema.Column("revision", "版本", Optional.of(80)),
                        new ViewSchema.Column("authorityRevision", "权限版本", Optional.of(90))),
                ViewSelectionMode.SINGLE,
                List.of(clearCredential, clearPrivateNetwork, delete));
    }

    private static ViewSchema.Form credentialForm() {
        ViewField kind = new ViewField(
                "kind",
                "凭据用途",
                ViewFieldType.CHOICE,
                new ViewBinding(SiteManagement.EDIT_SOURCE, "credentialKind"),
                Optional.of(SiteContracts.CredentialKind.BEARER.name()),
                ViewFieldValidation.required(true),
                List.of(
                        new ViewOption(SiteContracts.CredentialKind.BEARER.name(), "Bearer Token"),
                        new ViewOption(SiteContracts.CredentialKind.API_KEY_HEADER.name(), "API Key Header")),
                Optional.empty(),
                Optional.empty());
        ViewField credential = new ViewField(
                "credentialId",
                "Vault 凭据",
                ViewFieldType.CHOICE,
                new ViewBinding(SiteManagement.EDIT_SOURCE, "credentialId"),
                Optional.empty(),
                ViewFieldValidation.required(true),
                List.of(),
                Optional.of(new ViewOptionSource(SiteManagement.CREDENTIAL_SOURCE, "id", "label")),
                Optional.empty());
        ViewField header = new ViewField(
                "apiKeyHeader",
                "API Key Header",
                ViewFieldType.TEXT,
                new ViewBinding(SiteManagement.EDIT_SOURCE, "apiKeyHeader"),
                Optional.empty(),
                new ViewFieldValidation(
                        true, Optional.of(1), Optional.of(80), Optional.empty(), Optional.empty(), Optional.empty()),
                List.of(),
                Optional.empty(),
                Optional.of(new ViewCondition(
                        new ViewBinding(SiteManagement.EDIT_SOURCE, "credentialKind"),
                        ViewConditionOperator.EQUALS,
                        SiteContracts.CredentialKind.API_KEY_HEADER.name())));
        return new ViewSchema.Form(
                "site-credential-bind",
                "绑定 HTTP 凭据",
                List.of(kind, credential, header),
                authorityAction("绑定凭据", SiteManagement.CREDENTIAL_BIND, false));
    }

    private static ViewSchema.Form privateNetworkForm() {
        ViewField grant = new ViewField(
                "grantId",
                "Site 私网授权",
                ViewFieldType.CHOICE,
                new ViewBinding(SiteManagement.EDIT_SOURCE, "privateNetworkGrantId"),
                Optional.empty(),
                ViewFieldValidation.required(true),
                List.of(),
                Optional.of(new ViewOptionSource(SiteManagement.PRIVATE_NETWORK_SOURCE, "id", "label")),
                Optional.empty());
        return new ViewSchema.Form(
                "site-private-network-bind",
                "绑定私网授权",
                List.of(grant),
                authorityAction("绑定私网授权", SiteManagement.PRIVATE_NETWORK_BIND, true));
    }

    private static ViewAction authorityAction(String label, String command, boolean dangerous) {
        return new ViewAction(
                label,
                command,
                Map.of(),
                Map.of(),
                new ExpectedRevisionBinding.SourceRevision(SiteManagement.EDIT_SOURCE),
                dangerous,
                new ViewCommandBinding("siteId", new ViewBinding(SiteManagement.EDIT_SOURCE, "id")),
                new ViewCommandBinding(
                        "expectedAuthorityRevision", new ViewBinding(SiteManagement.EDIT_SOURCE, "authorityRevision")));
    }

    private static ViewSchema.Form form(String id, String title, String source, boolean create) {
        ViewAction save = create
                ? new ViewAction(
                        "创建 Site", SiteManagement.CREATE, Map.of(), Map.of(), new ExpectedRevisionBinding.None(), false)
                : new ViewAction(
                        "保存 Site",
                        SiteManagement.UPDATE,
                        Map.of(),
                        Map.of(),
                        new ExpectedRevisionBinding.SourceRevision(source),
                        false,
                        new ViewCommandBinding("id", new ViewBinding(source, "id")));
        return new ViewSchema.Form(id, title, fields(source, create), save);
    }

    private static List<? extends ViewFormField> fields(String source, boolean create) {
        List<ViewFormField> fields = new ArrayList<>();
        if (create) {
            fields.add(text(source, "id", "Site 标识", 100, Optional.empty()));
        }
        fields.add(text(source, "name", "名称", 200, Optional.empty()));
        fields.add(text(source, "origin", "主 HTTPS Origin", 2_048, Optional.of("https://example.com")));
        fields.add(allowedOrigins(source));
        fields.add(new ViewField(
                "enabled",
                "启用",
                ViewFieldType.BOOLEAN,
                new ViewBinding(source, "enabled"),
                Optional.of("true"),
                ViewFieldValidation.required(false),
                List.of(),
                Optional.empty(),
                Optional.empty()));
        return List.copyOf(fields);
    }

    private static ViewField text(
            String source, String name, String label, int maximumLength, Optional<String> initialValue) {
        ViewFieldValidation validation = new ViewFieldValidation(
                true, Optional.of(1), Optional.of(maximumLength), Optional.empty(), Optional.empty(), Optional.empty());
        return new ViewField(
                name,
                label,
                ViewFieldType.TEXT,
                new ViewBinding(source, name),
                initialValue,
                validation,
                List.of(),
                Optional.empty(),
                Optional.empty());
    }

    private static ViewStructuredListField allowedOrigins(String source) {
        ViewStructuredItemValidation validation = new ViewStructuredItemValidation(
                true,
                Optional.of(1),
                Optional.of(2_048),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
        ViewStructuredItemField origin = new ViewStructuredItemField(
                "origin",
                "HTTPS Origin",
                ViewStructuredItemType.TEXT,
                Optional.empty(),
                List.of(),
                validation,
                List.of());
        return new ViewStructuredListField(
                "allowedOrigins",
                "允许的精确 Origin",
                new ViewBinding(source, "allowedOrigins"),
                1,
                SiteManagementContracts.MAXIMUM_ALLOWED_ORIGINS,
                "itemKey",
                List.of(origin),
                List.of(Map.of("itemKey", "origin-1", "origin", "https://example.com")),
                Optional.empty());
    }

    record SiteEditor(
            String id,
            String name,
            URI origin,
            List<SiteManagementContracts.AllowedOrigin> allowedOrigins,
            boolean enabled,
            long revision,
            long authorityRevision,
            boolean hasCredential,
            boolean hasPrivateGrant,
            SiteContracts.CredentialKind credentialKind,
            String credentialId,
            String apiKeyHeader,
            String privateNetworkGrantId) {}
}
