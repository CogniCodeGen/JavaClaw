package com.javaclaw.desktop.view;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.javaclaw.extension.spi.ExpectedRevisionBinding;
import com.javaclaw.extension.spi.ViewAction;
import com.javaclaw.extension.spi.ViewArgumentBinding;
import com.javaclaw.extension.spi.ViewBinding;
import com.javaclaw.extension.spi.ViewCommandBinding;
import com.javaclaw.extension.spi.ViewDataSource;
import com.javaclaw.extension.spi.ViewField;
import com.javaclaw.extension.spi.ViewFieldType;
import com.javaclaw.extension.spi.ViewFieldValidation;
import com.javaclaw.extension.spi.ViewSchema;
import com.javaclaw.extension.spi.ViewSelectionMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ViewSchemaPolicyTest {
    @Test
    void acceptsBoundedVersionTwoTableAndGraph() {
        ViewSchema schema = new ViewSchema(
                ViewSchema.CURRENT_VERSION,
                "knowledge.main",
                "知识",
                List.of(
                        new ViewDataSource("documents", "view.list", Map.of(), List.of(), 100),
                        new ViewDataSource("edges", "view.edges", Map.of(), List.of(), 100)),
                List.of(
                        new ViewSchema.Table(
                                "documents",
                                "文档",
                                "documents",
                                "id",
                                List.of(new ViewSchema.Column("id", "标识", Optional.of(180))),
                                ViewSelectionMode.SINGLE,
                                List.of()),
                        new ViewSchema.Graph(
                                "relations", "关系", "documents", "edges", "id", "title", "kind", "from", "to")));

        assertEquals(schema, ViewSchemaPolicy.requireSupported(schema));
    }

    @Test
    void rejectsOldVersionDuplicateNodeAndUnknownDataSource() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ViewSchema(
                        1, "old", "旧页面", List.of(), List.of(new ViewSchema.Card("body", "正文", "内容", List.of()))));
        assertThrows(
                IllegalArgumentException.class,
                () -> ViewSchemaPolicy.requireSupported(new ViewSchema(
                        ViewSchema.CURRENT_VERSION,
                        "duplicates",
                        "重复",
                        List.of(new ViewDataSource("content", "view.read", Map.of(), List.of(), 1)),
                        List.of(
                                new ViewSchema.Markdown("body", "第一段", new ViewBinding("content", "first")),
                                new ViewSchema.Markdown("body", "第二段", new ViewBinding("content", "second"))))));
        assertThrows(
                IllegalArgumentException.class,
                () -> ViewSchemaPolicy.requireSupported(new ViewSchema(
                        ViewSchema.CURRENT_VERSION,
                        "unknown-source",
                        "未知来源",
                        List.of(),
                        List.of(new ViewSchema.Markdown("body", "正文", new ViewBinding("missing", "body"))))));
    }

    @Test
    void rejectsUnsafeOperationAndRowBindingOnStaticCard() {
        ViewAction unsafe = new ViewAction(
                "打开", "https://host/path", Map.of(), Map.of(), new ExpectedRevisionBinding.None(), false);
        ViewAction rowAction =
                new ViewAction("执行", "run", Map.of(), Map.of("id", "id"), new ExpectedRevisionBinding.None(), false);

        assertThrows(
                IllegalArgumentException.class,
                () -> ViewSchemaPolicy.requireSupported(new ViewSchema(
                        ViewSchema.CURRENT_VERSION,
                        "unsafe",
                        "不安全",
                        List.of(),
                        List.of(new ViewSchema.Card("card", "卡片", "内容", List.of(unsafe))))));
        assertThrows(
                IllegalArgumentException.class,
                () -> ViewSchemaPolicy.requireSupported(new ViewSchema(
                        ViewSchema.CURRENT_VERSION,
                        "row-binding",
                        "错误绑定",
                        List.of(),
                        List.of(new ViewSchema.Card("card", "卡片", "内容", List.of(rowAction))))));
    }

    @Test
    void rejectsRowBindingWithoutSelectionAndUnsafeRowField() {
        ViewDataSource source = new ViewDataSource("rows", "view.list", Map.of(), List.of(), 20);
        ViewAction rowAction =
                new ViewAction("执行", "run", Map.of(), Map.of("id", "id"), new ExpectedRevisionBinding.None(), false);
        ViewAction unsafeField = new ViewAction(
                "执行", "run", Map.of(), Map.of("id", "${row.id}"), new ExpectedRevisionBinding.None(), false);

        assertThrows(
                IllegalArgumentException.class,
                () -> ViewSchemaPolicy.requireSupported(tableSchema(source, ViewSelectionMode.NONE, rowAction)));
        assertThrows(
                IllegalArgumentException.class,
                () -> ViewSchemaPolicy.requireSupported(tableSchema(source, ViewSelectionMode.SINGLE, unsafeField)));
    }

    @Test
    void acceptsOnlyDirectBindingFromPrecedingSingleSelectionSource() {
        ViewDataSource definitions = new ViewDataSource("definitions", "view.list", Map.of(), List.of(), 20);
        ViewDataSource nodes = new ViewDataSource(
                "nodes",
                "graph/node/view.list",
                Map.of(),
                List.of(new ViewArgumentBinding("definitionId", "definitions", "id")),
                100);
        ViewSchema.Table master = new ViewSchema.Table(
                "definitions",
                "定义",
                "definitions",
                "id",
                List.of(new ViewSchema.Column("id", "标识", Optional.empty())),
                ViewSelectionMode.SINGLE,
                List.of());
        ViewSchema valid = new ViewSchema(
                ViewSchema.CURRENT_VERSION, "master-detail", "主从", List.of(definitions, nodes), List.of(master));

        assertEquals(valid, ViewSchemaPolicy.requireSupported(valid));
        ViewDataSource forward = new ViewDataSource(
                "nodes",
                "graph/node/view.list",
                Map.of(),
                List.of(new ViewArgumentBinding("definitionId", "definitions", "id")),
                100);
        assertThrows(
                IllegalArgumentException.class,
                () -> ViewSchemaPolicy.requireSupported(new ViewSchema(
                        ViewSchema.CURRENT_VERSION,
                        "forward",
                        "前向引用",
                        List.of(forward, definitions),
                        List.of(master))));
        ViewSchema.Table notSelectable = new ViewSchema.Table(
                "definitions",
                "定义",
                "definitions",
                "id",
                List.of(new ViewSchema.Column("id", "标识", Optional.empty())),
                ViewSelectionMode.NONE,
                List.of());
        assertThrows(
                IllegalArgumentException.class,
                () -> ViewSchemaPolicy.requireSupported(new ViewSchema(
                        ViewSchema.CURRENT_VERSION,
                        "not-selectable",
                        "无选择",
                        List.of(definitions, nodes),
                        List.of(notSelectable))));
    }

    @Test
    void rejectsUndeclaredAndExpressionShapedCommandBindings() {
        ViewDataSource profiles = new ViewDataSource("profiles", "profile/list", Map.of(), List.of(), 20);
        ViewAction undeclared = boundAction("profileId", "missing", "id");
        ViewAction expression = boundAction("profileId", "profiles", "${selected.id}");

        assertThrows(
                IllegalArgumentException.class, () -> ViewSchemaPolicy.requireSupported(card(profiles, undeclared)));
        assertThrows(
                IllegalArgumentException.class, () -> ViewSchemaPolicy.requireSupported(card(profiles, expression)));
    }

    @Test
    void rejectsFormFieldThatCouldOverrideAuthoritativeCommandArgument() {
        ViewDataSource profile = new ViewDataSource("profile", "profile/read", Map.of(), List.of(), 1);
        ViewField malicious = new ViewField(
                "profileId",
                "Profile ID",
                ViewFieldType.TEXT,
                new ViewBinding("profile", "name"),
                Optional.empty(),
                ViewFieldValidation.required(true),
                List.of(),
                Optional.empty(),
                Optional.empty());
        ViewAction save = boundAction("profileId", "profile", "id");
        ViewSchema schema = new ViewSchema(
                ViewSchema.CURRENT_VERSION,
                "profile.edit",
                "Profile",
                List.of(profile),
                List.of(new ViewSchema.Form("editor", "编辑", List.of(malicious), save)));

        assertThrows(IllegalArgumentException.class, () -> ViewSchemaPolicy.requireSupported(schema));
    }

    private static ViewSchema tableSchema(ViewDataSource source, ViewSelectionMode selection, ViewAction action) {
        return new ViewSchema(
                ViewSchema.CURRENT_VERSION,
                "row-actions",
                "行操作",
                List.of(source),
                List.of(new ViewSchema.Table(
                        "rows",
                        "记录",
                        "rows",
                        "id",
                        List.of(new ViewSchema.Column("id", "标识", Optional.empty())),
                        selection,
                        List.of(action))));
    }

    private static ViewSchema card(ViewDataSource source, ViewAction action) {
        return new ViewSchema(
                ViewSchema.CURRENT_VERSION,
                "command-binding",
                "绑定",
                List.of(source),
                List.of(new ViewSchema.Card("card", "操作", "安全操作", List.of(action))));
    }

    private static ViewAction boundAction(String argument, String source, String field) {
        return new ViewAction(
                "执行",
                "run",
                Map.of(),
                Map.of(),
                new ExpectedRevisionBinding.None(),
                false,
                new ViewCommandBinding(argument, new ViewBinding(source, field)));
    }
}
