package com.javaclaw.builtin.extensions;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.builtin.contracts.LoopContracts;
import com.javaclaw.extension.spi.ExpectedRevisionBinding;
import com.javaclaw.extension.spi.ExtensionContribution;
import com.javaclaw.extension.spi.ExtensionContributions;
import com.javaclaw.extension.spi.ExtensionExecutionContext;
import com.javaclaw.extension.spi.ExtensionRequest;
import com.javaclaw.extension.spi.ExtensionResponse;
import com.javaclaw.extension.spi.ExtensionTransaction;
import com.javaclaw.extension.spi.VersionedDocument;
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
import com.javaclaw.extension.spi.ViewOption;
import com.javaclaw.extension.spi.ViewOptionFilter;
import com.javaclaw.extension.spi.ViewOptionSource;
import com.javaclaw.extension.spi.ViewPlatformDataSource;
import com.javaclaw.extension.spi.ViewQueryRequest;
import com.javaclaw.extension.spi.ViewQueryResult;
import com.javaclaw.extension.spi.ViewSchema;
import com.javaclaw.extension.spi.ViewSelectionMode;

/** Loop 管理中心的强类型 Definition 编辑；revision 和时间始终由服务端生成。 */
final class LoopManagement {
    private static final String TOOLS_SOURCE = "platformTools";
    private static final String TOOL_FIELDS_SOURCE = "platformToolFields";
    private static final String SAVE = "definition/save";
    private static final String VIEW_NEW = "definition/view.new";
    private static final String VIEW_SELECTED = "definition/view.selected";

    private final ManagedDocumentResource<LoopContracts.Definition> documents;

    LoopManagement(ManagedDocumentResource<LoopContracts.Definition> documents) {
        this.documents = java.util.Objects.requireNonNull(documents, "documents");
    }

    List<ExtensionContribution> contributions() {
        return List.of(
                new ExtensionContributions.Query(
                        "definition.management.query", Set.of(VIEW_NEW, VIEW_SELECTED), this::query),
                new ExtensionContributions.Command("definition.management.command", Set.of(SAVE), this::save),
                new ExtensionContributions.View("definition.management.view", view()));
    }

    private ExtensionResponse query(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        return switch (request.operation()) {
            case VIEW_NEW -> newEditor(request);
            case VIEW_SELECTED -> selectedEditor(request, context);
            default -> throw new IllegalArgumentException("unknown Loop management query");
        };
    }

    private ExtensionResponse newEditor(ExtensionRequest request) {
        ViewQueryRequest query = documents.payloads().decode(request.payload(), ViewQueryRequest.class);
        if (!"newDefinition".equals(query.dataSourceId())
                || !query.arguments().isEmpty()
                || !query.cursor().isEmpty()) {
            throw new IllegalArgumentException("Loop new Definition view does not accept arguments");
        }
        ViewQueryResult result = new ViewQueryResult(
                query.dataSourceId(), List.of(), documents.payloads().encode(Map.of()), "", false, 0);
        return new ExtensionResponse(documents.payloads().encode(result), 0);
    }

    private ExtensionResponse selectedEditor(ExtensionRequest request, ExtensionExecutionContext context)
            throws Exception {
        ViewQueryRequest query = documents.payloads().decode(request.payload(), ViewQueryRequest.class);
        if (!"definitionEditor".equals(query.dataSourceId())
                || !query.arguments().keySet().equals(Set.of("id", "revision"))
                || !query.cursor().isEmpty()) {
            throw new IllegalArgumentException("Loop editor requires one exact selected Definition");
        }
        long selectedRevision = parseRevision(query.arguments().get("revision"));
        LoopContracts.Definition definition = context.managedStore()
                .inTransaction(
                        documents.extensionId(),
                        transaction -> requireDefinition(
                                transaction, request, query.arguments().get("id"), selectedRevision));
        LoopDefinitionEditor editor = editor(definition);
        ViewQueryResult result = new ViewQueryResult(
                query.dataSourceId(), List.of(), documents.payloads().encode(editor), "", false, definition.revision());
        return new ExtensionResponse(documents.payloads().encode(result), definition.revision());
    }

    private ExtensionResponse save(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        String key = request.idempotencyKey()
                .orElseThrow(() -> new IllegalArgumentException("Loop Definition save requires idempotency key"));
        CanonicalPayload digest = documents
                .payloads()
                .encode(new CommandDigest(request.operation(), request.expectedRevision(), request.payload()));
        return context.managedStore()
                .inCommand(
                        documents.extensionId(),
                        request.operation(),
                        key,
                        digest.sha256(),
                        transaction -> save(request, context, transaction));
    }

    private ExtensionResponse save(
            ExtensionRequest request, ExtensionExecutionContext context, ExtensionTransaction transaction) {
        LoopContracts.ManagementSaveRequest input =
                documents.payloads().decode(request.payload(), LoopContracts.ManagementSaveRequest.class);
        requireExpectedRevision(transaction, request, input.id());
        LoopContracts.Definition definition = new LoopContracts.Definition(
                input.id(),
                Math.addExact(request.expectedRevision(), 1),
                input.name(),
                input.objective(),
                input.instruction(),
                input.maximumIterations(),
                input.noProgressThreshold(),
                verificationRule(input),
                context.clock().instant());
        transaction.put(
                documents.documentCollection(request.workspaceId()),
                definition.id(),
                request.expectedRevision(),
                documents.payloads().encode(definition));
        return new ExtensionResponse(documents.payloads().encode(definition), definition.revision());
    }

    private LoopContracts.VerificationRule verificationRule(LoopContracts.ManagementSaveRequest input) {
        Optional<CanonicalPayload> expected = input.expectedValue()
                .map(value -> scalar(input.expectedValueKind().orElseThrow(), value));
        return new LoopContracts.VerificationRule(
                input.verificationKind(), input.toolName(), input.expectedExitCode(), input.fieldPointer(), expected);
    }

    private CanonicalPayload scalar(LoopContracts.ManagementValueKind kind, String value) {
        Object scalar =
                switch (kind) {
                    case STRING -> value;
                    case NUMBER -> number(value);
                    case BOOLEAN -> booleanValue(value);
                };
        return documents.payloads().encode(Map.of("value", scalar));
    }

    private LoopDefinitionEditor editor(LoopContracts.Definition definition) {
        ScalarValue scalar = definition
                .verificationRule()
                .expectedValue()
                .map(this::scalarValue)
                .orElse(ScalarValue.empty());
        return new LoopDefinitionEditor(
                definition.id(),
                definition.name(),
                definition.objective(),
                definition.instruction(),
                definition.maximumIterations(),
                definition.noProgressThreshold(),
                definition.verificationRule().kind(),
                definition.verificationRule().toolName(),
                definition.verificationRule().expectedExitCode(),
                definition.verificationRule().fieldPointer(),
                scalar.kind(),
                scalar.value(),
                definition.revision(),
                definition.updatedAt());
    }

    private ScalarValue scalarValue(CanonicalPayload payload) {
        Map<?, ?> envelope = documents.payloads().decode(payload, Map.class);
        if (!envelope.keySet().equals(Set.of("value")) || envelope.get("value") == null) {
            throw new IllegalStateException("Loop 管理中心只编辑带类型的标量字段断言");
        }
        Object value = envelope.get("value");
        if (value instanceof String text) {
            return new ScalarValue(Optional.of(LoopContracts.ManagementValueKind.STRING), Optional.of(text));
        }
        if (value instanceof Boolean flag) {
            return new ScalarValue(
                    Optional.of(LoopContracts.ManagementValueKind.BOOLEAN), Optional.of(flag.toString()));
        }
        if (value instanceof Number number) {
            return new ScalarValue(
                    Optional.of(LoopContracts.ManagementValueKind.NUMBER),
                    Optional.of(new BigDecimal(number.toString())
                            .stripTrailingZeros()
                            .toPlainString()));
        }
        throw new IllegalStateException("Loop 管理中心只编辑字符串、数值或布尔字段断言");
    }

    private LoopContracts.Definition requireDefinition(
            ExtensionTransaction transaction, ExtensionRequest request, String id, long selectedRevision) {
        VersionedDocument document = transaction
                .get(documents.documentCollection(request.workspaceId()), id)
                .orElseThrow(() -> new IllegalArgumentException("Loop Definition does not exist"));
        LoopContracts.Definition definition =
                documents.payloads().decode(document.payload(), LoopContracts.Definition.class);
        if (definition.revision() != document.revision() || document.revision() != selectedRevision) {
            if (document.revision() != selectedRevision) {
                throw new IllegalArgumentException("Loop Definition selection is stale");
            }
            throw new IllegalStateException("Loop Definition payload revision differs from managed store");
        }
        return definition;
    }

    private static long parseRevision(String value) {
        try {
            long revision = Long.parseLong(value);
            if (revision < 1) {
                throw new IllegalArgumentException("Loop Definition revision must be positive");
            }
            return revision;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Loop Definition revision is invalid", failure);
        }
    }

    private void requireExpectedRevision(
            ExtensionTransaction transaction, ExtensionRequest request, String definitionId) {
        Optional<VersionedDocument> current =
                transaction.get(documents.documentCollection(request.workspaceId()), definitionId);
        long actual = current.map(VersionedDocument::revision).orElse(0L);
        if (actual != request.expectedRevision()) {
            throw new IllegalArgumentException("Loop Definition revision changed");
        }
    }

    private ViewSchema view() {
        return new ViewSchema(
                ViewSchema.CURRENT_VERSION,
                documents.extensionId().value() + ".management",
                "循环定义",
                List.of(
                        new ViewDataSource("documents", "view.list", Map.of(), List.of(), 100),
                        new ViewDataSource(TOOLS_SOURCE, ViewPlatformDataSource.TOOL_CATALOG, Map.of(), List.of(), 100),
                        new ViewDataSource(
                                TOOL_FIELDS_SOURCE,
                                ViewPlatformDataSource.TOOL_OUTPUT_FIELDS,
                                Map.of(),
                                List.of(),
                                200),
                        new ViewDataSource("newDefinition", VIEW_NEW, Map.of(), List.of(), 1),
                        new ViewDataSource(
                                "definitionEditor",
                                VIEW_SELECTED,
                                Map.of(),
                                List.of(
                                        new ViewArgumentBinding("id", "documents", "id"),
                                        new ViewArgumentBinding("revision", "documents", "revision")),
                                1)),
                List.of(
                        definitionsTable(),
                        form("loop-create", "新建 Loop", "newDefinition", true),
                        form("loop-edit", "编辑所选 Loop", "definitionEditor", false)));
    }

    private ViewSchema.Table definitionsTable() {
        ViewAction delete = new ViewAction(
                "删除", "delete", Map.of(), Map.of("id", "id"), new ExpectedRevisionBinding.RowField("revision"), true);
        return new ViewSchema.Table(
                "definitions",
                "Loop",
                "documents",
                "id",
                List.of(
                        new ViewSchema.Column("name", "名称", Optional.of(220)),
                        new ViewSchema.Column("maximumIterations", "最大轮次", Optional.of(100)),
                        new ViewSchema.Column("revision", "版本", Optional.of(80))),
                ViewSelectionMode.SINGLE,
                List.of(delete));
    }

    private ViewSchema.Form form(String id, String title, String source, boolean create) {
        ViewAction save = create
                ? new ViewAction("保存 Definition", SAVE, Map.of(), Map.of(), new ExpectedRevisionBinding.None(), false)
                : new ViewAction(
                        "保存 Definition",
                        SAVE,
                        Map.of(),
                        Map.of(),
                        new ExpectedRevisionBinding.SourceRevision(source),
                        false,
                        new ViewCommandBinding("id", new ViewBinding(source, "id")));
        return new ViewSchema.Form(id, title, fields(source, create), save);
    }

    private List<ViewField> fields(String source, boolean create) {
        List<ViewField> fields = new ArrayList<>();
        if (create) {
            fields.add(text(source, "id", "定义标识", ViewFieldType.TEXT, true));
        }
        fields.addAll(List.of(
                text(source, "name", "名称", ViewFieldType.TEXT, true),
                text(source, "objective", "目标", ViewFieldType.MULTILINE, true),
                text(source, "instruction", "每轮指令", ViewFieldType.MULTILINE, true),
                number(source, "maximumIterations", "最大迭代数", "10", 1, 100, Optional.empty()),
                number(source, "noProgressThreshold", "无进展阈值", "3", 1, 100, Optional.empty()),
                verificationKind(source),
                toolChoice(source),
                number(
                        source,
                        "expectedExitCode",
                        "期望退出码",
                        "0",
                        Integer.MIN_VALUE,
                        Integer.MAX_VALUE,
                        visible(source, "verificationKind", "TOOL_EXIT_CODE")),
                pointerChoice(source),
                expectedValueKind(source),
                conditionalText(
                        source, "expectedValue", "期望标量值（不是 JSON）", "verificationKind", "TOOL_FIELD_ASSERTION")));
        return List.copyOf(fields);
    }

    private ViewField toolChoice(String source) {
        return new ViewField(
                "toolName",
                "证据工具",
                ViewFieldType.CHOICE,
                new ViewBinding(source, "toolName"),
                Optional.empty(),
                ViewFieldValidation.required(true),
                List.of(),
                Optional.of(new ViewOptionSource(
                        TOOLS_SOURCE,
                        ViewPlatformDataSource.TOOL_NAME_FIELD,
                        ViewPlatformDataSource.TOOL_LABEL_FIELD,
                        Optional.empty())),
                Optional.of(new ViewCondition(
                        new ViewBinding(source, "verificationKind"),
                        ViewConditionOperator.NOT_EQUALS,
                        "USER_CONFIRMATION")));
    }

    private ViewField pointerChoice(String source) {
        return new ViewField(
                "fieldPointer",
                "工具输出标量字段",
                ViewFieldType.CHOICE,
                new ViewBinding(source, "fieldPointer"),
                Optional.empty(),
                ViewFieldValidation.required(true),
                List.of(),
                Optional.of(new ViewOptionSource(
                        TOOL_FIELDS_SOURCE,
                        ViewPlatformDataSource.POINTER_FIELD,
                        ViewPlatformDataSource.POINTER_LABEL_FIELD,
                        Optional.of(new ViewOptionFilter(ViewPlatformDataSource.TOOL_NAME_FIELD, "toolName")))),
                visible(source, "verificationKind", "TOOL_FIELD_ASSERTION"));
    }

    private ViewField verificationKind(String source) {
        return new ViewField(
                "verificationKind",
                "完成证据",
                ViewFieldType.CHOICE,
                new ViewBinding(source, "verificationKind"),
                Optional.of("USER_CONFIRMATION"),
                ViewFieldValidation.required(true),
                List.of(
                        new ViewOption("USER_CONFIRMATION", "用户显式确认"),
                        new ViewOption("TOOL_EXIT_CODE", "工具退出码"),
                        new ViewOption("TOOL_FIELD_ASSERTION", "工具字段断言")),
                Optional.empty(),
                Optional.empty());
    }

    private ViewField expectedValueKind(String source) {
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

    private ViewField conditionalText(
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

    private ViewField text(String source, String name, String label, ViewFieldType type, boolean required) {
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

    private ViewField number(
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
            throw new IllegalArgumentException("Loop 期望值不是有效数值", failure);
        }
    }

    private static boolean booleanValue(String value) {
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            throw new IllegalArgumentException("Loop 布尔期望值只能是 true 或 false");
        }
        return Boolean.parseBoolean(value);
    }

    private record CommandDigest(String operation, long expectedRevision, CanonicalPayload payload) {}

    private record ScalarValue(Optional<LoopContracts.ManagementValueKind> kind, Optional<String> value) {
        private static ScalarValue empty() {
            return new ScalarValue(Optional.empty(), Optional.empty());
        }
    }

    private record LoopDefinitionEditor(
            String id,
            String name,
            String objective,
            String instruction,
            int maximumIterations,
            int noProgressThreshold,
            LoopContracts.VerificationKind verificationKind,
            Optional<String> toolName,
            Optional<Integer> expectedExitCode,
            Optional<String> fieldPointer,
            Optional<LoopContracts.ManagementValueKind> expectedValueKind,
            Optional<String> expectedValue,
            long revision,
            Instant updatedAt) {}
}
