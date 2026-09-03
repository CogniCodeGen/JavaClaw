package com.javaclaw.builtin.extensions;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.javaclaw.builtin.contracts.ScheduleActionContracts;
import com.javaclaw.builtin.contracts.ScheduleContracts;
import com.javaclaw.extension.spi.ExpectedRevisionBinding;
import com.javaclaw.extension.spi.ViewAction;
import com.javaclaw.extension.spi.ViewBinding;
import com.javaclaw.extension.spi.ViewCommandBinding;
import com.javaclaw.extension.spi.ViewCondition;
import com.javaclaw.extension.spi.ViewConditionOperator;
import com.javaclaw.extension.spi.ViewField;
import com.javaclaw.extension.spi.ViewFieldType;
import com.javaclaw.extension.spi.ViewFieldValidation;
import com.javaclaw.extension.spi.ViewFormField;
import com.javaclaw.extension.spi.ViewOption;
import com.javaclaw.extension.spi.ViewSchema;
import com.javaclaw.extension.spi.ViewStructuredItemField;
import com.javaclaw.extension.spi.ViewStructuredItemType;
import com.javaclaw.extension.spi.ViewStructuredItemValidation;
import com.javaclaw.extension.spi.ViewStructuredListField;

/** Schedule 管理页复用的新建与编辑表单定义。 */
final class ScheduleManagementForm {
    private static final String CREATE = "definition/create";
    private static final String UPDATE = "definition/update";
    private static final String PROFILE_SOURCE = "profiles";
    private static final String TARGET_SOURCE = "scheduleTargets";

    private ScheduleManagementForm() {}

    static ViewSchema.Form create(String id, String title, String source, String actionSource, boolean create) {
        ViewAction save = create
                ? saveAction("创建 Schedule", CREATE, source, false)
                : saveAction("保存 Schedule", UPDATE, source, true);
        return new ViewSchema.Form(id, title, fields(source, actionSource, create), save);
    }

    private static ViewAction saveAction(String label, String operation, String source, boolean update) {
        List<ViewCommandBinding> bindings = new ArrayList<>();
        if (update) {
            bindings.add(new ViewCommandBinding("id", new ViewBinding(source, "id")));
        }
        bindings.add(new ViewCommandBinding("profileId", new ViewBinding(PROFILE_SOURCE, "id")));
        bindings.add(new ViewCommandBinding("profileRevision", new ViewBinding(PROFILE_SOURCE, "revision")));
        bindings.add(new ViewCommandBinding("targetKind", new ViewBinding(TARGET_SOURCE, "targetKind")));
        bindings.add(new ViewCommandBinding("targetExtensionId", new ViewBinding(TARGET_SOURCE, "targetExtensionId")));
        bindings.add(new ViewCommandBinding("targetId", new ViewBinding(TARGET_SOURCE, "targetId")));
        bindings.add(new ViewCommandBinding("targetRevision", new ViewBinding(TARGET_SOURCE, "targetRevision")));
        bindings.add(new ViewCommandBinding("targetSchemaHash", new ViewBinding(TARGET_SOURCE, "actionSchemaHash")));
        return new ViewAction(
                label,
                operation,
                Map.of(),
                Map.of(),
                update ? new ExpectedRevisionBinding.SourceRevision(source) : new ExpectedRevisionBinding.None(),
                false,
                bindings.toArray(ViewCommandBinding[]::new));
    }

    private static List<? extends ViewFormField> fields(String source, String actionSource, boolean create) {
        List<ViewFormField> fields = new ArrayList<>();
        if (create) {
            fields.add(text(source, "id", "Schedule 标识", ViewFieldType.TEXT, 100, Optional.empty()));
        }
        fields.add(text(source, "name", "名称", ViewFieldType.TEXT, 200, Optional.empty()));
        fields.add(booleanField(source, "enabled", "启用", "true"));
        fields.add(timingKind(source));
        fields.add(text(source, "cronExpression", "Quartz Cron", ViewFieldType.TEXT, 256, visible(source, "CRON")));
        fields.add(text(source, "zoneId", "IANA Zone", ViewFieldType.TEXT, 128, visible(source, "CRON")));
        fields.add(number(source, "intervalMinutes", "间隔分钟数", "60", 1, 525_600, visible(source, "FIXED_INTERVAL")));
        fields.add(text(
                source, "firstFireAt", "首次触发时间（ISO-8601）", ViewFieldType.TEXT, 64, visible(source, "FIXED_INTERVAL")));
        fields.add(text(source, "title", "新 Thread 标题", ViewFieldType.TEXT, 200, Optional.empty()));
        fields.add(text(source, "instruction", "Turn 指令", ViewFieldType.MULTILINE, 32_768, Optional.empty()));
        fields.add(actionArguments(actionSource));
        fields.add(number(source, "maximumTurns", "最大 Turn 数", "10", 1, 10_000, Optional.empty()));
        fields.add(number(source, "inputTokens", "输入 token 总上限", "100000", 1, 1_000_000_000, Optional.empty()));
        fields.add(number(source, "outputTokens", "输出 token 总上限", "50000", 1, 1_000_000_000, Optional.empty()));
        fields.add(number(source, "toolCalls", "Tool 调用总上限", "100", 1, 1_000_000, Optional.empty()));
        return List.copyOf(fields);
    }

    private static ViewStructuredListField actionArguments(String source) {
        ViewStructuredItemValidation boundedText = new ViewStructuredItemValidation(
                false,
                Optional.of(0),
                Optional.of(ViewStructuredListField.MAX_TEXT_LENGTH),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
        ViewStructuredItemField type = new ViewStructuredItemField(
                "type",
                "类型",
                ViewStructuredItemType.CHOICE,
                Optional.of(ScheduleActionContracts.ValueType.STRING.name()),
                List.of(),
                ViewStructuredItemValidation.required(true),
                java.util.Arrays.stream(ScheduleActionContracts.ValueType.values())
                        .map(value -> new ViewOption(value.name(), value.name()))
                        .toList(),
                Optional.empty());
        ViewStructuredItemField value = new ViewStructuredItemField(
                "value",
                "固定值",
                ViewStructuredItemType.TEXT,
                Optional.of(""),
                List.of(),
                boundedText,
                List.of(),
                Optional.empty());
        return new ViewStructuredListField(
                "actionArguments",
                "SchedulableAction 固定参数",
                new ViewBinding(source, "actionArguments"),
                0,
                32,
                "name",
                List.of(type, value),
                List.of(),
                Optional.of(new ViewCondition(
                        new ViewBinding(TARGET_SOURCE, "targetKind"),
                        ViewConditionOperator.EQUALS,
                        ScheduleContracts.TargetKind.ACTION.name())));
    }

    private static ViewField timingKind(String source) {
        return new ViewField(
                "timingKind",
                "触发方式",
                ViewFieldType.CHOICE,
                new ViewBinding(source, "timingKind"),
                Optional.of("CRON"),
                ViewFieldValidation.required(true),
                List.of(new ViewOption("CRON", "Cron + IANA Zone"), new ViewOption("FIXED_INTERVAL", "固定间隔")),
                Optional.empty(),
                Optional.empty());
    }

    private static ViewField booleanField(String source, String name, String label, String initial) {
        return new ViewField(
                name,
                label,
                ViewFieldType.BOOLEAN,
                new ViewBinding(source, name),
                Optional.of(initial),
                ViewFieldValidation.required(false),
                List.of(),
                Optional.empty(),
                Optional.empty());
    }

    private static ViewField text(
            String source,
            String name,
            String label,
            ViewFieldType type,
            int maximumLength,
            Optional<ViewCondition> condition) {
        ViewFieldValidation validation = new ViewFieldValidation(
                true, Optional.of(1), Optional.of(maximumLength), Optional.empty(), Optional.empty(), Optional.empty());
        return new ViewField(
                name,
                label,
                type,
                new ViewBinding(source, name),
                Optional.empty(),
                validation,
                List.of(),
                Optional.empty(),
                condition);
    }

    private static ViewField number(
            String source,
            String name,
            String label,
            String initial,
            long minimum,
            long maximum,
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

    private static Optional<ViewCondition> visible(String source, String timingKind) {
        return Optional.of(
                new ViewCondition(new ViewBinding(source, "timingKind"), ViewConditionOperator.EQUALS, timingKind));
    }
}
