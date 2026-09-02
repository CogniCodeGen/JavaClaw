package com.javaclaw.builtin.extensions;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.builtin.contracts.SddContracts;
import com.javaclaw.extension.spi.ExpectedRevisionBinding;
import com.javaclaw.extension.spi.ExtensionContribution;
import com.javaclaw.extension.spi.ExtensionContributions;
import com.javaclaw.extension.spi.ExtensionExecutionContext;
import com.javaclaw.extension.spi.ExtensionRequest;
import com.javaclaw.extension.spi.ExtensionResponse;
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
import com.javaclaw.extension.spi.ViewSchema;
import com.javaclaw.extension.spi.ViewSelectionMode;
import com.javaclaw.extension.spi.ViewStructuredItemField;
import com.javaclaw.extension.spi.ViewStructuredItemType;
import com.javaclaw.extension.spi.ViewStructuredItemValidation;
import com.javaclaw.extension.spi.ViewStructuredListField;

/** SDD 管理中心的强类型新建、权威详情和乐观锁编辑纵切。 */
final class SddManagement {
    private static final String CREATE = "definition/create";
    private static final String UPDATE = "definition/update";
    private static final String VIEW_NEW = "definition/view.new";
    private static final String VIEW_SELECTED = "definition/view.selected";
    private static final String NEW_SOURCE = "newDefinition";
    private static final String EDIT_SOURCE = "definitionEditor";

    private final ManagedDocumentResource<SddContracts.Definition> documents;
    private final DefinitionManagementSupport<SddContracts.Definition> support;

    SddManagement(ManagedDocumentResource<SddContracts.Definition> documents) {
        this.documents = java.util.Objects.requireNonNull(documents, "documents");
        support = new DefinitionManagementSupport<>(documents);
    }

    List<ExtensionContribution> contributions() {
        return List.of(
                new ExtensionContributions.Query(
                        "definition.management.query", Set.of(VIEW_NEW, VIEW_SELECTED), this::query),
                new ExtensionContributions.Command("definition.management.command", Set.of(CREATE, UPDATE), this::save),
                new ExtensionContributions.View("definition.management.view", view()));
    }

    private ExtensionResponse query(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        return switch (request.operation()) {
            case VIEW_NEW -> support.newEditor(request, NEW_SOURCE);
            case VIEW_SELECTED -> support.selected(request, context, EDIT_SOURCE, this::editor);
            default -> throw new IllegalArgumentException("unknown SDD management query");
        };
    }

    private ExtensionResponse save(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        DefinitionManagementSupport.SaveMode mode =
                switch (request.operation()) {
                    case CREATE -> DefinitionManagementSupport.SaveMode.CREATE;
                    case UPDATE -> DefinitionManagementSupport.SaveMode.UPDATE;
                    default -> throw new IllegalArgumentException("unknown SDD management command");
                };
        return support.save(
                request,
                context,
                SddContracts.ManagementSaveRequest.class,
                SddContracts.ManagementSaveRequest::id,
                this::definition,
                mode);
    }

    private SddContracts.Definition definition(
            SddContracts.ManagementSaveRequest input, long revision, Instant updatedAt) {
        SddContracts.Content content = new SddContracts.Content(
                input.requirements(),
                input.design(),
                input.tasks().stream()
                        .map(SddContracts.ManagementTask::instruction)
                        .toList());
        SddContracts.VerificationRule rule = new SddContracts.VerificationRule(
                input.verificationKind(),
                input.toolName(),
                input.expectedExitCode(),
                input.fieldPointer(),
                expectedValue(input));
        return new SddContracts.Definition(
                input.id(), revision, input.title(), content, rule, input.maximumRemediations(), updatedAt);
    }

    private Optional<CanonicalPayload> expectedValue(SddContracts.ManagementSaveRequest input) {
        if (input.expectedValue().isEmpty()) {
            if (input.expectedValueKind().isPresent()) {
                throw new IllegalArgumentException("SDD expected value kind requires a value");
            }
            return Optional.empty();
        }
        SddContracts.ManagementValueKind kind = input.expectedValueKind()
                .orElseThrow(() -> new IllegalArgumentException("SDD expected value requires a scalar kind"));
        String value = input.expectedValue().orElseThrow();
        Object scalar =
                switch (kind) {
                    case STRING -> value;
                    case NUMBER -> number(value);
                    case BOOLEAN -> booleanValue(value);
                };
        return Optional.of(documents.payloads().encode(Map.of("value", scalar)));
    }

    private SddEditor editor(SddContracts.Definition definition) {
        ScalarValue scalar = definition
                .verificationRule()
                .expectedValue()
                .map(this::scalarValue)
                .orElse(ScalarValue.empty());
        List<SddContracts.ManagementTask> tasks = IntStream.range(
                        0, definition.content().tasks().size())
                .mapToObj(index -> new SddContracts.ManagementTask(
                        "task-" + Math.addExact(index, 1),
                        definition.content().tasks().get(index)))
                .toList();
        return new SddEditor(
                definition.id(),
                definition.title(),
                definition.content().requirements(),
                definition.content().design(),
                tasks,
                definition.verificationRule().kind(),
                definition.verificationRule().toolName(),
                definition.verificationRule().expectedExitCode(),
                definition.verificationRule().fieldPointer(),
                scalar.kind(),
                scalar.value(),
                definition.maximumRemediations(),
                definition.specificationDigest(),
                definition.taskDigest(),
                definition.revision(),
                definition.updatedAt());
    }

    private ScalarValue scalarValue(CanonicalPayload payload) {
        Map<?, ?> envelope = documents.payloads().decode(payload, Map.class);
        if (!envelope.keySet().equals(Set.of("value")) || envelope.get("value") == null) {
            throw new IllegalStateException("SDD 管理中心只编辑带类型的标量字段断言");
        }
        Object value = envelope.get("value");
        if (value instanceof String text) {
            return new ScalarValue(Optional.of(SddContracts.ManagementValueKind.STRING), Optional.of(text));
        }
        if (value instanceof Boolean flag) {
            return new ScalarValue(Optional.of(SddContracts.ManagementValueKind.BOOLEAN), Optional.of(flag.toString()));
        }
        if (value instanceof Number number) {
            return new ScalarValue(
                    Optional.of(SddContracts.ManagementValueKind.NUMBER),
                    Optional.of(new BigDecimal(number.toString())
                            .stripTrailingZeros()
                            .toPlainString()));
        }
        throw new IllegalStateException("SDD 管理中心只编辑字符串、数值或布尔字段断言");
    }

    private ViewSchema view() {
        return new ViewSchema(
                ViewSchema.CURRENT_VERSION,
                documents.extensionId().value() + ".management",
                "规格驱动开发定义",
                List.of(
                        new ViewDataSource("documents", "view.list", Map.of(), List.of(), 100),
                        new ViewDataSource(NEW_SOURCE, VIEW_NEW, Map.of(), List.of(), 1),
                        new ViewDataSource(
                                EDIT_SOURCE,
                                VIEW_SELECTED,
                                Map.of(),
                                List.of(
                                        new ViewArgumentBinding("id", "documents", "id"),
                                        new ViewArgumentBinding("revision", "documents", "revision")),
                                1)),
                List.of(
                        definitionsTable(),
                        form("sdd-create", "新建 SDD", NEW_SOURCE, true),
                        form("sdd-edit", "编辑 SDD", EDIT_SOURCE, false)));
    }

    private ViewSchema.Table definitionsTable() {
        ViewAction delete = new ViewAction(
                "删除", "delete", Map.of(), Map.of("id", "id"), new ExpectedRevisionBinding.RowField("revision"), true);
        return new ViewSchema.Table(
                "definitions",
                "SDD",
                "documents",
                "id",
                List.of(
                        new ViewSchema.Column("title", "标题", Optional.of(260)),
                        new ViewSchema.Column("revision", "版本", Optional.of(80)),
                        new ViewSchema.Column("updatedAt", "更新时间", Optional.of(180))),
                ViewSelectionMode.SINGLE,
                List.of(delete));
    }

    private ViewSchema.Form form(String id, String title, String source, boolean create) {
        ViewAction save = create
                ? new ViewAction("创建 Definition", CREATE, Map.of(), Map.of(), new ExpectedRevisionBinding.None(), false)
                : new ViewAction(
                        "保存 Definition",
                        UPDATE,
                        Map.of(),
                        Map.of(),
                        new ExpectedRevisionBinding.SourceRevision(source),
                        false,
                        new ViewCommandBinding("id", new ViewBinding(source, "id")));
        return new ViewSchema.Form(id, title, fields(source, create), save);
    }

    private List<? extends ViewFormField> fields(String source, boolean create) {
        java.util.ArrayList<ViewFormField> fields = new java.util.ArrayList<>();
        if (create) {
            fields.add(text(source, "id", "定义标识", ViewFieldType.TEXT, true));
        }
        fields.add(text(source, "title", "标题", ViewFieldType.TEXT, true));
        fields.add(text(source, "requirements", "需求与验收条件", ViewFieldType.MULTILINE, true));
        fields.add(text(source, "design", "设计说明", ViewFieldType.MULTILINE, true));
        fields.add(tasks(source));
        fields.add(verificationKind(source));
        fields.add(text(source, "toolName", "证据工具名", ViewFieldType.TEXT, true));
        fields.add(number(
                source,
                "expectedExitCode",
                "期望退出码",
                "0",
                Integer.MIN_VALUE,
                Integer.MAX_VALUE,
                visible(source, "verificationKind", "TOOL_EXIT_CODE")));
        fields.add(
                conditionalText(source, "fieldPointer", "字段 JSON Pointer", "verificationKind", "TOOL_FIELD_ASSERTION"));
        fields.add(expectedValueKind(source));
        fields.add(
                conditionalText(source, "expectedValue", "期望标量值（不是 JSON）", "verificationKind", "TOOL_FIELD_ASSERTION"));
        fields.add(number(source, "maximumRemediations", "最大修复轮数", "1", 0, 10, Optional.empty()));
        return List.copyOf(fields);
    }

    private static ViewStructuredListField tasks(String source) {
        ViewStructuredItemField instruction = new ViewStructuredItemField(
                "instruction",
                "任务正文",
                ViewStructuredItemType.MULTILINE,
                Optional.empty(),
                List.of(),
                ViewStructuredItemValidation.required(true),
                List.of());
        return new ViewStructuredListField(
                "tasks",
                "实施任务",
                new ViewBinding(source, "tasks"),
                1,
                100,
                "itemKey",
                List.of(instruction),
                List.of(Map.of("itemKey", "task-1", "instruction", "描述第一项实施任务")),
                Optional.empty());
    }

    private static ViewField verificationKind(String source) {
        return new ViewField(
                "verificationKind",
                "完成证据",
                ViewFieldType.CHOICE,
                new ViewBinding(source, "verificationKind"),
                Optional.of("TOOL_EXIT_CODE"),
                ViewFieldValidation.required(true),
                List.of(new ViewOption("TOOL_EXIT_CODE", "工具退出码"), new ViewOption("TOOL_FIELD_ASSERTION", "工具字段断言")),
                Optional.empty(),
                Optional.empty());
    }

    private static ViewField expectedValueKind(String source) {
        return new ViewField(
                "expectedValueKind",
                "期望值类型",
                ViewFieldType.CHOICE,
                new ViewBinding(source, "expectedValueKind"),
                Optional.of("STRING"),
                ViewFieldValidation.required(true),
                List.of(
                        new ViewOption("STRING", "字符串"),
                        new ViewOption("NUMBER", "数值"),
                        new ViewOption("BOOLEAN", "布尔值")),
                Optional.empty(),
                visible(source, "verificationKind", "TOOL_FIELD_ASSERTION"));
    }

    private static ViewField conditionalText(
            String source, String name, String label, String conditionField, String expected) {
        return new ViewField(
                name,
                label,
                ViewFieldType.TEXT,
                new ViewBinding(source, name),
                Optional.empty(),
                ViewFieldValidation.required(true),
                List.of(),
                Optional.empty(),
                visible(source, conditionField, expected));
    }

    private static ViewField text(String source, String name, String label, ViewFieldType type, boolean required) {
        return new ViewField(
                name,
                label,
                type,
                new ViewBinding(source, name),
                Optional.empty(),
                ViewFieldValidation.required(required),
                List.of(),
                Optional.empty(),
                Optional.empty());
    }

    private static ViewField number(
            String source,
            String name,
            String label,
            String initial,
            int minimum,
            int maximum,
            Optional<ViewCondition> condition) {
        ViewFieldValidation validation = new ViewFieldValidation(
                true,
                Optional.empty(),
                Optional.empty(),
                Optional.of(BigDecimal.valueOf(minimum)),
                Optional.of(BigDecimal.valueOf(maximum)),
                Optional.empty());
        return new ViewField(
                name,
                label,
                ViewFieldType.NUMBER,
                new ViewBinding(source, name),
                Optional.of(initial),
                validation,
                List.of(),
                Optional.empty(),
                condition);
    }

    private static Optional<ViewCondition> visible(String source, String field, String expected) {
        return Optional.of(new ViewCondition(new ViewBinding(source, field), ViewConditionOperator.EQUALS, expected));
    }

    private static BigDecimal number(String value) {
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("SDD 期望值不是有效数值", failure);
        }
    }

    private static boolean booleanValue(String value) {
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            throw new IllegalArgumentException("SDD 布尔期望值只能是 true 或 false");
        }
        return Boolean.parseBoolean(value);
    }

    private record ScalarValue(Optional<SddContracts.ManagementValueKind> kind, Optional<String> value) {
        private static ScalarValue empty() {
            return new ScalarValue(Optional.empty(), Optional.empty());
        }
    }

    private record SddEditor(
            String id,
            String title,
            String requirements,
            String design,
            List<SddContracts.ManagementTask> tasks,
            SddContracts.VerificationKind verificationKind,
            String toolName,
            Optional<Integer> expectedExitCode,
            Optional<String> fieldPointer,
            Optional<SddContracts.ManagementValueKind> expectedValueKind,
            Optional<String> expectedValue,
            int maximumRemediations,
            String specificationDigest,
            String taskDigest,
            long revision,
            Instant updatedAt) {}
}
