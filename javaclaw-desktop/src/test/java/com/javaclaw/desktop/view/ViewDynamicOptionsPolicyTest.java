package com.javaclaw.desktop.view;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.javaclaw.extension.spi.ExpectedRevisionBinding;
import com.javaclaw.extension.spi.ViewAction;
import com.javaclaw.extension.spi.ViewBinding;
import com.javaclaw.extension.spi.ViewDataSource;
import com.javaclaw.extension.spi.ViewField;
import com.javaclaw.extension.spi.ViewFieldType;
import com.javaclaw.extension.spi.ViewFieldValidation;
import com.javaclaw.extension.spi.ViewOptionFilter;
import com.javaclaw.extension.spi.ViewOptionSource;
import com.javaclaw.extension.spi.ViewPlatformDataSource;
import com.javaclaw.extension.spi.ViewSchema;
import com.javaclaw.extension.spi.ViewStructuredItemField;
import com.javaclaw.extension.spi.ViewStructuredItemType;
import com.javaclaw.extension.spi.ViewStructuredItemValidation;
import com.javaclaw.extension.spi.ViewStructuredListField;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ViewDynamicOptionsPolicyTest {
    @Test
    void acceptsKnownPlatformSourcesAndSameFormScalarFilter() {
        ViewSchema schema = formSchema(
                List.of(toolChoice(), pointerChoice("toolName")),
                List.of(
                        platform("tools", ViewPlatformDataSource.TOOL_CATALOG),
                        platform("fields", ViewPlatformDataSource.TOOL_OUTPUT_FIELDS)));

        assertEquals(schema, ViewSchemaPolicy.requireSupported(schema));
    }

    @Test
    void rejectsUnknownOrParameterizedPlatformSource() {
        ViewDataSource unknown = platform("tools", "platform/unknown");
        ViewDataSource parameterized = new ViewDataSource(
                "tools", ViewPlatformDataSource.TOOL_CATALOG, Map.of("scope", "all"), List.of(), 100);

        assertThrows(
                IllegalArgumentException.class,
                () -> ViewSchemaPolicy.requireSupported(formSchema(List.of(toolChoice()), List.of(unknown))));
        assertThrows(
                IllegalArgumentException.class,
                () -> ViewSchemaPolicy.requireSupported(formSchema(List.of(toolChoice()), List.of(parameterized))));
    }

    @Test
    void rejectsFilterThatDoesNotReferenceSameFormScalar() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ViewSchemaPolicy.requireSupported(formSchema(
                        List.of(pointerChoice("missing")),
                        List.of(platform("fields", ViewPlatformDataSource.TOOL_OUTPUT_FIELDS)))));
        assertThrows(
                IllegalArgumentException.class,
                () -> ViewSchemaPolicy.requireSupported(formSchema(
                        List.of(structuredRows(), pointerChoice("rows")),
                        List.of(platform("fields", ViewPlatformDataSource.TOOL_OUTPUT_FIELDS)))));
    }

    private static ViewField toolChoice() {
        return choice("toolName", new ViewOptionSource("tools", "toolName", "toolLabel", Optional.empty()));
    }

    private static ViewField pointerChoice(String dependency) {
        return choice(
                "fieldPointer",
                new ViewOptionSource(
                        "fields",
                        "fieldPointer",
                        "fieldLabel",
                        Optional.of(new ViewOptionFilter("toolName", dependency))));
    }

    private static ViewField choice(String name, ViewOptionSource source) {
        return new ViewField(
                name,
                name,
                ViewFieldType.CHOICE,
                new ViewBinding("editor", name),
                Optional.empty(),
                ViewFieldValidation.required(true),
                List.of(),
                Optional.of(source),
                Optional.empty());
    }

    private static ViewStructuredListField structuredRows() {
        ViewStructuredItemField text = new ViewStructuredItemField(
                "value",
                "值",
                ViewStructuredItemType.TEXT,
                Optional.empty(),
                List.of(),
                ViewStructuredItemValidation.required(false),
                List.of(),
                Optional.empty());
        return new ViewStructuredListField(
                "rows", "行", new ViewBinding("editor", "rows"), 0, 1, "id", List.of(text), List.of(), Optional.empty());
    }

    private static ViewDataSource platform(String id, String query) {
        return new ViewDataSource(id, query, Map.of(), List.of(), 100);
    }

    private static ViewSchema formSchema(
            List<? extends com.javaclaw.extension.spi.ViewFormField> fields, List<ViewDataSource> additionalSources) {
        ViewDataSource editor = new ViewDataSource("editor", "view.editor", Map.of(), List.of(), 1);
        ViewAction save = new ViewAction(
                "保存",
                "definition/save",
                Map.of(),
                Map.of(),
                new ExpectedRevisionBinding.SourceRevision("editor"),
                false);
        return new ViewSchema(
                ViewSchema.CURRENT_VERSION,
                "dynamic-options",
                "动态选项",
                java.util.stream.Stream.concat(java.util.stream.Stream.of(editor), additionalSources.stream())
                        .toList(),
                List.of(new ViewSchema.Form("form", "编辑", fields, save)));
    }
}
