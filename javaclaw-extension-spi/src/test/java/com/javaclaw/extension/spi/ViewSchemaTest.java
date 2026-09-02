package com.javaclaw.extension.spi;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CanonicalPayload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewSchemaTest {
    @Test
    void supportsBindingsValidationDynamicFieldsPagingAndGraphWithoutExecutableExpressions() {
        ViewDataSource documents = new ViewDataSource("documents", "view.list", Map.of("kind", "plan"), List.of(), 50);
        ViewBinding name = new ViewBinding("documents", "name");
        ViewField field = new ViewField(
                "name",
                "名称",
                ViewFieldType.TEXT,
                name,
                Optional.of("新计划"),
                new ViewFieldValidation(
                        true, Optional.of(2), Optional.of(80), Optional.empty(), Optional.empty(), Optional.empty()),
                List.of(),
                Optional.empty(),
                Optional.of(new ViewCondition(name, ViewConditionOperator.NOT_EQUALS, "hidden")));
        ViewAction save = new ViewAction(
                "保存", "put", Map.of(), Map.of(), new ExpectedRevisionBinding.SourceRevision("documents"), false);
        ViewSchema schema = new ViewSchema(
                ViewSchema.CURRENT_VERSION,
                "extension.page",
                "页面",
                List.of(documents),
                List.of(
                        new ViewSchema.Form("form", "编辑", List.of(field), save),
                        new ViewSchema.Table(
                                "table",
                                "文档",
                                "documents",
                                "id",
                                List.of(new ViewSchema.Column("name", "名称", Optional.of(120))),
                                ViewSelectionMode.SINGLE,
                                List.of()),
                        new ViewSchema.Graph(
                                "graph", "流程", "documents", "documents", "id", "name", "kind", "from", "to")));

        assertEquals(2, schema.schemaVersion());
        assertEquals("extension.page", schema.viewId());
        assertEquals(3, schema.nodes().size());
        assertEquals(Optional.of("新计划"), field.initialValue());
    }

    @Test
    void rejectsOldVersionInvalidBoundsAndAttachmentInitialValue() {
        ViewSchema.Markdown markdown = new ViewSchema.Markdown("body", "正文", new ViewBinding("content", "markdown"));

        assertThrows(
                IllegalArgumentException.class, () -> new ViewSchema(1, "view", "标题", List.of(), List.of(markdown)));
        assertThrows(IllegalArgumentException.class, () -> new ViewDataSource("data", "read", Map.of(), List.of(), 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ViewFieldValidation(
                        false, Optional.of(5), Optional.of(2), Optional.empty(), Optional.empty(), Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ViewField(
                        "attachment",
                        "文件",
                        ViewFieldType.ATTACHMENT,
                        new ViewBinding("content", "attachment"),
                        Optional.of("must-not-exist"),
                        ViewFieldValidation.attachment(
                                false, new ViewAttachmentPolicy(java.util.Set.of("text/*"), 1024)),
                        List.of(),
                        Optional.empty(),
                        Optional.empty()));
    }

    @Test
    void extensionViewFieldTypesExcludeSecretInputs() {
        assertFalse(
                java.util.Arrays.stream(ViewFieldType.values()).map(Enum::name).anyMatch("SECRET"::equals));
    }

    @Test
    void standardViewQueryContractsPreserveCanonicalRowsAndRevision() {
        ViewQueryRequest request = new ViewQueryRequest("documents", Map.of(), "doc-1", 20, Optional.of("doc-2"));
        ViewQueryResult result = new ViewQueryResult(
                "documents",
                List.of(new CanonicalPayload("{\"id\":\"doc-2\"}")),
                new CanonicalPayload("{\"name\":\"Demo\"}"),
                "doc-2",
                true,
                7);

        assertEquals("doc-1", request.cursor());
        assertEquals(7, result.revision());
        assertTrue(result.hasMore());
        assertThrows(
                IllegalArgumentException.class,
                () -> new ViewQueryResult("documents", List.of(), new CanonicalPayload("{}"), "", true, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ViewFieldValidation(
                        false,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(BigDecimal.TEN),
                        Optional.of(BigDecimal.ONE),
                        Optional.empty()));
    }

    @Test
    void attachmentFieldRequiresBoundedDeclarativeMediaPolicy() {
        ViewAttachmentPolicy policy =
                new ViewAttachmentPolicy(java.util.Set.of("application/pdf", "text/*"), 16 * 1024 * 1024);
        ViewField attachment = new ViewField(
                "attachment",
                "文件",
                ViewFieldType.ATTACHMENT,
                new ViewBinding("sources", "attachment"),
                Optional.empty(),
                ViewFieldValidation.attachment(true, policy),
                List.of(),
                Optional.empty(),
                Optional.empty());

        assertEquals(policy, attachment.validation().attachment().orElseThrow());
        assertTrue(policy.accepts("text/markdown; charset=utf-8"));
        assertTrue(policy.accepts("application/pdf"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ViewField(
                        "attachment",
                        "文件",
                        ViewFieldType.ATTACHMENT,
                        new ViewBinding("sources", "attachment"),
                        Optional.empty(),
                        ViewFieldValidation.required(true),
                        List.of(),
                        Optional.empty(),
                        Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new ViewAttachmentPolicy(java.util.Set.of("*/*"), 1));
    }
}
