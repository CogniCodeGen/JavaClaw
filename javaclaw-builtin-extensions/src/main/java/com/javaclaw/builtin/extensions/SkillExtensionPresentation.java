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
import com.javaclaw.builtin.contracts.SkillContracts;
import com.javaclaw.builtin.contracts.SkillTransferContracts;
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
import com.javaclaw.extension.spi.ViewSchema;
import com.javaclaw.extension.spi.ViewSelectionMode;

/** Skill 的公开 Schema、受治理工具和 ViewSchema v2。 */
final class SkillExtensionPresentation {
    private SkillExtensionPresentation() {}

    static List<ExtensionSchema> schemas(ExtensionPayloadCodec codec) {
        return List.of(
                schema(codec, "draft/v5", "Skill Draft", draftProperties(), draftRequired()),
                schema(codec, "published/v5", "Published Skill", publishedProperties(), publishedRequired()),
                schema(codec, "proposal/v5", "Skill Proposal", proposalProperties(), proposalRequired()),
                schema(codec, "catalog/v5", "Frozen Skill catalog", catalogProperties(), catalogRequired()),
                schema(
                        codec,
                        "resource-execution/v5",
                        "Sandboxed Skill resource execution",
                        executionResultProperties(),
                        List.of("exitCode", "output", "truncated", "elapsedMillis")));
    }

    static ToolDescriptor searchTool(ExtensionPayloadCodec codec, long revision) {
        CanonicalPayload input = codec.encode(ContractSchemaFactory.object(
                Map.of(
                        "query", ContractSchemaFactory.string(),
                        "limit", ContractSchemaFactory.boundedInteger(1, 100)),
                List.of("query", "limit")));
        CanonicalPayload output = codec.encode(ContractSchemaFactory.object(
                Map.of(
                        "matches", ContractSchemaFactory.array(summarySchema()),
                        "catalogDigest", ContractSchemaFactory.digest()),
                List.of("matches", "catalogDigest")));
        return tool(revision, "skill_search", "搜索本 Turn 冻结的已发布 Skill 摘要", input, output, ToolRisk.READ_ONLY);
    }

    static ToolDescriptor readTool(ExtensionPayloadCodec codec, long revision) {
        CanonicalPayload input = codec.encode(ContractSchemaFactory.object(
                Map.of(
                        "id", ContractSchemaFactory.string(),
                        "revision", ContractSchemaFactory.integer(1),
                        "digest", ContractSchemaFactory.digest()),
                List.of("id", "revision", "digest")));
        CanonicalPayload output =
                codec.encode(ContractSchemaFactory.object(publishedProperties(), publishedRequired()));
        return tool(
                revision,
                "skill_read",
                "按 published revision 与 digest 精确读取已发布 Skill",
                input,
                output,
                ToolRisk.READ_ONLY);
    }

    static ToolDescriptor executeResourceTool(ExtensionPayloadCodec codec, long revision) {
        CanonicalPayload input = codec.encode(ContractSchemaFactory.object(
                Map.of(
                        "skill",
                                ContractSchemaFactory.object(
                                        Map.of(
                                                "id", ContractSchemaFactory.string(),
                                                "revision", ContractSchemaFactory.integer(1),
                                                "digest", ContractSchemaFactory.digest()),
                                        List.of("id", "revision", "digest")),
                        "resourceId", ContractSchemaFactory.string(),
                        "arguments", ContractSchemaFactory.array(ContractSchemaFactory.string())),
                List.of("skill", "resourceId", "arguments")));
        CanonicalPayload output = codec.encode(ContractSchemaFactory.object(
                executionResultProperties(), List.of("exitCode", "output", "truncated", "elapsedMillis")));
        return tool(
                revision,
                "skill_execute_resource",
                "在 Native Sandbox 中执行冻结 Published Skill 的 Java/JShell 资源",
                input,
                output,
                ToolRisk.PROCESS);
    }

    static ViewSchema managementView() {
        return new ViewSchema(
                ViewSchema.CURRENT_VERSION,
                "javaclaw.skill.management",
                "技能",
                managementDataSources(),
                managementNodes());
    }

    private static List<ViewDataSource> managementDataSources() {
        return List.of(
                new ViewDataSource("newDraft", "view.new-draft", Map.of(), List.of(), 1),
                new ViewDataSource("drafts", "view.drafts", Map.of(), List.of(), 100),
                new ViewDataSource(
                        "draftEditor",
                        "view.draft",
                        Map.of(),
                        List.of(
                                new ViewArgumentBinding("id", "drafts", "id"),
                                new ViewArgumentBinding("revision", "drafts", "revision")),
                        1),
                new ViewDataSource(
                        "resourceDraft",
                        "view.draft",
                        Map.of(),
                        List.of(
                                new ViewArgumentBinding("id", "drafts", "id"),
                                new ViewArgumentBinding("revision", "drafts", "revision")),
                        1),
                new ViewDataSource(
                        "draftResources",
                        "view.resources",
                        Map.of(),
                        List.of(
                                new ViewArgumentBinding("id", "drafts", "id"),
                                new ViewArgumentBinding("revision", "drafts", "revision")),
                        100),
                new ViewDataSource(
                        "history",
                        "view.history",
                        Map.of(),
                        List.of(new ViewArgumentBinding("id", "drafts", "id")),
                        100),
                new ViewDataSource("tombstones", "view.tombstones", Map.of(), List.of(), 100),
                new ViewDataSource("published", "view.published", Map.of(), List.of(), 100),
                new ViewDataSource("proposals", "view.proposals", Map.of(), List.of(), 100));
    }

    private static List<ViewSchema.Node> managementNodes() {
        return List.of(
                importForm(),
                createDraftForm(),
                editDraftForm(),
                addResourceForm(),
                table(
                        "drafts",
                        "Draft",
                        "drafts",
                        List.of("name", "resourceCount", "revision"),
                        List.of(
                                action(
                                        "发布",
                                        "publish",
                                        Map.of("id", "id", "draftRevision", "revision"),
                                        "publishedRevision",
                                        false),
                                action("删除", "draft/tombstone", Map.of("id", "id"), "revision", true))),
                resourcesTable(),
                historyTable(),
                table(
                        "tombstones",
                        "可恢复 Draft",
                        "tombstones",
                        List.of("name", "revision"),
                        List.of(action(
                                "恢复",
                                "draft/restore",
                                Map.of("id", "id", "sourceRevision", "sourceRevision"),
                                "revision",
                                false))),
                table(
                        "published",
                        "Published",
                        "published",
                        List.of("name", "enabled", "revision"),
                        List.of(
                                action("启用", "enable/set", Map.of("id", "id"), "revision", false),
                                action("停用", "enable/clear", Map.of("id", "id"), "revision", true),
                                exportAction("导出 Markdown", SkillTransferContracts.TransferFormat.MARKDOWN),
                                exportAction("导出 v5 Bundle", SkillTransferContracts.TransferFormat.BUNDLE))),
                table(
                        "proposals",
                        "Proposal",
                        "proposals",
                        List.of("name", "description", "state", "revision"),
                        List.of(
                                action("采纳为 Draft", "proposal/adopt", Map.of("id", "id"), "revision", false),
                                action("拒绝", "proposal/reject", Map.of("id", "id"), "revision", true))));
    }

    private static ViewSchema.Form importForm() {
        ViewField attachment = new ViewField(
                "attachment",
                "v5 Skill Markdown 或 Bundle",
                ViewFieldType.ATTACHMENT,
                new ViewBinding("newDraft", "importAttachment"),
                Optional.empty(),
                ViewFieldValidation.attachment(
                        true,
                        new com.javaclaw.extension.spi.ViewAttachmentPolicy(
                                Set.of(
                                        SkillTransferContracts.MARKDOWN_MEDIA_TYPE,
                                        SkillTransferContracts.BUNDLE_MEDIA_TYPE),
                                SkillTransferContracts.MAXIMUM_IMPORT_BYTES)),
                List.of(),
                Optional.empty(),
                Optional.empty());
        ViewAction upload = new ViewAction(
                "导入为 Draft", "skill/import", Map.of(), Map.of(), new ExpectedRevisionBinding.None(), false);
        return new ViewSchema.Form("skill-import", "导入 v5 Skill", List.of(attachment), upload);
    }

    private static ViewAction exportAction(String label, SkillTransferContracts.TransferFormat format) {
        return new ViewAction(
                label,
                "skill/export",
                Map.of("format", format.name()),
                Map.of("id", "id"),
                new ExpectedRevisionBinding.RowField("revision"),
                false);
    }

    private static ViewSchema.Table historyTable() {
        ViewAction restore = new ViewAction(
                "恢复此版本",
                "draft/restore",
                Map.of(),
                Map.of("id", "id", "sourceRevision", "sourceRevision"),
                new ExpectedRevisionBinding.RowField("currentRevision"),
                true);
        return new ViewSchema.Table(
                "history",
                "Draft 不可变历史",
                "history",
                "revision",
                List.of(
                        new ViewSchema.Column("revision", "版本", Optional.of(80)),
                        new ViewSchema.Column("name", "名称", Optional.of(180)),
                        new ViewSchema.Column("description", "检索摘要", Optional.of(320)),
                        new ViewSchema.Column("tombstone", "删除标记", Optional.of(90)),
                        new ViewSchema.Column("updatedAt", "写入时间", Optional.of(180))),
                ViewSelectionMode.SINGLE,
                List.of(restore));
    }

    private static ViewSchema.Table resourcesTable() {
        ViewAction remove = new ViewAction(
                "移除资源",
                "draft/resource/remove",
                Map.of(),
                Map.of("id", "draftId", "resourceId", "id"),
                new ExpectedRevisionBinding.RowField("draftRevision"),
                true);
        return new ViewSchema.Table(
                "draft-resources",
                "所选 Draft 资源",
                "draftResources",
                "id",
                List.of(
                        new ViewSchema.Column("id", "资源标识", Optional.of(180)),
                        new ViewSchema.Column("mediaType", "媒体类型", Optional.of(180)),
                        new ViewSchema.Column("digest", "SHA-256", Optional.of(300)),
                        new ViewSchema.Column("executable", "可执行", Optional.of(80))),
                ViewSelectionMode.SINGLE,
                List.of(remove));
    }

    private static ViewSchema.Form createDraftForm() {
        List<ViewField> fields = new java.util.ArrayList<>();
        fields.add(field("newDraft", "id", "Skill 标识", ViewFieldType.TEXT));
        fields.addAll(contentFields("newDraft"));
        ViewAction save = new ViewAction(
                "保存 Draft", "draft/save-content", Map.of(), Map.of(), new ExpectedRevisionBinding.None(), false);
        return new ViewSchema.Form("draft-create", "新建 Draft", fields, save);
    }

    private static ViewSchema.Form editDraftForm() {
        ViewAction save = new ViewAction(
                "保存 Draft",
                "draft/save-content",
                Map.of(),
                Map.of(),
                new ExpectedRevisionBinding.SourceRevision("draftEditor"),
                false,
                new ViewCommandBinding("id", new ViewBinding("draftEditor", "id")));
        return new ViewSchema.Form("draft-edit", "编辑所选 Draft", contentFields("draftEditor"), save);
    }

    private static ViewSchema.Form addResourceForm() {
        ViewField attachment = new ViewField(
                "attachment",
                "资源文件",
                ViewFieldType.ATTACHMENT,
                new ViewBinding("resourceDraft", "attachment"),
                Optional.empty(),
                ViewFieldValidation.attachment(
                        true,
                        new com.javaclaw.extension.spi.ViewAttachmentPolicy(
                                Set.of("text/*", "application/json", "application/octet-stream"),
                                SkillContracts.MAXIMUM_RESOURCE_BYTES)),
                List.of(),
                Optional.empty(),
                Optional.empty());
        List<ViewField> fields = List.of(
                field("resourceDraft", "resourceId", "资源标识", ViewFieldType.TEXT),
                attachment,
                new ViewField(
                        "executable",
                        "允许执行",
                        ViewFieldType.BOOLEAN,
                        new ViewBinding("resourceDraft", "executable"),
                        Optional.of("false"),
                        ViewFieldValidation.required(true),
                        List.of(),
                        Optional.empty(),
                        Optional.empty()));
        ViewAction add = new ViewAction(
                "添加资源",
                "draft/resource/add",
                Map.of(),
                Map.of(),
                new ExpectedRevisionBinding.SourceRevision("resourceDraft"),
                false,
                new ViewCommandBinding("id", new ViewBinding("resourceDraft", "id")));
        return new ViewSchema.Form("draft-resource-add", "为所选 Draft 添加资源", fields, add);
    }

    private static List<ViewField> contentFields(String source) {
        return List.of(
                field(source, "name", "名称", ViewFieldType.TEXT),
                field(source, "description", "检索摘要", ViewFieldType.MULTILINE),
                field(source, "instructions", "指令", ViewFieldType.MULTILINE));
    }

    private static ViewField field(String source, String name, String label, ViewFieldType type) {
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

    private static ViewSchema.Table table(
            String id, String title, String source, List<String> fields, List<ViewAction> actions) {
        return new ViewSchema.Table(
                id,
                title,
                source,
                "id",
                fields.stream()
                        .map(field -> new ViewSchema.Column(field, field, Optional.of(120)))
                        .toList(),
                ViewSelectionMode.SINGLE,
                actions);
    }

    private static ViewAction action(
            String label, String command, Map<String, String> rowArguments, String revisionField, boolean dangerous) {
        return new ViewAction(
                label, command, Map.of(), rowArguments, new ExpectedRevisionBinding.RowField(revisionField), dangerous);
    }

    private static ExtensionSchema schema(
            ExtensionPayloadCodec codec,
            String suffix,
            String title,
            Map<String, Object> properties,
            List<String> required) {
        String id = BuiltinExtensionIds.SKILL + "/" + suffix;
        return new ExtensionSchema(id, ContractSchemaFactory.document(codec, id, title, properties, required));
    }

    private static ToolDescriptor tool(
            long revision,
            String name,
            String description,
            CanonicalPayload input,
            CanonicalPayload output,
            ToolRisk risk) {
        return new ToolDescriptor(
                new ToolIdentity(BuiltinExtensionIds.SKILL, name, revision),
                description,
                input,
                output,
                risk,
                Set.of("skill", "instructions"));
    }

    private static Map<String, Object> executionResultProperties() {
        return Map.of(
                "exitCode", ContractSchemaFactory.integer(0),
                "output", ContractSchemaFactory.string(),
                "truncated", ContractSchemaFactory.bool(),
                "elapsedMillis", ContractSchemaFactory.integer(0));
    }

    private static Map<String, Object> resourceSchema() {
        return ContractSchemaFactory.object(
                Map.of(
                        "id", ContractSchemaFactory.string(),
                        "mediaType", ContractSchemaFactory.string(),
                        "digest", ContractSchemaFactory.digest(),
                        "executable", ContractSchemaFactory.bool()),
                List.of("id", "mediaType", "digest", "executable"));
    }

    private static Map<String, Object> draftProperties() {
        return Map.of(
                "id", ContractSchemaFactory.string(),
                "revision", ContractSchemaFactory.integer(1),
                "name", ContractSchemaFactory.string(),
                "description", ContractSchemaFactory.string(),
                "instructions", ContractSchemaFactory.string(),
                "resources", ContractSchemaFactory.array(resourceSchema()),
                "createdAt", ContractSchemaFactory.instant(),
                "updatedAt", ContractSchemaFactory.instant());
    }

    private static List<String> draftRequired() {
        return List.of("id", "revision", "name", "description", "instructions", "resources", "createdAt", "updatedAt");
    }

    private static Map<String, Object> publishedProperties() {
        return Map.ofEntries(
                Map.entry("id", ContractSchemaFactory.string()),
                Map.entry("revision", ContractSchemaFactory.integer(1)),
                Map.entry("draftRevision", ContractSchemaFactory.integer(1)),
                Map.entry("digest", ContractSchemaFactory.digest()),
                Map.entry("name", ContractSchemaFactory.string()),
                Map.entry("description", ContractSchemaFactory.string()),
                Map.entry("instructions", ContractSchemaFactory.string()),
                Map.entry("resources", ContractSchemaFactory.array(resourceSchema())),
                Map.entry("enabled", ContractSchemaFactory.bool()),
                Map.entry("publishedAt", ContractSchemaFactory.instant()),
                Map.entry("updatedAt", ContractSchemaFactory.instant()));
    }

    private static List<String> publishedRequired() {
        return List.of(
                "id",
                "revision",
                "draftRevision",
                "digest",
                "name",
                "description",
                "instructions",
                "resources",
                "enabled",
                "publishedAt",
                "updatedAt");
    }

    private static Map<String, Object> proposalProperties() {
        return Map.of(
                "id", ContractSchemaFactory.string(),
                "revision", ContractSchemaFactory.integer(1),
                "candidate", proposalCandidateSchema(),
                "state", ContractSchemaFactory.enumStrings("PENDING", "ADOPTED_AS_DRAFT", "REJECTED"),
                "draftId", nullable(ContractSchemaFactory.string()),
                "createdAt", ContractSchemaFactory.instant(),
                "updatedAt", ContractSchemaFactory.instant());
    }

    private static List<String> proposalRequired() {
        return List.of("id", "revision", "candidate", "state", "draftId", "createdAt", "updatedAt");
    }

    private static Map<String, Object> proposalCandidateSchema() {
        return ContractSchemaFactory.object(
                Map.of(
                        "id", ContractSchemaFactory.string(),
                        "draftId", ContractSchemaFactory.string(),
                        "name", ContractSchemaFactory.string(),
                        "description", ContractSchemaFactory.string(),
                        "instructions", ContractSchemaFactory.string()),
                List.of("id", "draftId", "name", "description", "instructions"));
    }

    private static Map<String, Object> catalogProperties() {
        return Map.of(
                "turnId", ContractSchemaFactory.string(),
                "skills", ContractSchemaFactory.array(summarySchema()),
                "digest", ContractSchemaFactory.digest(),
                "capturedAt", ContractSchemaFactory.instant());
    }

    private static List<String> catalogRequired() {
        return List.of("turnId", "skills", "digest", "capturedAt");
    }

    private static Map<String, Object> summarySchema() {
        return ContractSchemaFactory.object(
                Map.of(
                        "id", ContractSchemaFactory.string(),
                        "revision", ContractSchemaFactory.integer(1),
                        "digest", ContractSchemaFactory.digest(),
                        "name", ContractSchemaFactory.string(),
                        "description", ContractSchemaFactory.string()),
                List.of("id", "revision", "digest", "name", "description"));
    }

    private static Map<String, Object> nullable(Map<String, Object> schema) {
        return Map.of("anyOf", List.of(schema, Map.of("type", "null")));
    }
}
