package com.javaclaw.builtin.extensions;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ToolDescriptor;
import com.javaclaw.api.ToolIdentity;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.extension.spi.ExpectedRevisionBinding;
import com.javaclaw.extension.spi.ExtensionPayloadCodec;
import com.javaclaw.extension.spi.ExtensionSchema;
import com.javaclaw.extension.spi.ViewAction;
import com.javaclaw.extension.spi.ViewArgumentBinding;
import com.javaclaw.extension.spi.ViewBinding;
import com.javaclaw.extension.spi.ViewCommandBinding;
import com.javaclaw.extension.spi.ViewDataSource;
import com.javaclaw.extension.spi.ViewField;
import com.javaclaw.extension.spi.ViewFieldType;
import com.javaclaw.extension.spi.ViewFieldValidation;
import com.javaclaw.extension.spi.ViewOption;
import com.javaclaw.extension.spi.ViewSchema;
import com.javaclaw.extension.spi.ViewSelectionMode;
import com.javaclaw.extension.spi.ViewStructuredItemField;
import com.javaclaw.extension.spi.ViewStructuredItemType;
import com.javaclaw.extension.spi.ViewStructuredItemValidation;
import com.javaclaw.extension.spi.ViewStructuredListField;

/** Memory 的公开 JSON Schema、工具描述与 ViewSchema v2。 */
final class MemoryExtensionPresentation {
    private MemoryExtensionPresentation() {}

    static List<ExtensionSchema> schemas(ExtensionPayloadCodec codec) {
        return List.of(
                schema(codec, "memory/v5", "Memory current value", memoryProperties(), memoryRequired()),
                schema(codec, "proposal/v5", "Memory learning proposal", proposalProperties(), proposalRequired()),
                schema(
                        codec,
                        "learning-settings/v5",
                        "Memory learning settings",
                        settingsProperties(),
                        List.of("revision", "policy", "updatedAt")),
                schema(
                        codec,
                        "history/v5",
                        "Memory immutable history",
                        Map.of(
                                "entries",
                                ContractSchemaFactory.array(historyEntrySchema()),
                                "hasMore",
                                ContractSchemaFactory.bool()),
                        List.of("entries", "hasMore")));
    }

    static ToolDescriptor searchTool(ExtensionPayloadCodec codec, long extensionRevision) {
        CanonicalPayload input = codec.encode(ContractSchemaFactory.object(
                Map.of(
                        "query", ContractSchemaFactory.string(),
                        "scopes", ContractSchemaFactory.uniqueStrings(),
                        "tags", ContractSchemaFactory.uniqueStrings(),
                        "limit", ContractSchemaFactory.boundedInteger(1, 100)),
                List.of("query", "scopes", "tags", "limit")));
        CanonicalPayload output = codec.encode(ContractSchemaFactory.object(
                Map.of("matches", ContractSchemaFactory.array(memorySchema())), List.of("matches")));
        return tool(extensionRevision, "memory_search", "检索当前 Workspace 中已确认的记忆", ToolRisk.READ_ONLY, input, output);
    }

    static ToolDescriptor proposeTool(ExtensionPayloadCodec codec, long extensionRevision) {
        CanonicalPayload input = codec.encode(learningRequestSchema());
        CanonicalPayload output = codec.encode(ContractSchemaFactory.object(
                Map.of(
                        "action",
                        ContractSchemaFactory.enumStrings("IGNORED", "PROPOSED", "AUTO_ACCEPTED"),
                        "proposal",
                        nullable(proposalSchema()),
                        "memory",
                        nullable(memorySchema())),
                List.of("action", "proposal", "memory")));
        return tool(
                extensionRevision,
                "memory_propose",
                "提交可审计的记忆学习提案；自动写入仅限逐字可核验低风险事实",
                ToolRisk.WORKSPACE_WRITE,
                input,
                output);
    }

    static ViewSchema managementView() {
        return new ViewSchema(
                ViewSchema.CURRENT_VERSION,
                "javaclaw.memory.management",
                "记忆",
                managementDataSources(),
                managementNodes());
    }

    private static List<ViewDataSource> managementDataSources() {
        return List.of(
                new ViewDataSource("newMemory", "view.new-memory", Map.of(), List.of(), 1),
                new ViewDataSource("memories", "view.memories", Map.of(), List.of(), 100),
                new ViewDataSource(
                        "memoryEditor",
                        "view.memory",
                        Map.of(),
                        List.of(
                                new ViewArgumentBinding("id", "memories", "id"),
                                new ViewArgumentBinding("revision", "memories", "revision")),
                        1),
                new ViewDataSource(
                        "history",
                        "view.history",
                        Map.of(),
                        List.of(new ViewArgumentBinding("id", "memories", "id")),
                        100),
                new ViewDataSource("stats", "view.stats", Map.of(), List.of(), 1),
                new ViewDataSource("tombstones", "view.tombstones", Map.of(), List.of(), 100),
                new ViewDataSource("proposals", "view.proposals", Map.of(), List.of(), 100),
                new ViewDataSource("settings", "view.settings", Map.of(), List.of(), 1));
    }

    private static List<ViewSchema.Node> managementNodes() {
        return List.of(
                learningSettingsForm(),
                manualCreateForm(),
                correctionForm(),
                new ViewSchema.Table(
                        "memories",
                        "当前记忆",
                        "memories",
                        "id",
                        List.of(
                                new ViewSchema.Column("content", "内容", Optional.of(360)),
                                new ViewSchema.Column("kind", "类别", Optional.of(100)),
                                new ViewSchema.Column("scope", "作用域", Optional.of(120)),
                                new ViewSchema.Column("sourceThreadId", "来源 Thread", Optional.of(220)),
                                new ViewSchema.Column("sourceItemId", "来源 Item", Optional.of(220)),
                                new ViewSchema.Column("pinned", "固定", Optional.of(80)),
                                new ViewSchema.Column("revision", "版本", Optional.of(80))),
                        ViewSelectionMode.SINGLE,
                        memoryActions()),
                historyTable(),
                statsTable(),
                new ViewSchema.Table(
                        "tombstones",
                        "可恢复记忆",
                        "tombstones",
                        "id",
                        List.of(
                                new ViewSchema.Column("content", "删除前正文", Optional.of(360)),
                                new ViewSchema.Column("revision", "删除版本", Optional.of(90))),
                        ViewSelectionMode.SINGLE,
                        List.of(action(
                                "恢复", "restore", Map.of("id", "id", "sourceRevision", "sourceRevision"), false))),
                new ViewSchema.Table(
                        "proposals",
                        "学习提案",
                        "proposals",
                        "id",
                        List.of(
                                new ViewSchema.Column("content", "候选正文", Optional.of(320)),
                                new ViewSchema.Column("kind", "类别", Optional.of(100)),
                                new ViewSchema.Column("concerns", "风险原因", Optional.of(220)),
                                new ViewSchema.Column("state", "状态", Optional.of(120)),
                                new ViewSchema.Column("revision", "版本", Optional.of(80))),
                        ViewSelectionMode.SINGLE,
                        proposalActions()));
    }

    private static ViewSchema.Table historyTable() {
        ViewAction restore = new ViewAction(
                "恢复此版本",
                "restore",
                Map.of(),
                Map.of("id", "id", "sourceRevision", "sourceRevision"),
                new ExpectedRevisionBinding.RowField("currentRevision"),
                true);
        return new ViewSchema.Table(
                "history",
                "不可变历史",
                "history",
                "revision",
                List.of(
                        new ViewSchema.Column("revision", "版本", Optional.of(80)),
                        new ViewSchema.Column("kind", "类别", Optional.of(100)),
                        new ViewSchema.Column("scope", "作用域", Optional.of(120)),
                        new ViewSchema.Column("content", "历史正文", Optional.of(360)),
                        new ViewSchema.Column("tombstone", "删除标记", Optional.of(90)),
                        new ViewSchema.Column("updatedAt", "写入时间", Optional.of(180))),
                ViewSelectionMode.SINGLE,
                List.of(restore));
    }

    private static ViewSchema.Table statsTable() {
        return new ViewSchema.Table(
                "stats",
                "Workspace 统计",
                "stats",
                "id",
                List.of(
                        new ViewSchema.Column("active", "活动记忆", Optional.of(100)),
                        new ViewSchema.Column("pinned", "已固定", Optional.of(100)),
                        new ViewSchema.Column("pendingProposals", "待处理提案", Optional.of(120)),
                        new ViewSchema.Column("tombstones", "删除标记", Optional.of(100))),
                ViewSelectionMode.NONE,
                List.of());
    }

    private static ViewSchema.Form correctionForm() {
        List<ViewField> fields = List.of(
                kindField("memoryEditor"),
                textField("scope", "作用域", "memoryEditor", "scope", ViewFieldType.TEXT),
                textField("content", "纠错正文", "memoryEditor", "content", ViewFieldType.MULTILINE));
        ViewAction save = new ViewAction(
                "保存纠错",
                "update/content",
                Map.of(),
                Map.of(),
                new ExpectedRevisionBinding.SourceRevision("memoryEditor"),
                false,
                new ViewCommandBinding("id", new ViewBinding("memoryEditor", "id")));
        return new ViewSchema.Form("memory-correction", "编辑与纠错", fields, save);
    }

    private static ViewSchema.Form manualCreateForm() {
        List<com.javaclaw.extension.spi.ViewFormField> fields = List.of(
                textField("id", "记忆标识", "newMemory", "id", ViewFieldType.TEXT),
                kindField("newMemory"),
                textField("scope", "作用域", "newMemory", "scope", ViewFieldType.TEXT),
                textField("content", "记忆正文", "newMemory", "content", ViewFieldType.MULTILINE),
                tagsField(),
                booleanField("pinned", "创建后固定", "newMemory"),
                sourcesField());
        ViewAction create = new ViewAction(
                "创建记忆", "management/create", Map.of(), Map.of(), new ExpectedRevisionBinding.None(), false);
        return new ViewSchema.Form("memory-create", "手工创建", fields, create);
    }

    private static ViewField kindField(String source) {
        return new ViewField(
                "kind",
                "类别",
                ViewFieldType.CHOICE,
                new ViewBinding(source, "kind"),
                Optional.of("FACT"),
                ViewFieldValidation.required(true),
                List.of(new ViewOption("FACT", "事实"), new ViewOption("PERSONA", "画像")),
                Optional.empty(),
                Optional.empty());
    }

    private static ViewField booleanField(String name, String label, String source) {
        return new ViewField(
                name,
                label,
                ViewFieldType.BOOLEAN,
                new ViewBinding(source, name),
                Optional.of("false"),
                ViewFieldValidation.required(false),
                List.of(),
                Optional.empty(),
                Optional.empty());
    }

    private static ViewStructuredListField tagsField() {
        return new ViewStructuredListField(
                "tags",
                "检索标签",
                new ViewBinding("newMemory", "tags"),
                0,
                32,
                "itemKey",
                List.of(structuredText("value", "标签", ViewStructuredItemType.TEXT)),
                List.of(),
                Optional.empty());
    }

    private static ViewStructuredListField sourcesField() {
        return new ViewStructuredListField(
                "sources",
                "可核验来源（最多一个）",
                new ViewBinding("newMemory", "sources"),
                0,
                1,
                "itemKey",
                List.of(
                        structuredText("threadId", "Thread UUID", ViewStructuredItemType.TEXT),
                        structuredText("itemId", "Item UUID", ViewStructuredItemType.TEXT),
                        structuredText("verbatim", "逐字来源", ViewStructuredItemType.MULTILINE)),
                List.of(),
                Optional.empty());
    }

    private static ViewStructuredItemField structuredText(String name, String label, ViewStructuredItemType type) {
        return new ViewStructuredItemField(
                name, label, type, Optional.empty(), List.of(), ViewStructuredItemValidation.required(true), List.of());
    }

    private static ViewField textField(String name, String label, String source, String field, ViewFieldType type) {
        return new ViewField(
                name,
                label,
                type,
                new ViewBinding(source, field),
                Optional.empty(),
                ViewFieldValidation.required(true),
                List.of(),
                Optional.empty(),
                Optional.empty());
    }

    private static List<ViewAction> memoryActions() {
        return List.of(
                action("固定", "pin/set", Map.of("id", "id"), false),
                action("取消固定", "pin/clear", Map.of("id", "id"), false),
                action("删除", "tombstone", Map.of("id", "id"), true));
    }

    private static List<ViewAction> proposalActions() {
        return List.of(
                action("接受", "proposal/accept", Map.of("id", "id"), false),
                action("拒绝", "proposal/reject", Map.of("id", "id"), true));
    }

    private static ViewAction action(
            String label, String command, Map<String, String> rowArguments, boolean dangerous) {
        return new ViewAction(
                label, command, Map.of(), rowArguments, new ExpectedRevisionBinding.RowField("revision"), dangerous);
    }

    private static ViewSchema.Form learningSettingsForm() {
        ViewField policy = new ViewField(
                "policy",
                "学习策略",
                ViewFieldType.CHOICE,
                new ViewBinding("settings", "policy"),
                Optional.of("SUGGEST"),
                ViewFieldValidation.required(true),
                List.of(
                        new ViewOption("OFF", "关闭"),
                        new ViewOption("SUGGEST", "仅提案"),
                        new ViewOption("AUTO_LOW_RISK", "自动接受低风险事实")),
                Optional.empty(),
                Optional.empty());
        ViewAction save = new ViewAction(
                "保存学习策略",
                "settings/update",
                Map.of(),
                Map.of(),
                new ExpectedRevisionBinding.SourceRevision("settings"),
                false);
        return new ViewSchema.Form("learning-settings", "学习策略", List.of(policy), save);
    }

    private static ExtensionSchema schema(
            ExtensionPayloadCodec codec,
            String suffix,
            String title,
            Map<String, Object> properties,
            List<String> required) {
        String id = BuiltinExtensionIds.MEMORY + "/" + suffix;
        return new ExtensionSchema(id, ContractSchemaFactory.document(codec, id, title, properties, required));
    }

    private static ToolDescriptor tool(
            long extensionRevision,
            String name,
            String description,
            ToolRisk risk,
            CanonicalPayload input,
            CanonicalPayload output) {
        return new ToolDescriptor(
                new ToolIdentity(BuiltinExtensionIds.MEMORY, name, extensionRevision),
                description,
                input,
                output,
                risk,
                Set.of("memory", "context"));
    }

    private static Map<String, Object> memorySchema() {
        return ContractSchemaFactory.object(memoryProperties(), memoryRequired());
    }

    private static Map<String, Object> memoryProperties() {
        return Map.ofEntries(
                Map.entry("id", ContractSchemaFactory.string()),
                Map.entry("revision", ContractSchemaFactory.integer(1)),
                Map.entry("kind", ContractSchemaFactory.enumStrings("FACT", "PERSONA")),
                Map.entry("scope", ContractSchemaFactory.string()),
                Map.entry("content", ContractSchemaFactory.string()),
                Map.entry("tags", ContractSchemaFactory.uniqueStrings()),
                Map.entry("pinned", ContractSchemaFactory.bool()),
                Map.entry("source", nullable(sourceSchema())),
                Map.entry("createdAt", ContractSchemaFactory.instant()),
                Map.entry("updatedAt", ContractSchemaFactory.instant()));
    }

    private static List<String> memoryRequired() {
        return List.of(
                "id", "revision", "kind", "scope", "content", "tags", "pinned", "source", "createdAt", "updatedAt");
    }

    private static Map<String, Object> sourceSchema() {
        return ContractSchemaFactory.object(
                Map.of(
                        "workspaceId", ContractSchemaFactory.string(),
                        "threadId", ContractSchemaFactory.string(),
                        "itemId", ContractSchemaFactory.string(),
                        "verbatim", ContractSchemaFactory.string()),
                List.of("workspaceId", "threadId", "itemId", "verbatim"));
    }

    private static Map<String, Object> learningRequestSchema() {
        return ContractSchemaFactory.object(
                Map.of(
                        "id", ContractSchemaFactory.string(),
                        "kind", ContractSchemaFactory.enumStrings("FACT", "PERSONA"),
                        "scope", ContractSchemaFactory.string(),
                        "content", ContractSchemaFactory.string(),
                        "tags", ContractSchemaFactory.uniqueStrings(),
                        "source", sourceSchema()),
                List.of("id", "kind", "scope", "content", "tags", "source"));
    }

    private static Map<String, Object> proposalSchema() {
        return ContractSchemaFactory.object(proposalProperties(), proposalRequired());
    }

    private static Map<String, Object> proposalProperties() {
        return Map.of(
                "id", ContractSchemaFactory.string(),
                "revision", ContractSchemaFactory.integer(1),
                "candidate", learningRequestSchema(),
                "concerns", ContractSchemaFactory.uniqueStrings(),
                "state", ContractSchemaFactory.enumStrings("PENDING", "AUTO_ACCEPTED", "ACCEPTED", "REJECTED"),
                "memoryId", nullable(ContractSchemaFactory.string()),
                "createdAt", ContractSchemaFactory.instant(),
                "updatedAt", ContractSchemaFactory.instant());
    }

    private static List<String> proposalRequired() {
        return List.of("id", "revision", "candidate", "concerns", "state", "memoryId", "createdAt", "updatedAt");
    }

    private static Map<String, Object> settingsProperties() {
        return Map.of(
                "revision", ContractSchemaFactory.integer(0),
                "policy", ContractSchemaFactory.enumStrings("OFF", "SUGGEST", "AUTO_LOW_RISK"),
                "updatedAt", ContractSchemaFactory.instant());
    }

    private static Map<String, Object> historyEntrySchema() {
        return ContractSchemaFactory.object(
                Map.of(
                        "revision", ContractSchemaFactory.integer(1),
                        "memory", memorySchema(),
                        "tombstone", ContractSchemaFactory.bool(),
                        "updatedAt", ContractSchemaFactory.instant()),
                List.of("revision", "memory", "tombstone", "updatedAt"));
    }

    private static Map<String, Object> nullable(Map<String, Object> schema) {
        return Map.of("anyOf", List.of(schema, Map.of("type", "null")));
    }
}
