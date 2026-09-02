package com.javaclaw.api;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Core Item 的强类型 payload 集合；业务领域 payload 不得加入此类型。 */
public final class CorePayloads {
    private CorePayloads() {}

    /**
     * 对话消息。
     *
     * @param role 消息角色
     * @param text UTF-8 正文，允许空字符串以承载纯附件消息
     * @param attachments 内容寻址附件
     * @param toolCallId Tool 消息关联的调用 ID；其他角色为空
     */
    public record Message(MessageRole role, String text, List<AttachmentRef> attachments, Optional<String> toolCallId)
            implements ItemPayload {
        /** 复制集合并校验角色约束。 */
        public Message {
            Objects.requireNonNull(role, "role");
            text = Objects.requireNonNull(text, "text");
            attachments = List.copyOf(attachments);
            toolCallId = Objects.requireNonNull(toolCallId, "toolCallId")
                    .map(String::strip)
                    .filter(value -> !value.isEmpty());
            if (role != MessageRole.TOOL && toolCallId.isPresent()) {
                throw new IllegalArgumentException("toolCallId is only valid for TOOL messages");
            }
        }
    }

    /**
     * 模型发起的工具调用。
     *
     * @param callId Turn 内唯一调用 ID
     * @param producerId 工具来源
     * @param toolName 冻结目录中的工具名称
     * @param toolRevision 冻结工具版本
     * @param arguments 规范化参数
     */
    public record ToolCall(
            String callId, String producerId, String toolName, long toolRevision, CanonicalPayload arguments)
            implements ItemPayload {
        /** 校验工具标识和版本。 */
        public ToolCall {
            callId = Preconditions.text(callId, "callId");
            producerId = Preconditions.text(producerId, "producerId");
            toolName = Preconditions.text(toolName, "toolName");
            toolRevision = Preconditions.positive(toolRevision, "toolRevision");
            Objects.requireNonNull(arguments, "arguments");
        }
    }

    /**
     * 工具执行结果。
     *
     * @param callId 对应调用 ID
     * @param success 是否成功
     * @param output 规范化输出
     * @param receipt 已提交副作用的凭据；纯查询为空
     */
    public record ToolResult(String callId, boolean success, CanonicalPayload output, Optional<EffectReceipt> receipt)
            implements ItemPayload {
        /** 校验调用 ID 与输出。 */
        public ToolResult {
            callId = Preconditions.text(callId, "callId");
            Objects.requireNonNull(output, "output");
            receipt = Objects.requireNonNull(receipt, "receipt");
        }
    }

    /**
     * 受控命令摘要。
     *
     * @param commandId 命令 ID
     * @param argv 不经 Shell 拼接的参数数组
     * @param workingDirectory Workspace 内规范路径
     * @param exitCode 终态退出码；运行中为空
     */
    public record Command(String commandId, List<String> argv, Path workingDirectory, Optional<Integer> exitCode)
            implements ItemPayload {
        /** 复制参数并规范化工作目录。 */
        public Command {
            commandId = Preconditions.text(commandId, "commandId");
            argv = List.copyOf(argv);
            if (argv.isEmpty() || argv.stream().anyMatch(value -> value == null || value.isBlank())) {
                throw new IllegalArgumentException("argv must contain non-blank values");
            }
            workingDirectory =
                    Objects.requireNonNull(workingDirectory, "workingDirectory").normalize();
            exitCode = Objects.requireNonNull(exitCode, "exitCode");
        }
    }

    /**
     * 文件变更摘要。
     *
     * @param relativePath Workspace 相对路径
     * @param operation create、update、move 或 delete
     * @param beforeDigest 修改前摘要；不存在时为空
     * @param afterDigest 修改后摘要；删除时为空
     */
    public record FileChange(
            Path relativePath, String operation, Optional<String> beforeDigest, Optional<String> afterDigest)
            implements ItemPayload {
        /** 校验路径不会逃逸 Workspace。 */
        public FileChange {
            relativePath = Objects.requireNonNull(relativePath, "relativePath").normalize();
            if (relativePath.isAbsolute() || relativePath.startsWith("..")) {
                throw new IllegalArgumentException("relativePath must stay inside the workspace");
            }
            operation = Preconditions.text(operation, "operation");
            beforeDigest = optionalText(beforeDigest, "beforeDigest");
            afterDigest = optionalText(afterDigest, "afterDigest");
        }
    }

    /**
     * 审批状态。
     *
     * @param requestId 审批 ID
     * @param toolName 申请调用的工具
     * @param risk 风险等级
     * @param state 强类型审批状态
     * @param reason 面向用户的简短原因
     */
    public record Approval(String requestId, String toolName, ToolRisk risk, ApprovalState state, String reason)
            implements ItemPayload {
        /** 校验审批字段。 */
        public Approval {
            requestId = Preconditions.text(requestId, "requestId");
            toolName = Preconditions.text(toolName, "toolName");
            Objects.requireNonNull(risk, "risk");
            Objects.requireNonNull(state, "state");
            reason = Preconditions.text(reason, "reason");
        }
    }

    /**
     * 用户输入交互。
     *
     * @param requestId 请求 ID
     * @param prompt 用户可见问题
     * @param secret 是否需要按 Secret 处理
     * @param answered 是否已经响应
     */
    public record Input(String requestId, String prompt, boolean secret, boolean answered) implements ItemPayload {
        /** 校验输入请求。 */
        public Input {
            requestId = Preconditions.text(requestId, "requestId");
            prompt = Preconditions.text(prompt, "prompt");
        }
    }

    /**
     * 子智能体生命周期摘要。
     *
     * @param childThreadId 子 Thread
     * @param state queued、running、completed、cancelled 或 failed
     * @param reservedInputTokens 从父预算预留的输入 token
     * @param reservedOutputTokens 从父预算预留的输出 token
     */
    public record Subagent(ThreadId childThreadId, String state, long reservedInputTokens, long reservedOutputTokens)
            implements ItemPayload {
        /** 校验子 Thread 与预留预算。 */
        public Subagent {
            Objects.requireNonNull(childThreadId, "childThreadId");
            state = Preconditions.text(state, "state");
            reservedInputTokens = Preconditions.nonNegative(reservedInputTokens, "reservedInputTokens");
            reservedOutputTokens = Preconditions.nonNegative(reservedOutputTokens, "reservedOutputTokens");
        }
    }

    /**
     * 上下文压缩结果。
     *
     * @param strategy summary 或 provider-native
     * @param consumedTokens 压缩前 token 数
     * @param summary 可读摘要；纯原生 opaque 压缩时可为空
     * @param providerStateDigest opaque state 摘要；无原生状态时为空
     */
    public record Compaction(String strategy, long consumedTokens, String summary, Optional<String> providerStateDigest)
            implements ItemPayload {
        /** 校验压缩元数据。 */
        public Compaction {
            strategy = Preconditions.text(strategy, "strategy");
            consumedTokens = Preconditions.nonNegative(consumedTokens, "consumedTokens");
            summary = Objects.requireNonNull(summary, "summary");
            providerStateDigest = optionalText(providerStateDigest, "providerStateDigest");
        }
    }

    /**
     * 结构化错误。
     *
     * @param code 稳定错误代码
     * @param message 脱敏后的用户可见说明
     * @param retryable 原请求是否可安全重试
     * @param occurredAt 发生时间
     * @param details 不包含 Secret 的诊断字段
     */
    public record Error(String code, String message, boolean retryable, Instant occurredAt, Map<String, String> details)
            implements ItemPayload {
        /** 校验并复制诊断数据。 */
        public Error {
            code = Preconditions.text(code, "code");
            message = Preconditions.text(message, "message");
            Objects.requireNonNull(occurredAt, "occurredAt");
            details = Map.copyOf(details);
        }
    }

    private static Optional<String> optionalText(Optional<String> value, String name) {
        return Objects.requireNonNull(value, name).map(entry -> Preconditions.text(entry, name));
    }
}
