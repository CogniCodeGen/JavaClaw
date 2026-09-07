package com.javaclaw.client.extension;

import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.CoreSchemas;
import com.javaclaw.api.ItemEnvelope;
import com.javaclaw.builtin.contracts.CodingContracts;
import com.javaclaw.builtin.contracts.CodingResults;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ProtocolException;

/** Coding 和既有 Core 执行事实的纯文本投影；不执行终端转义序列、命令或 Diff。 */
public final class CodingTranscriptFormatter {
    private final CanonicalJson json;

    /**
     * 创建共享的文本格式器。
     *
     * @param json 严格协议 Codec
     */
    public CodingTranscriptFormatter(CanonicalJson json) {
        this.json = Objects.requireNonNull(json, "json");
    }

    /**
     * 将未知版本或无法类型化的回执保留为有界文本，不猜测 JSON 意图。
     *
     * @param item 已持久化的未知 Item 或 Core ToolResult
     * @return 最多 16384 字符的安全文本；不执行控制序列
     */
    public Fact fallback(ItemEnvelope item) {
        String body = item.payload().json();
        if (CoreSchemas.TOOL_RESULT.equals(item.schemaId())) {
            body = json.decode(item.payload(), CorePayloads.ToolResult.class)
                    .output()
                    .json();
        }
        if (body.length() > 16_384) {
            int end = Character.isHighSurrogate(body.charAt(16_383)) ? 16_383 : 16_384;
            body = body.substring(0, end) + "\n[显示已截断；原始 Item 保留完整 payload]";
        }
        return new Fact("执行记录 · " + item.schemaId(), body);
    }

    /**
     * 格式化已知执行事实；未知 schema 保持由调用方处理。
     *
     * @param item 已持久化的 Item
     * @return 可展示的标题与正文
     */
    public Optional<Fact> format(ItemEnvelope item) {
        Objects.requireNonNull(item, "item");
        return switch (item.schemaId()) {
            case CodingResults.FAILURE_SCHEMA ->
                Optional.of(failure(json.decode(item.payload(), CodingResults.Failure.class)));
            case CoreSchemas.COMMAND -> Optional.of(command(json.decode(item.payload(), CorePayloads.Command.class)));
            case CoreSchemas.FILE_CHANGE ->
                Optional.of(change(json.decode(item.payload(), CorePayloads.FileChange.class)));
            case CodingResults.COMMAND_SCHEMA ->
                Optional.of(command(json.decode(item.payload(), CodingResults.CommandResult.class)));
            case CodingResults.PATCH_SCHEMA ->
                Optional.of(patch(json.decode(item.payload(), CodingResults.PatchResult.class)));
            case CodingResults.TERMINAL_SCHEMA ->
                Optional.of(terminal(json.decode(item.payload(), CodingResults.TerminalResult.class)));
            case CodingResults.PREPARATION_SCHEMA ->
                Optional.of(preparation(json.decode(item.payload(), CodingResults.PreparationResult.class)));
            default -> Optional.empty();
        };
    }

    /**
     * 按服务端持久化的冻结工具身份解释 Core ToolResult，禁止按 JSON 外形猜测来源。
     *
     * @param item 当前结果或普通事实
     * @param associatedCall 同 Turn 中位于结果之前的唯一 Core ToolCall；缺少时不解释工具输出
     * @return 已知 Coding v1 输出的纯文本投影；身份或输出契约不匹配时为空
     */
    public Optional<Fact> format(ItemEnvelope item, Optional<ItemEnvelope> associatedCall) {
        Objects.requireNonNull(associatedCall, "associatedCall");
        if (!CoreSchemas.TOOL_RESULT.equals(item.schemaId())) {
            return format(item);
        }
        if (!"core".equals(item.producerId()) || associatedCall.isEmpty()) {
            return Optional.empty();
        }
        var callItem = associatedCall.orElseThrow();
        if (!CoreSchemas.TOOL_CALL.equals(callItem.schemaId())
                || !"core".equals(callItem.producerId())
                || !item.turnId().equals(callItem.turnId())
                || callItem.sequence() >= item.sequence()) {
            return Optional.empty();
        }
        try {
            var call = json.decode(callItem.payload(), CorePayloads.ToolCall.class);
            var result = json.decode(item.payload(), CorePayloads.ToolResult.class);
            if (!CodingContracts.EXTENSION_ID.equals(call.producerId())
                    || call.toolRevision() != CodingContracts.REVISION
                    || !call.callId().equals(result.callId())) {
                return Optional.empty();
            }
            return codingOutput(call.toolName(), result);
        } catch (IllegalArgumentException | ProtocolException invalidOutput) {
            // 版本不匹配或损坏输出继续由通用 Transcript 展示，不能伪装成一次成功执行。
            return Optional.empty();
        }
    }

    private Optional<Fact> codingOutput(String name, CorePayloads.ToolResult result) {
        try {
            return switch (name) {
                case "command_run" ->
                    Optional.of(command(json.decode(result.output(), CodingResults.CommandResult.class)));
                case "file_apply_patch" ->
                    Optional.of(patch(json.decode(result.output(), CodingResults.PatchResult.class)));
                case "dependencies_prepare" ->
                    Optional.of(preparation(json.decode(result.output(), CodingResults.PreparationResult.class)));
                case "terminal_open",
                        "terminal_read",
                        "terminal_write",
                        "terminal_signal",
                        "terminal_resize",
                        "terminal_close" ->
                    Optional.of(terminal(json.decode(result.output(), CodingResults.TerminalResult.class)));
                case "file_list", "file_read", "file_search" ->
                    result.success()
                            ? Optional.empty()
                            : Optional.of(failure(json.decode(result.output(), CodingResults.Failure.class)));
                default -> Optional.empty();
            };
        } catch (IllegalArgumentException | ProtocolException invalidSuccess) {
            if (result.success()) {
                return Optional.empty();
            }
            return Optional.of(failure(json.decode(result.output(), CodingResults.Failure.class)));
        }
    }

    private static Fact failure(CodingResults.Failure value) {
        return new Fact("执行失败 · " + value.errorCode(), value.message() + "\n操作：" + value.operationId());
    }

    private static Fact command(CorePayloads.Command value) {
        return new Fact(
                "命令 · " + value.commandId(),
                arguments(value.argv()) + "\n目录：" + value.workingDirectory() + "\n退出码："
                        + value.exitCode().map(Object::toString).orElse("运行中"));
    }

    private static Fact change(CorePayloads.FileChange value) {
        return new Fact(
                "文件 · " + value.operation(),
                value.relativePath() + "\n" + value.beforeDigest().orElse("新建") + " → "
                        + value.afterDigest().orElse("已删除"));
    }

    private static Fact command(CodingResults.CommandResult value) {
        CodingResults.CommandSummary command = value.command();
        return new Fact(
                "命令 · " + command.state(),
                arguments(command.argv()) + "\n目录："
                        + command.workingDirectory() + " · 耗时：" + command.durationMillis() + " ms"
                        + command.exitCode().map(code -> " · 退出码：" + code).orElse("") + "\n" + output(value.output()));
    }

    private static Fact patch(CodingResults.PatchResult value) {
        String body = value.changes().stream()
                .map(change -> change.operation() + " " + change.path()
                        + change.moveTo().map(target -> " → " + target).orElse("") + "\n" + change.diff())
                .collect(Collectors.joining("\n"));
        return new Fact(
                value.complete() ? "文件修改" : "文件修改 · 未完成",
                body
                        + value.failureCode().map(code -> "\n错误：" + code).orElse("")
                        + (value.recoveryPaths().isEmpty()
                                ? ""
                                : "\n保留原文件的恢复目录：\n" + String.join("\n", value.recoveryPaths())));
    }

    private static Fact terminal(CodingResults.TerminalResult value) {
        return new Fact(
                "终端 · " + value.sessionId() + " · " + value.state(),
                output(value.output())
                        + value.exitCode().map(code -> "\n退出码：" + code).orElse(""));
    }

    private static Fact preparation(CodingResults.PreparationResult value) {
        Fact command = command(value.command());
        return new Fact("依赖准备 · " + value.manager(), command.body());
    }

    private static String arguments(java.util.List<String> argv) {
        return argv.stream()
                .map(value -> value.chars().anyMatch(Character::isWhitespace)
                        ? "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
                        : value)
                .collect(Collectors.joining(" "));
    }

    private static String output(CodingResults.Output value) {
        return value.stdout()
                + (value.stderr().isEmpty() ? "" : "\nstderr:\n" + value.stderr())
                + (value.truncated() ? "\n[输出已截断；下一字节游标 " + value.nextOffsetBytes() + "]" : "");
    }

    /**
     * 可访问的执行事实文本；控制字符只作可见占位，避免日志操纵真实终端。
     *
     * @param title 简明标题
     * @param body 命令、Diff 或输出正文
     */
    public record Fact(String title, String body) {
        /** 保留换行与制表符，其余控制字符替换为可见符号。 */
        public Fact {
            title = sanitize(Objects.requireNonNull(title, "title"));
            body = sanitize(Objects.requireNonNull(body, "body"));
        }

        private static String sanitize(String text) {
            return text.replaceAll("[\\p{Cc}&&[^\\n\\t]]", "�");
        }
    }
}
