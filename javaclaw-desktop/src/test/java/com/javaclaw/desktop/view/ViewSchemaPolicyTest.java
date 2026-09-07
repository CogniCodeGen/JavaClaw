package com.javaclaw.desktop.view;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

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
import com.javaclaw.extension.spi.ViewPlatformDataSource;
import com.javaclaw.extension.spi.ViewSchema;
import com.javaclaw.extension.spi.ViewSelectionMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        ViewDataSource roles = new ViewDataSource("roles", "role/list", Map.of(), List.of(), 20);
        ViewAction undeclared = boundAction("roleId", "missing", "id");
        ViewAction expression = boundAction("roleId", "roles", "${selected.id}");

        assertThrows(IllegalArgumentException.class, () -> ViewSchemaPolicy.requireSupported(card(roles, undeclared)));
        assertThrows(IllegalArgumentException.class, () -> ViewSchemaPolicy.requireSupported(card(roles, expression)));
    }

    @Test
    void rejectsFormFieldThatCouldOverrideAuthoritativeCommandArgument() {
        ViewDataSource role = new ViewDataSource("role", "role/read", Map.of(), List.of(), 1);
        ViewField malicious = new ViewField(
                "roleId",
                "Role ID",
                ViewFieldType.TEXT,
                new ViewBinding("role", "name"),
                Optional.empty(),
                ViewFieldValidation.required(true),
                List.of(),
                Optional.empty(),
                Optional.empty());
        ViewAction save = boundAction("roleId", "role", "id");
        ViewSchema schema = new ViewSchema(
                ViewSchema.CURRENT_VERSION,
                "role.edit",
                "Role",
                List.of(role),
                List.of(new ViewSchema.Form("editor", "编辑", List.of(malicious), save)));

        assertThrows(IllegalArgumentException.class, () -> ViewSchemaPolicy.requireSupported(schema));
    }

    @Test
    void 拒绝超出页面规模和静态文本边界的声明() {
        List<ViewDataSource> tooManySources = IntStream.range(0, 33)
                .mapToObj(index -> new ViewDataSource("source-" + index, "view/read", Map.of(), List.of(), 1))
                .toList();
        List<ViewSchema.Node> tooManyNodes = IntStream.range(0, 33)
                .mapToObj(index -> new ViewSchema.Card("card-" + index, "说明", "内容", List.of()))
                .map(ViewSchema.Node.class::cast)
                .toList();

        assertRejected(schema("too-many-sources", tooManySources, List.of(card())), "数据源过多");
        assertRejected(schema("too-many-nodes", List.of(), tooManyNodes), "顶层节点过多");
        assertRejected(
                schema(
                        "long-copy",
                        List.of(),
                        List.of(new ViewSchema.Card("copy", "说明", "x".repeat(4_001), List.of()))),
                "静态文本过长");
    }

    @Test
    void 拒绝重复或不安全的页面标识() {
        ViewDataSource first = new ViewDataSource("records", "view/list", Map.of(), List.of(), 20);
        ViewDataSource duplicate = new ViewDataSource("records", "view/read", Map.of(), List.of(), 1);

        assertRejected(schema("duplicate-source", List.of(first, duplicate), List.of(card())), "数据源 ID 重复");
        assertRejected(
                schema("unsafe-node", List.of(), List.of(new ViewSchema.Card("unsafe/id", "说明", "内容", List.of()))),
                "节点 ID 不安全");
    }

    @Test
    void 平台数据源拒绝未知操作参数绑定和过大分页() {
        ViewDataSource master = new ViewDataSource("records", "view/list", Map.of(), List.of(), 20);
        ViewDataSource withArguments = new ViewDataSource(
                "tools", ViewPlatformDataSource.TOOL_CATALOG, Map.of("kind", "read"), List.of(), 100);
        ViewDataSource withBinding = new ViewDataSource(
                "tools",
                ViewPlatformDataSource.TOOL_CATALOG,
                Map.of(),
                List.of(new ViewArgumentBinding("kind", "records", "kind")),
                100);
        ViewDataSource oversized =
                new ViewDataSource("tools", ViewPlatformDataSource.TOOL_CATALOG, Map.of(), List.of(), 101);
        ViewDataSource unknown = new ViewDataSource("tools", "platform/unknown", Map.of(), List.of(), 20);

        assertRejected(schema("platform-arguments", List.of(withArguments), List.of(card())), "不能携带扩展参数");
        assertRejected(schema("platform-binding", List.of(master, withBinding), List.of(card())), "不能携带扩展参数");
        assertRejected(schema("platform-page", List.of(oversized), List.of(card())), "页大小超出限制");
        assertRejected(schema("platform-unknown", List.of(unknown), List.of(card())), "未知平台数据源");

        ViewDataSource outputFields =
                new ViewDataSource("fields", ViewPlatformDataSource.TOOL_OUTPUT_FIELDS, Map.of(), List.of(), 200);
        ViewSchema valid = schema("platform-valid", List.of(outputFields), List.of(card()));
        assertEquals(valid, ViewSchemaPolicy.requireSupported(valid));
    }

    @Test
    void 数据源动态参数不得覆盖固定参数() {
        ViewDataSource master = new ViewDataSource("records", "view/list", Map.of(), List.of(), 20);
        ViewDataSource detail = new ViewDataSource(
                "detail",
                "detail/read",
                Map.of("recordId", "fixed"),
                List.of(new ViewArgumentBinding("recordId", "records", "id")),
                20);

        assertRejected(schema("duplicate-argument", List.of(master, detail), List.of(card())), "数据源参数重复");
    }

    @Test
    void 表单字段名称和绑定都必须唯一() {
        ViewDataSource editor = new ViewDataSource("editor", "view/read", Map.of(), List.of(), 1);
        ViewField name = field("name", new ViewBinding("editor", "name"));
        ViewField duplicateName = field("name", new ViewBinding("editor", "alias"));
        ViewField duplicateBinding = field("alias", new ViewBinding("editor", "name"));

        assertRejected(formSchema(editor, List.of(name, duplicateName), submit()), "字段名或绑定重复");
        assertRejected(formSchema(editor, List.of(name, duplicateBinding), submit()), "字段名或绑定重复");
    }

    @Test
    void 非行节点拒绝行参数和行revision绑定() {
        ViewDataSource editor = new ViewDataSource("editor", "view/read", Map.of(), List.of(), 1);
        ViewAction rowArgument = new ViewAction(
                "保存", "editor/put", Map.of(), Map.of("recordId", "id"), new ExpectedRevisionBinding.None(), false);
        ViewAction rowRevision = new ViewAction(
                "删除", "editor/delete", Map.of(), Map.of(), new ExpectedRevisionBinding.RowField("revision"), false);

        assertRejected(
                formSchema(editor, List.of(field("name", new ViewBinding("editor", "name"))), rowArgument), "不能声明行参数");
        assertRejected(
                schema(
                        "card-row-revision",
                        List.of(),
                        List.of(new ViewSchema.Card("card", "操作", "说明", List.of(rowRevision)))),
                "不能从行字段读取 expected revision");
    }

    @Test
    void 无选择表格允许不依赖行字段的固定操作() {
        ViewDataSource records = new ViewDataSource("rows", "view/list", Map.of(), List.of(), 20);
        ViewAction refresh = new ViewAction(
                "重新计算",
                "records/rebuild",
                Map.of("mode", "safe"),
                Map.of(),
                new ExpectedRevisionBinding.SourceRevision("rows"),
                false);
        ViewSchema schema = tableSchema(records, ViewSelectionMode.NONE, refresh);

        assertEquals(schema, ViewSchemaPolicy.requireSupported(schema));
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

    private static ViewSchema formSchema(
            ViewDataSource source, List<? extends com.javaclaw.extension.spi.ViewFormField> fields, ViewAction action) {
        return schema("form-policy", List.of(source), List.of(new ViewSchema.Form("editor", "编辑", fields, action)));
    }

    private static ViewField field(String name, ViewBinding binding) {
        return new ViewField(
                name,
                name,
                ViewFieldType.TEXT,
                binding,
                Optional.empty(),
                ViewFieldValidation.required(false),
                List.of(),
                Optional.empty(),
                Optional.empty());
    }

    private static ViewAction submit() {
        return new ViewAction("保存", "editor/put", Map.of(), Map.of(), new ExpectedRevisionBinding.None(), false);
    }

    private static ViewSchema.Card card() {
        return new ViewSchema.Card("card", "说明", "内容", List.of());
    }

    private static ViewSchema schema(
            String viewId, List<ViewDataSource> sources, List<? extends ViewSchema.Node> nodes) {
        return new ViewSchema(
                ViewSchema.CURRENT_VERSION,
                viewId,
                "策略",
                sources,
                nodes.stream().map(ViewSchema.Node.class::cast).toList());
    }

    private static void assertRejected(ViewSchema schema, String message) {
        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, () -> ViewSchemaPolicy.requireSupported(schema));
        assertTrue(failure.getMessage().contains(message), failure::getMessage);
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
