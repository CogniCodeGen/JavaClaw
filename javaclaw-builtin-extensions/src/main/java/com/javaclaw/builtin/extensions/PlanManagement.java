package com.javaclaw.builtin.extensions;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

import com.javaclaw.builtin.contracts.ContractDigests;
import com.javaclaw.builtin.contracts.PlanContracts;
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
import com.javaclaw.extension.spi.ViewDataSource;
import com.javaclaw.extension.spi.ViewField;
import com.javaclaw.extension.spi.ViewFieldType;
import com.javaclaw.extension.spi.ViewFieldValidation;
import com.javaclaw.extension.spi.ViewFormField;
import com.javaclaw.extension.spi.ViewSchema;
import com.javaclaw.extension.spi.ViewSelectionMode;
import com.javaclaw.extension.spi.ViewStructuredItemField;
import com.javaclaw.extension.spi.ViewStructuredItemType;
import com.javaclaw.extension.spi.ViewStructuredItemValidation;
import com.javaclaw.extension.spi.ViewStructuredListField;

/** Plan 管理中心的强类型新建、权威详情和乐观锁编辑纵切。 */
final class PlanManagement {
    private static final String CREATE = "definition/create";
    private static final String UPDATE = "definition/update";
    private static final String VIEW_NEW = "definition/view.new";
    private static final String VIEW_SELECTED = "definition/view.selected";
    private static final String NEW_SOURCE = "newDefinition";
    private static final String EDIT_SOURCE = "definitionEditor";

    private final ManagedDocumentResource<PlanContracts.Definition> documents;
    private final DefinitionManagementSupport<PlanContracts.Definition> support;

    PlanManagement(ManagedDocumentResource<PlanContracts.Definition> documents) {
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
            default -> throw new IllegalArgumentException("unknown Plan management query");
        };
    }

    private ExtensionResponse save(ExtensionRequest request, ExtensionExecutionContext context) throws Exception {
        DefinitionManagementSupport.SaveMode mode =
                switch (request.operation()) {
                    case CREATE -> DefinitionManagementSupport.SaveMode.CREATE;
                    case UPDATE -> DefinitionManagementSupport.SaveMode.UPDATE;
                    default -> throw new IllegalArgumentException("unknown Plan management command");
                };
        return support.save(
                request,
                context,
                PlanContracts.ManagementSaveRequest.class,
                PlanContracts.ManagementSaveRequest::id,
                this::definition,
                mode);
    }

    private PlanContracts.Definition definition(
            PlanContracts.ManagementSaveRequest input, long revision, Instant updatedAt) {
        return definition(candidate(input), revision, updatedAt);
    }

    static PlanContracts.Candidate candidate(PlanContracts.ManagementSaveRequest input) {
        List<PlanContracts.OpenQuestion> questions =
                input.openQuestions().stream().map(PlanManagement::question).toList();
        List<PlanContracts.Step> steps = input.steps().stream()
                .map(step -> new PlanContracts.Step(
                        step.id(), step.title(), step.instruction(), step.acceptanceCriteria(), step.dependencies()))
                .toList();
        return new PlanContracts.Candidate(
                input.id(),
                input.title(),
                input.objective(),
                input.scope(),
                input.risks().stream()
                        .map(PlanContracts.ManagementRisk::description)
                        .toList(),
                questions,
                steps);
    }

    static PlanContracts.Definition definition(PlanContracts.Candidate candidate, long revision, Instant updatedAt) {
        return new PlanContracts.Definition(
                candidate.id(),
                revision,
                candidate.title(),
                candidate.objective(),
                candidate.scope(),
                candidate.risks(),
                candidate.openQuestions(),
                candidate.steps(),
                updatedAt);
    }

    private static PlanContracts.OpenQuestion question(PlanContracts.ManagementOpenQuestion input) {
        String digest = ContractDigests.sha256(input.prompt());
        Optional<PlanContracts.Decision> decision =
                input.decision().map(answer -> new PlanContracts.Decision(digest, answer));
        return new PlanContracts.OpenQuestion(input.id(), input.prompt(), digest, decision);
    }

    private PlanEditor editor(PlanContracts.Definition definition) {
        List<PlanContracts.ManagementRisk> risks = IntStream.range(
                        0, definition.risks().size())
                .mapToObj(index -> new PlanContracts.ManagementRisk(
                        "risk-" + Math.addExact(index, 1), definition.risks().get(index)))
                .toList();
        return new PlanEditor(
                definition.id(),
                definition.title(),
                definition.objective(),
                definition.scope(),
                risks,
                definition.openQuestions().stream()
                        .map(PlanManagement::questionEditor)
                        .toList(),
                definition.steps().stream().map(PlanManagement::stepEditor).toList(),
                definition.revision(),
                definition.updatedAt());
    }

    private static PlanContracts.ManagementOpenQuestion questionEditor(PlanContracts.OpenQuestion question) {
        return new PlanContracts.ManagementOpenQuestion(
                question.id(),
                question.id(),
                question.prompt(),
                question.decision().map(PlanContracts.Decision::answer));
    }

    private static PlanContracts.ManagementStep stepEditor(PlanContracts.Step step) {
        return new PlanContracts.ManagementStep(
                step.id(), step.id(), step.title(), step.instruction(), step.acceptanceCriteria(), step.dependencies());
    }

    private ViewSchema view() {
        return new ViewSchema(
                ViewSchema.CURRENT_VERSION,
                documents.extensionId().value() + ".management",
                "计划定义",
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
                        form("plan-create", "新建 Plan", NEW_SOURCE, true),
                        form("plan-edit", "编辑 Plan", EDIT_SOURCE, false)));
    }

    private ViewSchema.Table definitionsTable() {
        ViewAction delete = new ViewAction(
                "删除", "delete", Map.of(), Map.of("id", "id"), new ExpectedRevisionBinding.RowField("revision"), true);
        return new ViewSchema.Table(
                "definitions",
                "Plan",
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
            fields.add(text(source, "id", "定义标识", ViewFieldType.TEXT));
        }
        fields.add(text(source, "title", "标题", ViewFieldType.TEXT));
        fields.add(text(source, "objective", "验收目标", ViewFieldType.MULTILINE));
        fields.add(text(source, "scope", "范围", ViewFieldType.MULTILINE));
        fields.add(risks(source));
        fields.add(openQuestions(source));
        fields.add(steps(source));
        return List.copyOf(fields);
    }

    private static ViewField text(String source, String name, String label, ViewFieldType type) {
        return new ViewField(
                name,
                label,
                type,
                new ViewBinding(source, name),
                Optional.empty(),
                ViewFieldValidation.required(true),
                List.of(),
                Optional.empty(),
                Optional.empty());
    }

    private static ViewStructuredListField risks(String source) {
        return new ViewStructuredListField(
                "risks",
                "风险",
                new ViewBinding(source, "risks"),
                0,
                100,
                "itemKey",
                List.of(item("description", "风险说明", ViewStructuredItemType.MULTILINE, true)),
                List.of(),
                Optional.empty());
    }

    private static ViewStructuredListField openQuestions(String source) {
        return new ViewStructuredListField(
                "openQuestions",
                "开放问题与决策",
                new ViewBinding(source, "openQuestions"),
                0,
                100,
                "itemKey",
                List.of(
                        item("id", "问题标识", ViewStructuredItemType.TEXT, true),
                        item("prompt", "问题", ViewStructuredItemType.MULTILINE, true),
                        item("decision", "显式决策（可留空）", ViewStructuredItemType.MULTILINE, false)),
                List.of(),
                Optional.empty());
    }

    private static ViewStructuredListField steps(String source) {
        return new ViewStructuredListField(
                "steps",
                "步骤与依赖",
                new ViewBinding(source, "steps"),
                1,
                100,
                "itemKey",
                List.of(
                        item("id", "步骤标识", ViewStructuredItemType.TEXT, true),
                        item("title", "标题", ViewStructuredItemType.TEXT, true),
                        item("instruction", "单 Turn 指令", ViewStructuredItemType.MULTILINE, true),
                        item("acceptanceCriteria", "验收条件", ViewStructuredItemType.MULTILINE, true),
                        textList("dependencies", "前置步骤 ID")),
                List.of(Map.of(
                        "itemKey", "step-1",
                        "id", "step-1",
                        "title", "第一步",
                        "instruction", "描述需要完成的工作",
                        "acceptanceCriteria", "描述可验证的完成条件",
                        "dependencies", List.of())),
                Optional.empty());
    }

    private static ViewStructuredItemField item(
            String name, String label, ViewStructuredItemType type, boolean required) {
        return new ViewStructuredItemField(
                name,
                label,
                type,
                Optional.empty(),
                List.of(),
                ViewStructuredItemValidation.required(required),
                List.of());
    }

    private static ViewStructuredItemField textList(String name, String label) {
        ViewStructuredItemValidation validation = new ViewStructuredItemValidation(
                false,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(0),
                Optional.of(ViewStructuredListField.MAX_TEXT_LIST_ITEMS));
        return new ViewStructuredItemField(
                name, label, ViewStructuredItemType.TEXT_LIST, Optional.empty(), List.of(), validation, List.of());
    }

    private record PlanEditor(
            String id,
            String title,
            String objective,
            String scope,
            List<PlanContracts.ManagementRisk> risks,
            List<PlanContracts.ManagementOpenQuestion> openQuestions,
            List<PlanContracts.ManagementStep> steps,
            long revision,
            Instant updatedAt) {}
}
