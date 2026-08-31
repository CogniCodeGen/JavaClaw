package com.javaclaw.cli;

import java.nio.file.Path;
import java.util.Map;

import com.javaclaw.sdk.JavaClawClient;
import com.javaclaw.sdk.ManagementDocuments;

/** Memory、Knowledge 与 Skill 的类型化命令；导入与审阅分离，不自动接受模型提案。 */
final class CliKnowledgeCommands {
    private CliKnowledgeCommands() {}

    static Object execute(JavaClawClient client, JavaClawCli.Arguments args) throws Exception {
        var knowledge = client.knowledge();
        String key = args.key();
        return switch (args.command) {
            case "memory-list" ->
                knowledge.listMemories(args.required("workspace")).join();
            case "memory-read" -> knowledge.readMemory(args.required("memory")).join();
            case "memory-history" ->
                knowledge.memoryHistory(args.required("memory")).join();
            case "memory-put" -> {
                var value = ManagementDocuments.memory(CliFiles.document(args));
                yield knowledge.saveMemory(value, value.revision(), key).join();
            }
            case "memory-delete" -> {
                args.confirm();
                yield knowledge
                        .deleteMemory(args.required("memory"), args.revision(), key)
                        .join();
            }
            case "memory-restore" -> {
                args.confirm();
                yield knowledge
                        .restoreMemory(
                                args.required("memory"),
                                args.longValue("source-revision", 0, 1, Long.MAX_VALUE),
                                args.revision(),
                                key)
                        .join();
            }
            case "memory-proposals" ->
                knowledge.memoryProposals(args.required("workspace")).join();
            case "memory-propose" -> {
                var value = ManagementDocuments.memory(CliFiles.document(args));
                yield knowledge
                        .proposeMemory(value, value.revision(), args.required("reason"), key)
                        .join();
            }
            case "memory-review" -> {
                args.required("accept");
                yield knowledge
                        .reviewMemoryProposal(
                                args.required("proposal"), args.booleanValue("accept", false), args.revision(), key)
                        .join();
            }
            case "knowledge-list" ->
                knowledge.listSources(args.required("workspace")).join();
            case "knowledge-read" ->
                knowledge.readSource(args.required("source")).join();
            case "knowledge-history" ->
                knowledge.sourceHistory(args.required("source")).join();
            case "knowledge-content" ->
                knowledge
                        .sourceContent(args.required("source"), args.revision())
                        .join();
            case "knowledge-import" ->
                knowledge
                        .importSource(
                                args.required("workspace"),
                                CliFiles.attachment(client, args),
                                args.required("name"),
                                key)
                        .join();
            case "knowledge-reindex" ->
                knowledge
                        .reindexSource(args.required("source"), args.revision(), key)
                        .join();
            case "knowledge-delete" -> {
                args.confirm();
                yield knowledge
                        .deleteSource(args.required("source"), args.revision(), key)
                        .join();
            }
            case "knowledge-search" ->
                knowledge
                        .search(args.required("workspace"), args.required("query"), args.intValue("limit", 10, 1, 100))
                        .join();
            case "skill-read" ->
                knowledge.readSkillContent(args.required("skill")).join();
            case "skill-history" ->
                knowledge.skillHistory(args.required("skill")).join();
            case "skill-resource" ->
                knowledge
                        .readSkillResource(args.required("skill"), args.revision(), args.required("path"))
                        .join();
            case "skill-import-preview" ->
                knowledge.importSkillDraft(Path.of(args.required("file"))).join();
            case "skill-install" -> {
                args.confirm();
                yield knowledge
                        .saveSkillContent(
                                knowledge
                                        .importSkillDraft(Path.of(args.required("file")))
                                        .join(),
                                key)
                        .join();
            }
            case "skill-export" -> {
                var content = knowledge.readSkillContent(args.required("skill")).join();
                knowledge
                        .exportSkillBundle(
                                content, Path.of(args.required("output")), args.booleanValue("replace", false))
                        .join();
                yield Map.of(
                        "output",
                        args.required("output"),
                        "revision",
                        content.skill().revision());
            }
            case "skill-enable", "skill-disable" ->
                knowledge
                        .setSkillEnabled(
                                args.required("skill"), args.command.equals("skill-enable"), args.revision(), key)
                        .join();
            case "skill-uninstall" -> {
                args.confirm();
                yield knowledge
                        .uninstallSkill(args.required("skill"), args.revision(), key)
                        .join();
            }
            case "skill-restore" -> {
                args.confirm();
                yield knowledge
                        .restoreSkill(
                                args.required("skill"),
                                args.longValue("source-revision", 0, 1, Long.MAX_VALUE),
                                args.revision(),
                                key)
                        .join();
            }
            case "skill-proposals" ->
                knowledge.skillProposals(args.required("workspace")).join();
            case "skill-propose" ->
                knowledge
                        .proposeSkill(
                                ManagementDocuments.skillProposal(CliFiles.document(args)),
                                args.required("reason"),
                                key)
                        .join();
            case "skill-review" -> {
                args.required("accept");
                yield knowledge
                        .reviewSkillProposal(
                                args.required("proposal"), args.booleanValue("accept", false), args.revision(), key)
                        .join();
            }
            case "learning-read" ->
                knowledge.learningSettings(args.required("workspace")).join();
            case "learning-put" -> {
                args.confirm();
                yield knowledge
                        .saveLearningSettings(
                                args.required("workspace"),
                                args.required("mode"),
                                args.booleanValue("memory-automatic", false),
                                args.revision(),
                                key)
                        .join();
            }
            default -> throw new IllegalArgumentException("unknown knowledge command: " + args.command);
        };
    }
}
