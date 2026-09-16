package com.javaclaw.desktop.settings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.extension.spi.ExpectedRevisionBinding;
import com.javaclaw.extension.spi.ViewAction;
import com.javaclaw.extension.spi.ViewArgumentBinding;
import com.javaclaw.extension.spi.ViewBinding;
import com.javaclaw.extension.spi.ViewCommandBinding;
import com.javaclaw.extension.spi.ViewDataSource;
import com.javaclaw.extension.spi.ViewField;
import com.javaclaw.extension.spi.ViewFieldType;
import com.javaclaw.extension.spi.ViewFieldValidation;
import com.javaclaw.extension.spi.ViewFormField;
import com.javaclaw.extension.spi.ViewSchema;
import com.javaclaw.extension.spi.ViewSelectionMode;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ViewSchemaWireCodec;

/** SDK 形状的页面夹具；原生截图模式可以读取构建生成的真实扩展 Schema，保持测试模块依赖边界。 */
final class SiteSettingsTestSchema {
    private SiteSettingsTestSchema() {}

    static ViewSchema create() {
        String schemaPath = System.getProperty("javaclaw.site.evidence.schema", "");
        if (!schemaPath.isEmpty()) {
            try {
                CanonicalJson json = new CanonicalJson();
                return new ViewSchemaWireCodec(json).decode(json.parse(Files.readString(Path.of(schemaPath))));
            } catch (IOException failure) {
                throw new IllegalStateException("无法读取构建输出的网站页面定义", failure);
            }
        }
        return new ViewSchema(
                2,
                BuiltinExtensionIds.SITE + ".management",
                "网站管理",
                List.of(
                        new ViewDataSource("documents", "view.list", Map.of(), List.of(), 100),
                        new ViewDataSource("newSite", "site/view.new", Map.of(), List.of(), 1),
                        new ViewDataSource(
                                "siteEditor",
                                "site/view.selected",
                                Map.of(),
                                List.of(new ViewArgumentBinding("id", "documents", "id")),
                                1)),
                List.of(
                        table(),
                        form(true),
                        form(false),
                        new ViewSchema.Form(
                                "site-credential-bind",
                                "绑定 HTTP 凭据",
                                List.of(field("siteEditor", "credentialId", "凭据", Optional.empty())),
                                action("绑定凭据", "site/credential/bind", false)),
                        new ViewSchema.Form(
                                "site-private-network-bind",
                                "绑定私网授权",
                                List.of(field("siteEditor", "privateNetworkGrantId", "私网授权", Optional.empty())),
                                action("绑定私网授权", "site/private-network/bind", true)),
                        new ViewSchema.Card(
                                "site-actions",
                                "危险操作",
                                "删除网站会同时清除它的账号及登录会话。",
                                List.of(action("删除网站", "delete", true)))));
    }

    private static ViewSchema.Table table() {
        return new ViewSchema.Table(
                "sites",
                "网站列表",
                "documents",
                "id",
                List.of(
                        new ViewSchema.Column("name", "名称", Optional.of(180)),
                        new ViewSchema.Column("origin", "网址", Optional.of(300)),
                        new ViewSchema.Column("enabled", "状态", Optional.of(80))),
                ViewSelectionMode.SINGLE,
                List.of());
    }

    private static ViewSchema.Form form(boolean create) {
        String source = create ? "newSite" : "siteEditor";
        List<ViewFormField> fields = new ArrayList<>();
        if (create) {
            fields.add(field(source, "id", "网站标识", Optional.empty()));
        }
        fields.add(field(source, "name", "名称", Optional.empty()));
        fields.add(field(source, "origin", "主 HTTPS Origin", Optional.of("https://example.com")));
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
        ViewAction save = create
                ? new ViewAction("创建网站", "site/create", Map.of(), Map.of(), new ExpectedRevisionBinding.None(), false)
                : action("保存网站", "site/update", false);
        return new ViewSchema.Form(create ? "site-create" : "site-edit", create ? "新建网站" : "网站信息", fields, save);
    }

    private static ViewField field(String source, String name, String label, Optional<String> initial) {
        return new ViewField(
                name,
                label,
                ViewFieldType.TEXT,
                new ViewBinding(source, name),
                initial,
                ViewFieldValidation.required(true),
                List.of(),
                Optional.empty(),
                Optional.empty());
    }

    private static ViewAction action(String label, String operation, boolean dangerous) {
        return new ViewAction(
                label,
                operation,
                Map.of(),
                Map.of(),
                new ExpectedRevisionBinding.SourceRevision("siteEditor"),
                dangerous,
                new ViewCommandBinding("id", new ViewBinding("siteEditor", "id")));
    }
}
