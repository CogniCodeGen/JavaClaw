package com.javaclaw.builtin.extensions;

import org.junit.jupiter.api.Test;

import com.javaclaw.extension.spi.ExtensionBundle;
import com.javaclaw.extension.spi.ExtensionContributions;
import com.javaclaw.extension.spi.ViewField;
import com.javaclaw.extension.spi.ViewFieldType;
import com.javaclaw.extension.spi.ViewFormField;
import com.javaclaw.extension.spi.ViewPlatformDataSource;
import com.javaclaw.extension.spi.ViewSchema;
import com.javaclaw.extension.spi.ViewStructuredItemField;
import com.javaclaw.extension.spi.ViewStructuredItemType;
import com.javaclaw.extension.spi.ViewStructuredListField;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GovernedToolViewSchemaTest {
    @Test
    void loopUsesGovernedToolAndFilteredScalarPointerChoices() throws Exception {
        ViewSchema schema = management(new LoopExtension());

        assertGovernedEvidenceFields(form(schema, "loop-create"), schema);
    }

    @Test
    void sddUsesGovernedToolAndFilteredScalarPointerChoices() throws Exception {
        ViewSchema schema = management(new SddExtension());

        assertGovernedEvidenceFields(form(schema, "sdd-create"), schema);
    }

    @Test
    void workflowToolNodesUseGovernedChoices() throws Exception {
        ViewSchema schema = management(new WorkflowExtension());
        ViewStructuredListField nodes =
                assertInstanceOf(ViewStructuredListField.class, field(form(schema, "workflow-create"), "nodes"));
        ViewStructuredItemField tool = nodes.itemFields().stream()
                .filter(item -> item.name().equals("toolName"))
                .findFirst()
                .orElseThrow();

        assertEquals(ViewStructuredItemType.CHOICE, tool.type());
        assertTrue(tool.options().isEmpty());
        assertEquals("platformTools", tool.optionSource().orElseThrow().sourceId());
        assertPlatformSource(schema, "platformTools", ViewPlatformDataSource.TOOL_CATALOG);
    }

    private static void assertGovernedEvidenceFields(ViewSchema.Form form, ViewSchema schema) {
        ViewField tool = assertInstanceOf(ViewField.class, field(form, "toolName"));
        ViewField pointer = assertInstanceOf(ViewField.class, field(form, "fieldPointer"));

        assertEquals(ViewFieldType.CHOICE, tool.type());
        assertEquals(ViewFieldType.CHOICE, pointer.type());
        assertEquals("platformTools", tool.optionSource().orElseThrow().sourceId());
        assertEquals("platformToolFields", pointer.optionSource().orElseThrow().sourceId());
        assertEquals(
                "toolName",
                pointer.optionSource().orElseThrow().filter().orElseThrow().inputField());
        assertPlatformSource(schema, "platformTools", ViewPlatformDataSource.TOOL_CATALOG);
        assertPlatformSource(schema, "platformToolFields", ViewPlatformDataSource.TOOL_OUTPUT_FIELDS);
    }

    private static void assertPlatformSource(ViewSchema schema, String id, String query) {
        assertEquals(
                query,
                schema.dataSources().stream()
                        .filter(source -> source.id().equals(id))
                        .findFirst()
                        .orElseThrow()
                        .query());
    }

    private static ViewSchema management(ExtensionBundle bundle) throws Exception {
        try (bundle) {
            return new BuiltinExtensionTestSupport()
                    .start(bundle).contributions().stream()
                            .filter(ExtensionContributions.View.class::isInstance)
                            .map(ExtensionContributions.View.class::cast)
                            .map(ExtensionContributions.View::view)
                            .filter(view -> view.viewId().endsWith(".management"))
                            .findFirst()
                            .orElseThrow();
        }
    }

    private static ViewSchema.Form form(ViewSchema schema, String id) {
        return schema.nodes().stream()
                .filter(ViewSchema.Form.class::isInstance)
                .map(ViewSchema.Form.class::cast)
                .filter(form -> form.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private static ViewFormField field(ViewSchema.Form form, String name) {
        return form.fields().stream()
                .filter(field -> field.name().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
