package com.javaclaw.protocol;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.extension.spi.ViewField;
import com.javaclaw.extension.spi.ViewFieldType;
import com.javaclaw.extension.spi.ViewFormField;
import com.javaclaw.extension.spi.ViewStructuredItemField;
import com.javaclaw.extension.spi.ViewStructuredListField;

/** ViewSchema v2 表单字段的显式判别 codec 和协议规模校验。 */
final class ViewFormFieldWireCodec {
    private static final String STRUCTURED_LIST = "STRUCTURED_LIST";
    private static final int MAX_FORM_FIELDS = 32;
    private static final Pattern FIELD = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");
    private static final Set<String> SCALAR_TYPES = java.util.Arrays.stream(ViewFieldType.values())
            .map(Enum::name)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    private final CanonicalJson json;

    ViewFormFieldWireCodec(CanonicalJson json) {
        this.json = Objects.requireNonNull(json, "json");
    }

    CanonicalPayload encode(ViewFormField field) {
        validate(field);
        return switch (field) {
            case ViewField scalar -> json.encode(scalar);
            case ViewStructuredListField structured ->
                json.encode(new WireStructuredList(
                        STRUCTURED_LIST,
                        structured.name(),
                        structured.label(),
                        structured.binding(),
                        structured.minRows(),
                        structured.maxRows(),
                        structured.itemKey(),
                        structured.itemFields(),
                        structured.initialRows(),
                        structured.visibleWhen()));
        };
    }

    ViewFormField decode(CanonicalPayload payload) {
        try {
            String type =
                    json.textField(payload, "type").orElseThrow(() -> invalid("view form field type is required"));
            ViewFormField field;
            if (STRUCTURED_LIST.equals(type)) {
                field = structured(json.decode(payload, WireStructuredList.class));
            } else if (SCALAR_TYPES.contains(type)) {
                field = json.decode(payload, ViewField.class);
            } else {
                throw invalid("unknown view form field type: " + type);
            }
            validate(field);
            return field;
        } catch (ProtocolException failure) {
            throw failure;
        } catch (IllegalArgumentException failure) {
            throw invalid("view form field violates the bounded contract");
        }
    }

    List<CanonicalPayload> encodeAll(List<? extends ViewFormField> fields) {
        requireFieldCount(fields.size());
        return fields.stream().map(this::encode).toList();
    }

    List<ViewFormField> decodeAll(List<CanonicalPayload> fields) {
        requireFieldCount(fields.size());
        return fields.stream().map(this::decode).toList();
    }

    private static ViewStructuredListField structured(WireStructuredList wire) {
        return new ViewStructuredListField(
                wire.name(),
                wire.label(),
                wire.binding(),
                wire.minRows(),
                wire.maxRows(),
                wire.itemKey(),
                wire.itemFields(),
                wire.initialRows(),
                wire.visibleWhen());
    }

    private static void validate(ViewFormField field) {
        requireField(field.name());
        requireText(field.label());
        switch (field) {
            case ViewField scalar -> validateScalar(scalar);
            case ViewStructuredListField structured -> validateStructured(structured);
        }
    }

    private static void validateScalar(ViewField field) {
        field.initialValue().ifPresent(ViewFormFieldWireCodec::requireText);
        if (field.options().size() > ViewStructuredListField.MAX_OPTIONS) {
            throw invalid("view field contains too many options");
        }
        field.options().forEach(option -> {
            requireText(option.value());
            requireText(option.label());
        });
    }

    private static void validateStructured(ViewStructuredListField field) {
        requireField(field.itemKey());
        if (field.minRows() < 0
                || field.maxRows() > ViewStructuredListField.MAX_ROWS
                || field.minRows() > field.maxRows()
                || field.initialRows().size() > field.maxRows()
                || field.itemFields().isEmpty()
                || field.itemFields().size() > ViewStructuredListField.MAX_ITEM_FIELDS
                || (long) field.itemFields().size() * field.maxRows() > ViewStructuredListField.MAX_RENDERED_INPUTS) {
            throw invalid("structured list bounds are invalid");
        }
        for (ViewStructuredItemField item : field.itemFields()) {
            requireField(item.name());
            requireText(item.label());
            item.initialValue().ifPresent(ViewFormFieldWireCodec::requireText);
            item.initialTextList().forEach(ViewFormFieldWireCodec::requireText);
        }
        field.normalizeRows(field.initialRows());
    }

    private static void requireFieldCount(int size) {
        if (size < 1 || size > MAX_FORM_FIELDS) {
            throw invalid("view form fields must be a bounded non-empty array");
        }
    }

    private static void requireField(String value) {
        if (!FIELD.matcher(value).matches()) {
            throw invalid("view form field name is unsafe");
        }
    }

    private static void requireText(String value) {
        if (value.length() > ViewStructuredListField.MAX_TEXT_LENGTH) {
            throw invalid("view form field text is too long");
        }
    }

    private static ProtocolException invalid(String message) {
        return new ProtocolException(ProtocolErrorCode.INVALID_PARAMS, message);
    }

    private record WireStructuredList(
            String type,
            String name,
            String label,
            com.javaclaw.extension.spi.ViewBinding binding,
            int minRows,
            int maxRows,
            String itemKey,
            List<ViewStructuredItemField> itemFields,
            List<Map<String, Object>> initialRows,
            Optional<com.javaclaw.extension.spi.ViewCondition> visibleWhen) {}
}
