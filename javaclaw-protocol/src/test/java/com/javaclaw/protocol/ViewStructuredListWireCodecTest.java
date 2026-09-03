package com.javaclaw.protocol;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.extension.spi.ExpectedRevisionBinding;
import com.javaclaw.extension.spi.ViewAction;
import com.javaclaw.extension.spi.ViewBinding;
import com.javaclaw.extension.spi.ViewDataSource;
import com.javaclaw.extension.spi.ViewOption;
import com.javaclaw.extension.spi.ViewOptionFilter;
import com.javaclaw.extension.spi.ViewOptionSource;
import com.javaclaw.extension.spi.ViewSchema;
import com.javaclaw.extension.spi.ViewStructuredItemField;
import com.javaclaw.extension.spi.ViewStructuredItemType;
import com.javaclaw.extension.spi.ViewStructuredItemValidation;
import com.javaclaw.extension.spi.ViewStructuredListField;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewStructuredListWireCodecTest {
    @Test
    void roundTripsExplicitStructuredListShapeAndCanonicalRowValues() {
        ViewStructuredListField field = new ViewStructuredListField(
                "steps",
                "步骤",
                new ViewBinding("editor", "steps"),
                1,
                10,
                "stepId",
                itemFields(),
                List.of(Map.of(
                        "stepId",
                        "step-1",
                        "title",
                        "构建",
                        "weight",
                        2,
                        "enabled",
                        true,
                        "kind",
                        "turn",
                        "tags",
                        List.of("core"))),
                Optional.empty());
        ViewSchema schema = schema(field);
        ViewSchemaWireCodec codec = new ViewSchemaWireCodec(new CanonicalJson());

        CanonicalPayload payload = codec.encode(schema);
        ViewSchema decoded = codec.decode(payload);
        ViewStructuredListField decodedField = (ViewStructuredListField)
                ((ViewSchema.Form) decoded.nodes().getFirst()).fields().getFirst();

        assertEquals(schema, decoded);
        assertEquals(new BigDecimal("2"), decodedField.initialRows().getFirst().get("weight"));
        assertTrue(payload.json().contains("\"type\":\"STRUCTURED_LIST\""));
        assertTrue(payload.json().contains("\"itemKey\":\"stepId\""));
    }

    @Test
    void roundTripsFilteredDynamicChoiceWithoutFreeTextFallback() {
        ViewStructuredItemField dynamic = new ViewStructuredItemField(
                "fieldPointer",
                "输出字段",
                ViewStructuredItemType.CHOICE,
                Optional.of("/exitCode"),
                List.of(),
                ViewStructuredItemValidation.required(true),
                List.of(),
                Optional.of(new ViewOptionSource(
                        "toolFields",
                        "fieldPointer",
                        "fieldLabel",
                        Optional.of(new ViewOptionFilter("toolName", "toolName")))));
        ViewStructuredListField field = new ViewStructuredListField(
                "steps",
                "步骤",
                new ViewBinding("editor", "steps"),
                0,
                10,
                "stepId",
                List.of(dynamic),
                List.of(),
                Optional.empty());
        ViewSchema schema =
                schema(field, List.of(new ViewDataSource("toolFields", "view.tool-fields", Map.of(), List.of(), 50)));
        ViewSchemaWireCodec codec = new ViewSchemaWireCodec(new CanonicalJson());

        assertEquals(schema, codec.decode(codec.encode(schema)));
        assertTrue(codec.encode(schema).json().contains("\"sourceField\":\"toolName\""));
    }

    @Test
    void rejectsRowsBelowMinimumAndAboveMaximumDuringWireDecode() {
        ViewSchemaWireCodec codec = new ViewSchemaWireCodec(new CanonicalJson());
        List<Map<String, Object>> tooMany = IntStream.range(0, 101)
                .mapToObj(index -> Map.<String, Object>of("stepId", "step-" + index, "title", "T" + index))
                .toList();

        assertInvalid(codec, wirePayload(1, 100, List.of(), itemFields()));
        assertInvalid(codec, wirePayload(0, 100, tooMany, itemFields()));
    }

    @Test
    void rejectsUnknownFieldsNestedValuesDuplicateKeysAndExecutableItemKinds() {
        ViewSchemaWireCodec codec = new ViewSchemaWireCodec(new CanonicalJson());
        List<Map<String, Object>> unknown =
                List.of(Map.of("stepId", "step-1", "title", "构建", "controllerClass", "java.lang.Runtime"));
        List<Map<String, Object>> nested = List.of(Map.of("stepId", "step-1", "title", Map.of("script", "run()")));
        List<Map<String, Object>> duplicate =
                List.of(Map.of("stepId", "same", "title", "第一步"), Map.of("stepId", "same", "title", "第二步"));
        Map<String, Object> scriptField = Map.of(
                "initialTextList",
                List.of(),
                "initialValue",
                Optional.empty(),
                "label",
                "脚本",
                "name",
                "script",
                "options",
                List.of(),
                "type",
                "SCRIPT",
                "validation",
                ViewStructuredItemValidation.required(false));

        assertInvalid(codec, wirePayload(0, 10, unknown, itemFields()));
        assertInvalid(codec, wirePayload(0, 10, nested, itemFields()));
        assertInvalid(codec, wirePayload(0, 10, duplicate, itemFields()));
        assertInvalid(codec, wirePayload(0, 10, List.of(), List.of(scriptField)));
    }

    @Test
    void rejectsSchemaThatWouldCreateTooManyPlatformInputs() {
        List<ViewStructuredItemField> fields = IntStream.range(0, 6)
                .mapToObj(index -> textField("field" + index, "字段" + index))
                .toList();

        assertInvalid(new ViewSchemaWireCodec(new CanonicalJson()), wirePayload(0, 100, List.of(), fields));
    }

    @Test
    void rejectsDynamicChoiceWhoseWireFilterShapeIsIncomplete() {
        Map<String, Object> dynamic = Map.of(
                "name",
                "fieldPointer",
                "label",
                "输出字段",
                "type",
                "CHOICE",
                "initialValue",
                Optional.empty(),
                "initialTextList",
                List.of(),
                "validation",
                ViewStructuredItemValidation.required(true),
                "options",
                List.of(),
                "optionSource",
                Map.of("sourceId", "toolFields", "valueField", "fieldPointer", "labelField", "fieldLabel"));

        assertInvalid(new ViewSchemaWireCodec(new CanonicalJson()), wirePayload(0, 10, List.of(), List.of(dynamic)));
    }

    private static ViewSchema schema(ViewStructuredListField field) {
        return schema(field, List.of());
    }

    private static ViewSchema schema(ViewStructuredListField field, List<ViewDataSource> additionalSources) {
        ViewDataSource editor = new ViewDataSource("editor", "view.read", Map.of(), List.of(), 1);
        ViewAction save = new ViewAction(
                "保存", "steps.put", Map.of(), Map.of(), new ExpectedRevisionBinding.SourceRevision("editor"), false);
        return new ViewSchema(
                ViewSchema.CURRENT_VERSION,
                "steps.editor",
                "步骤编辑",
                java.util.stream.Stream.concat(java.util.stream.Stream.of(editor), additionalSources.stream())
                        .toList(),
                List.of(new ViewSchema.Form("editor", "编辑", List.of(field), save)));
    }

    private static CanonicalPayload wirePayload(
            int minRows, int maxRows, List<Map<String, Object>> rows, List<?> fields) {
        Map<String, Object> structured = Map.of(
                "binding",
                new ViewBinding("editor", "steps"),
                "initialRows",
                rows,
                "itemFields",
                fields,
                "itemKey",
                "stepId",
                "label",
                "步骤",
                "maxRows",
                maxRows,
                "minRows",
                minRows,
                "name",
                "steps",
                "type",
                "STRUCTURED_LIST",
                "visibleWhen",
                Optional.empty());
        Map<String, Object> form = Map.of(
                "fields", List.of(structured),
                "id", "editor",
                "submit", wireAction(),
                "title", "编辑",
                "type", "form");
        return new CanonicalJson()
                .encode(Map.of(
                        "dataSources",
                        List.of(new ViewDataSource("editor", "view.read", Map.of(), List.of(), 1)),
                        "nodes",
                        List.of(form),
                        "schemaVersion",
                        2,
                        "title",
                        "步骤编辑",
                        "viewId",
                        "steps.editor"));
    }

    private static Map<String, Object> wireAction() {
        return Map.of(
                "arguments",
                Map.of(),
                "command",
                "steps.put",
                "commandBindings",
                List.of(),
                "dangerous",
                false,
                "expectedRevision",
                Map.of("field", Optional.empty(), "sourceId", "editor", "type", "source"),
                "label",
                "保存",
                "rowArguments",
                Map.of());
    }

    private static List<ViewStructuredItemField> itemFields() {
        return List.of(
                textField("title", "标题"),
                new ViewStructuredItemField(
                        "weight",
                        "权重",
                        ViewStructuredItemType.NUMBER,
                        Optional.empty(),
                        List.of(),
                        ViewStructuredItemValidation.required(false),
                        List.of(),
                        Optional.empty()),
                new ViewStructuredItemField(
                        "enabled",
                        "启用",
                        ViewStructuredItemType.BOOLEAN,
                        Optional.of("false"),
                        List.of(),
                        ViewStructuredItemValidation.required(false),
                        List.of(),
                        Optional.empty()),
                new ViewStructuredItemField(
                        "kind",
                        "类型",
                        ViewStructuredItemType.CHOICE,
                        Optional.of("turn"),
                        List.of(),
                        ViewStructuredItemValidation.required(true),
                        List.of(new ViewOption("turn", "Turn"), new ViewOption("tool", "Tool")),
                        Optional.empty()),
                new ViewStructuredItemField(
                        "tags",
                        "标签",
                        ViewStructuredItemType.TEXT_LIST,
                        Optional.empty(),
                        List.of(),
                        ViewStructuredItemValidation.required(false),
                        List.of(),
                        Optional.empty()));
    }

    private static ViewStructuredItemField textField(String name, String label) {
        return new ViewStructuredItemField(
                name,
                label,
                ViewStructuredItemType.TEXT,
                Optional.empty(),
                List.of(),
                ViewStructuredItemValidation.required(true),
                List.of(),
                Optional.empty());
    }

    private static void assertInvalid(ViewSchemaWireCodec codec, CanonicalPayload payload) {
        ProtocolException failure = assertThrows(ProtocolException.class, () -> codec.decode(payload));
        assertEquals(ProtocolErrorCode.INVALID_PARAMS, failure.code());
    }
}
