package com.javaclaw.cli;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import com.javaclaw.sdk.JavaClawClient;
import com.javaclaw.sdk.ManagementDocuments;

/** SDK 管理用例的 CLI 映射；命令名不是可任意转发的 RPC 方法，未知操作明确拒绝。 */
final class CliManagementCommands {
    private CliManagementCommands() {}

    static Object execute(JavaClawClient client, JavaClawCli.Arguments args) throws Exception {
        String command = args.command;
        if (command.startsWith("memory-")
                || command.startsWith("knowledge-")
                || command.startsWith("skill-")
                || command.startsWith("learning-")) {
            return CliKnowledgeCommands.execute(client, args);
        }
        if (command.startsWith("plugin-")
                || command.startsWith("mcp-")
                || command.startsWith("site-")
                || command.startsWith("network-grant-")
                || command.startsWith("tool-authorization-")) {
            return CliExtensionCommands.execute(client, args);
        }
        String key = args.key();
        return switch (command) {
            case "workspace-read" ->
                client.workspaces().read(args.required("workspace")).join();
            case "workspace-update" ->
                client.workspaces()
                        .update(args.required("workspace"), args.required("name"), args.revision(), key)
                        .join();
            case "workspace-delete" -> {
                args.confirm();
                yield client.workspaces()
                        .delete(args.required("workspace"), args.revision(), key)
                        .join();
            }
            case "instructions-resolve" ->
                client.workspaces()
                        .resolveInstructions(args.required("workspace"))
                        .join();
            case "thread-resume" ->
                client.threads()
                        .resume(args.required("thread"), args.longValue("after", 0, 0, Long.MAX_VALUE))
                        .join();
            case "worktree-list" ->
                client.threads().worktrees(args.required("workspace")).join();
            case "worktree-patch" ->
                client.threads()
                        .exportWorktreePatch(args.required("thread"), args.revision())
                        .join();
            case "worktree-cleanup" -> {
                args.confirm();
                yield client.threads()
                        .cleanupWorktree(
                                args.required("thread"),
                                args.revision(),
                                args.booleanValue("discard-unmerged", false),
                                key)
                        .join();
            }
            case "thread-events" ->
                client.threads()
                        .events(
                                args.required("thread"),
                                args.longValue("after", 0, 0, Long.MAX_VALUE),
                                args.intValue("limit", 100, 1, 1000))
                        .join();
            case "thread-fork" ->
                client.threads()
                        .fork(args.required("thread"), args.required("through-turn"), args.value("title", ""), key)
                        .join();
            case "thread-update" ->
                client.threads()
                        .update(args.required("thread"), args.required("title"), args.revision(), key)
                        .join();
            case "thread-archive" ->
                client.threads()
                        .archive(args.required("thread"), args.revision(), key)
                        .join();
            case "thread-unarchive" ->
                client.threads()
                        .unarchive(args.required("thread"), args.revision(), key)
                        .join();
            case "thread-delete" -> {
                args.confirm();
                yield client.threads()
                        .delete(args.required("thread"), args.revision(), key)
                        .join();
            }
            case "thread-compact-start" -> {
                args.confirm();
                args.requirePersistent();
                client.threads().startCompaction(args.required("thread")).join();
                yield java.util.Map.of("started", true);
            }
            case "turn-await" ->
                client.threads()
                        .awaitTurn(
                                args.required("thread"),
                                args.required("turn"),
                                Duration.ofSeconds(args.longValue("wait-seconds", 600, 1, 86_400)))
                        .join();
            case "turn-steer" ->
                client.threads()
                        .steer(args.required("turn"), args.required("text"))
                        .join();
            case "turn-interrupt" ->
                client.threads().interrupt(args.required("turn")).join();
            case "plan-adopt" -> {
                args.confirm();
                args.requirePersistent();
                yield client.threads()
                        .adoptPlan(
                                args.required("thread"),
                                args.required("item"),
                                args.required("profile"),
                                args.revision(),
                                args.value("decisions", ""),
                                key)
                        .join();
            }
            case "approval-respond" -> {
                args.required("approved");
                yield client.threads()
                        .respondToApproval(args.required("approval"), args.booleanValue("approved", false))
                        .join();
            }
            case "input-respond" ->
                client.threads()
                        .respondToUserInput(
                                args.required("request"), args.value("text", ""), args.booleanValue("cancelled", false))
                        .join();
            case "model-list" -> client.models().listModels().join();
            case "tool-list" -> client.models().listTools().join();
            case "profile-read" ->
                client.models().readProfile(args.required("profile")).join();
            case "profile-put" -> {
                var value = ManagementDocuments.profile(CliFiles.document(args));
                yield client.models().putProfile(value, value.revision(), key).join();
            }
            case "profile-delete" -> {
                args.confirm();
                yield client.models()
                        .deleteProfile(args.required("profile"), args.revision(), key)
                        .join();
            }
            case "profile-prompt-preview" ->
                client.models()
                        .previewPrompt(args.required("profile"), args.required("workspace"))
                        .join();
            case "profile-prompt-optimize" -> {
                args.requirePersistent();
                yield client.models()
                        .optimizePrompt(
                                args.required("thread"),
                                args.required("profile"),
                                CliFiles.text(args),
                                args.revision(),
                                key)
                        .join();
            }
            case "provider-configure" ->
                client.models()
                        .configureProvider(
                                args.required("provider"),
                                ManagementDocuments.stringMap(CliFiles.document(args)),
                                args.revision(),
                                key)
                        .join();
            case "provider-credential-set" ->
                CliFiles.credential(
                        args, value -> client.models().setCredential(args.required("provider"), value, key));
            case "provider-credential-clear" -> {
                args.confirm();
                yield client.models()
                        .clearCredential(args.required("provider"), args.revision(), key)
                        .join();
            }
            case "sdd-import-preview" ->
                client.automations()
                        .importOpenSpec(Path.of(args.required("file")))
                        .join();
            case "sdd-import" -> {
                args.confirm();
                var draft = client.automations()
                        .importOpenSpec(Path.of(args.required("file")))
                        .join();
                yield client.automations()
                        .saveOpenSpecDraft(args.required("automation"), draft, args.revision(), key)
                        .join();
            }
            case "sdd-export" ->
                client.automations()
                        .exportOpenSpec(
                                args.required("automation"),
                                Path.of(args.required("output")),
                                args.booleanValue("replace", false))
                        .join();
            case "automation-read" ->
                client.automations().read(args.required("automation")).join();
            case "automation-definition" ->
                client.automations().readDefinition(args.required("automation")).join();
            case "automation-put" -> {
                var value = ManagementDocuments.automation(CliFiles.document(args));
                yield client.automations().put(value, value.revision(), key).join();
            }
            case "automation-delete" -> {
                args.confirm();
                yield client.automations()
                        .delete(args.required("automation"), args.revision(), key)
                        .join();
            }
            case "automation-start" -> {
                args.requirePersistent();
                yield client.automations()
                        .start(args.required("automation"), key)
                        .join();
            }
            case "automation-resume" -> {
                args.requirePersistent();
                yield client.automations()
                        .resume(args.required("automation"), key)
                        .join();
            }
            case "automation-interrupt" ->
                client.automations().interrupt(args.required("automation")).join();
            case "automation-items" ->
                client.automations().items(args.required("automation")).join();
            case "schedule-read" ->
                client.automations().readSchedule(args.required("schedule")).join();
            case "schedule-put" -> {
                var value = ManagementDocuments.schedule(CliFiles.document(args));
                yield client.automations()
                        .putSchedule(value, value.revision(), key)
                        .join();
            }
            case "schedule-enable", "schedule-disable" ->
                client.automations()
                        .setScheduleEnabled(
                                args.required("schedule"), command.equals("schedule-enable"), args.revision(), key)
                        .join();
            case "schedule-delete" -> {
                args.confirm();
                yield client.automations()
                        .deleteSchedule(args.required("schedule"), args.revision(), key)
                        .join();
            }
            case "schedule-trigger" -> {
                args.requirePersistent();
                yield client.automations()
                        .triggerSchedule(args.required("schedule"), key)
                        .join();
            }
            case "attachment-upload" -> CliFiles.attachment(client, args);
            case "attachment-upload-start" ->
                client.attachments()
                        .startUpload(
                                args.required("sha"),
                                args.value("media-type", "application/octet-stream"),
                                args.required("display-name"),
                                args.longValue("size", -1, 0, com.javaclaw.sdk.AttachmentClient.MAX_BYTES),
                                key)
                        .join();
            case "attachment-upload-chunk" ->
                client.attachments()
                        .appendChunk(
                                args.required("upload"),
                                args.longValue("offset", 0, 0, com.javaclaw.sdk.AttachmentClient.MAX_BYTES),
                                CliFiles.bytes(
                                        Path.of(args.required("file")), com.javaclaw.sdk.AttachmentClient.CHUNK_BYTES))
                        .join();
            case "attachment-upload-complete" ->
                client.attachments().completeUpload(args.required("upload")).join();
            case "attachment-read" ->
                client.attachments()
                        .read(
                                args.required("sha"),
                                args.longValue("offset", 0, 0, com.javaclaw.sdk.AttachmentClient.MAX_BYTES),
                                args.intValue("limit", 65536, 1, com.javaclaw.sdk.AttachmentClient.CHUNK_BYTES))
                        .join();
            case "attachment-download" ->
                client.attachments()
                        .download(
                                args.required("sha"),
                                Path.of(args.required("output")),
                                args.booleanValue("replace", false))
                        .join();
            case "attachment-release" ->
                client.attachments().release(args.required("sha")).join();
            case "config-update" ->
                client.administration()
                        .updateConfiguration(CliFiles.document(args))
                        .join();
            case "diagnostics-export" ->
                client.administration().exportDiagnostics(key).join();
            case "theme-set" -> {
                client.administration().setDesktopTheme(args.required("theme")).join();
                yield Map.of("saved", true);
            }
            default -> throw new IllegalArgumentException("unknown command: " + command);
        };
    }
}
