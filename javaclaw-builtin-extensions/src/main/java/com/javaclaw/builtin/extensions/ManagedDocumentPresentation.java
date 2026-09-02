package com.javaclaw.builtin.extensions;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ToolDescriptor;
import com.javaclaw.api.ToolIdentity;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.extension.spi.ExpectedRevisionBinding;
import com.javaclaw.extension.spi.ExtensionDescriptor;
import com.javaclaw.extension.spi.ExtensionPayloadCodec;
import com.javaclaw.extension.spi.ExtensionSchema;
import com.javaclaw.extension.spi.ViewAction;
import com.javaclaw.extension.spi.ViewDataSource;
import com.javaclaw.extension.spi.ViewSchema;
import com.javaclaw.extension.spi.ViewSelectionMode;

/** 生成托管文档的稳定 View、Tool 与 JSON Schema，不接触存储和调用上下文。 */
final class ManagedDocumentPresentation {
    private final ExtensionDescriptor descriptor;
    private final ViewSchema view;

    ManagedDocumentPresentation(ExtensionDescriptor descriptor) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        view = new ViewSchema(
                ViewSchema.CURRENT_VERSION,
                descriptor.id().value() + ".documents",
                descriptor.displayName(),
                List.of(new ViewDataSource("documents", "view.list", Map.of(), List.of(), 100)),
                List.of(new ViewSchema.Table(
                        "documents",
                        descriptor.displayName(),
                        "documents",
                        "id",
                        List.of(
                                new ViewSchema.Column("id", "标识", Optional.of(240)),
                                new ViewSchema.Column("revision", "版本", Optional.of(90))),
                        ViewSelectionMode.SINGLE,
                        List.of(deleteAction()))));
    }

    ViewSchema view() {
        return view;
    }

    private ViewAction deleteAction() {
        return new ViewAction(
                "删除", "delete", Map.of(), Map.of("id", "id"), new ExpectedRevisionBinding.RowField("revision"), true);
    }

    ExtensionSchema documentSchema(ExtensionPayloadCodec payloads) {
        CanonicalPayload schema = payloads.encode(Map.of(
                "$schema",
                "https://json-schema.org/draft/2020-12/schema",
                "$id",
                descriptor.id().value() + "/document/v1",
                "title",
                descriptor.displayName() + " document",
                "type",
                "object"));
        return new ExtensionSchema(descriptor.id().value() + "/document/v1", schema);
    }

    ToolDescriptor readOnlyTool(
            ExtensionPayloadCodec payloads,
            String operation,
            String description,
            CanonicalPayload inputSchema,
            Set<String> additionalTags) {
        return governedTool(payloads, operation, description, inputSchema, ToolRisk.READ_ONLY, additionalTags);
    }

    ToolDescriptor governedTool(
            ExtensionPayloadCodec payloads,
            String operation,
            String description,
            CanonicalPayload inputSchema,
            ToolRisk risk,
            Set<String> additionalTags) {
        String id = descriptor.id().value();
        String shortName = id.substring(id.lastIndexOf('.') + 1);
        Set<String> tags = new HashSet<>(Set.of(shortName, operation, descriptor.displayName()));
        tags.addAll(Set.copyOf(additionalTags));
        return new ToolDescriptor(
                new ToolIdentity(id, shortName + "_" + operation, descriptor.revision()),
                description,
                inputSchema,
                objectSchema(payloads),
                Objects.requireNonNull(risk, "risk"),
                Set.copyOf(tags));
    }

    CanonicalPayload keySchema(ExtensionPayloadCodec payloads) {
        return payloads.encode(Map.of(
                "additionalProperties",
                false,
                "properties",
                Map.of("id", Map.of("minLength", 1, "type", "string")),
                "required",
                List.of("id"),
                "type",
                "object"));
    }

    CanonicalPayload pageSchema(ExtensionPayloadCodec payloads) {
        return payloads.encode(Map.of(
                "additionalProperties",
                false,
                "properties",
                Map.of(
                        "afterKey", Map.of("type", "string"),
                        "limit", Map.of("maximum", 500, "minimum", 1, "type", "integer")),
                "required",
                List.of("afterKey", "limit"),
                "type",
                "object"));
    }

    private static CanonicalPayload objectSchema(ExtensionPayloadCodec payloads) {
        return payloads.encode(Map.of("type", "object"));
    }
}
