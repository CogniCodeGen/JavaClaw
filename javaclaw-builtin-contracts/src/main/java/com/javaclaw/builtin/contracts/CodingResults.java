package com.javaclaw.builtin.contracts;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.TurnId;

/** Coding v1 的类型化执行事实；成功状态必须来自服务端回执，不由模型总结决定。 */
public final class CodingResults {
    /** 受控失败的 Item Schema。 */
    public static final String FAILURE_SCHEMA = "javaclaw.coding/failure/v1";

    /** 命令执行事实 Schema。 */
    public static final String COMMAND_SCHEMA = "javaclaw.coding/command-result/v1";
    /** 附加 Patch 与 Diff 详情的 Item Schema。 */
    public static final String PATCH_SCHEMA = "javaclaw.coding/patch-result/v1";
    /** 当前 Turn 终端状态的 Item Schema。 */
    public static final String TERMINAL_SCHEMA = "javaclaw.coding/terminal-result/v1";
    /** 依赖准备事实的 Item Schema。 */
    public static final String PREPARATION_SCHEMA = "javaclaw.coding/preparation-result/v1";
    /** 最近执行的只读列表 Schema；不改变旧命令或终端结果形状。 */
    public static final String EXECUTION_LIST_SCHEMA = "javaclaw.coding/execution-list/v1";

    private CodingResults() {}

    /**
     * 可观察执行的服务器摘要；这些定位字段本身不授予读取或执行权限。
     *
     * @param operationId 服务器执行资源标识
     * @param operation command_run、dependencies_prepare 或 terminal_open
     * @param turnId 权威所有者 Turn，不由客户端声明
     * @param state 当前状态，未知状态保留供文本回退
     * @param outputBytes 已持久保留的输出字节数，非负
     * @param exitCode 已知退出码，未退出或未知时为空
     */
    public record ExecutionSummary(
            String operationId,
            String operation,
            TurnId turnId,
            String state,
            long outputBytes,
            Optional<Integer> exitCode) {
        /** 固定执行身份和有界摘要，不将未知状态当作成功。 */
        public ExecutionSummary {
            operationId = CodingContractValidation.id(operationId);
            operation = CodingContractValidation.id(operation);
            Objects.requireNonNull(turnId, "turnId");
            state = CodingContractValidation.id(state);
            CodingContractValidation.nonNegative(outputBytes, "outputBytes");
            exitCode = Objects.requireNonNull(exitCode, "exitCode");
        }
    }

    /**
     * 当前已授权作用域内最近最多 100 个执行；查询不会启动项目命令。
     *
     * @param executions 按服务器最近优先排序的不可变摘要
     */
    public record ExecutionList(List<ExecutionSummary> executions) {
        /** 校验固定列表上限与唯一资源身份。 */
        public ExecutionList {
            executions = List.copyOf(executions);
            if (executions.size() > 100
                    || executions.stream()
                                    .map(ExecutionSummary::operationId)
                                    .distinct()
                                    .count()
                            != executions.size()) {
                throw new IllegalArgumentException("invalid execution list bound or identity");
            }
        }
    }

    /** 目录条目类型；OTHER 不允许客户端当普通文件使用。 */
    public enum EntryKind {
        /** 普通文件。 */
        FILE,
        /** 目录。 */
        DIRECTORY,
        /** 其他对象，不跟随符号链接。 */
        OTHER
    }

    /**
     * 有界目录条目。
     *
     * @param path executionRoot 内相对路径
     * @param kind 实际文件系统类型
     * @param sizeBytes 普通文件字节数；非文件为 0
     */
    public record FileEntry(String path, EntryKind kind, long sizeBytes) {
        /** 校验目录事实。 */
        public FileEntry {
            path = CodingContractValidation.path(path);
            Objects.requireNonNull(kind, "kind");
            CodingContractValidation.nonNegative(sizeBytes, "sizeBytes");
        }
    }

    /**
     * 单页目录读取结果。
     *
     * @param entries 按稳定名称排序的条目
     * @param nextName 下一页的 afterName；末页为空
     */
    public record FileListResult(List<FileEntry> entries, Optional<String> nextName) {
        /** 固定目录和游标。 */
        public FileListResult {
            entries = List.copyOf(entries);
            nextName = Objects.requireNonNull(nextName, "nextName");
        }
    }

    /**
     * 文件读取事实；字节游标不以 Java 字符数量计算。
     *
     * @param path 相对路径
     * @param text 解码的 UTF-8 正文；二进制时可为空
     * @param sha256 完整文件摘要
     * @param offsetBytes 实际起始字节
     * @param nextOffsetBytes 已消费后的字节游标
     * @param truncated 是否尚有内容未返回
     * @param binary 是否识别为二进制文件
     */
    public record FileReadResult(
            String path,
            String text,
            String sha256,
            long offsetBytes,
            long nextOffsetBytes,
            boolean truncated,
            boolean binary) {
        /** 校验文件摘要和单调游标。 */
        public FileReadResult {
            path = CodingContractValidation.path(path);
            text = Objects.requireNonNull(text, "text");
            sha256 = CodingContractValidation.digest(sha256);
            CodingContractValidation.nonNegative(offsetBytes, "offsetBytes");
            if (nextOffsetBytes < offsetBytes) {
                throw new IllegalArgumentException("file cursor moved backwards");
            }
        }
    }

    /**
     * 字面搜索命中。
     *
     * @param path 相对文件路径
     * @param lineNumber 从 1 开始的行号
     * @param text 已有界截断的命中行
     */
    public record FileMatch(String path, long lineNumber, String text) {
        /** 校验命中坐标。 */
        public FileMatch {
            path = CodingContractValidation.path(path);
            ContractValidation.revision(lineNumber);
            text = Objects.requireNonNull(text, "text");
        }
    }

    /**
     * 有界搜索结果。
     *
     * @param matches 匹配条目
     * @param truncated 达到扫描或结果上限
     * @param scannedBytes 实际扫描字节数
     */
    public record FileSearchResult(List<FileMatch> matches, boolean truncated, long scannedBytes) {
        /** 固定命中与预算事实。 */
        public FileSearchResult {
            matches = List.copyOf(matches);
            CodingContractValidation.nonNegative(scannedBytes, "scannedBytes");
        }
    }

    /**
     * 已执行文件变更及有界 Diff。
     *
     * @param path 修改前的相对路径，创建时为目标路径
     * @param operation create、update、move 或 delete
     * @param beforeSha256 修改前摘要；创建时为空
     * @param afterSha256 修改后摘要；删除时为空
     * @param moveTo 移动目标；非移动为空
     * @param diff 服务端计算的统一 Diff，不执行其中内容
     */
    public record PatchChange(
            String path,
            String operation,
            Optional<String> beforeSha256,
            Optional<String> afterSha256,
            Optional<String> moveTo,
            String diff) {
        /** 校验变更标识和摘要。 */
        public PatchChange {
            path = CodingContractValidation.path(path);
            if (!List.of("create", "update", "move", "delete").contains(operation)) {
                throw new IllegalArgumentException("unknown file change operation");
            }
            beforeSha256 = Objects.requireNonNull(beforeSha256, "beforeSha256").map(CodingContractValidation::digest);
            afterSha256 = Objects.requireNonNull(afterSha256, "afterSha256").map(CodingContractValidation::digest);
            moveTo = Objects.requireNonNull(moveTo, "moveTo").map(CodingContractValidation::path);
            diff = Objects.requireNonNull(diff, "diff");
        }
    }

    /**
     * Patch 最终事实；部分成功必须保留已执行变化，不能声称整体原子。
     *
     * @param changes 已持久化的实际变更
     * @param complete 是否所有请求均已完成
     * @param failureCode 不完整时的错误码，成功时为空
     * @param recoveryPaths 为保护并发编辑而保留的实际原文件目录，均为执行根内相对路径
     */
    public record PatchResult(
            List<PatchChange> changes, boolean complete, Optional<String> failureCode, List<String> recoveryPaths) {
        /** 校验完整状态与错误事实。 */
        public PatchResult {
            changes = List.copyOf(changes);
            failureCode = Objects.requireNonNull(failureCode, "failureCode").map(CodingContractValidation::id);
            recoveryPaths =
                    recoveryPaths.stream().map(CodingContractValidation::path).toList();
            if (recoveryPaths.size() > 200) {
                throw new IllegalArgumentException("patch recovery paths exceed the operation bound");
            }
            if (complete == failureCode.isPresent()) {
                throw new IllegalArgumentException("patch completion and failureCode disagree");
            }
        }

        /**
         * 创建不产生磁盘恢复目录的结果。
         *
         * @param changes 已确认变更
         * @param complete 是否完成
         * @param failureCode 失败类别
         */
        public PatchResult(List<PatchChange> changes, boolean complete, Optional<String> failureCode) {
            this(changes, complete, failureCode, List.of());
        }
    }

    /** 进程或终端的权威状态。 */
    public enum ProcessState {
        /** 进程仍在运行。 */
        RUNNING,
        /** 进程正常结束，包括非零退出码。 */
        COMPLETED,
        /** 平台执行失败。 */
        FAILED,
        /** 用户或权限取消。 */
        CANCELLED,
        /** 超出时间预算。 */
        TIMED_OUT
    }

    /**
     * 命令生命周期摘要。
     *
     * @param operationId 服务端命令记录标识
     * @param argv 实际执行的安全参数展示，不包含秘密
     * @param workingDirectory executionRoot 内相对目录
     * @param exitCode 已退出进程的退出码，运行中或未知时为空
     * @param state 权威执行状态
     * @param durationMillis 已经过的毫秒数
     */
    public record CommandSummary(
            String operationId,
            List<String> argv,
            String workingDirectory,
            Optional<Integer> exitCode,
            ProcessState state,
            long durationMillis) {
        /** 固定命令事实。 */
        public CommandSummary {
            operationId = CodingContractValidation.id(operationId);
            argv = List.copyOf(argv);
            if (argv.isEmpty()) {
                throw new IllegalArgumentException("command argv is empty");
            }
            workingDirectory = CodingContractValidation.path(workingDirectory);
            exitCode = Objects.requireNonNull(exitCode, "exitCode");
            Objects.requireNonNull(state, "state");
            CodingContractValidation.nonNegative(durationMillis, "durationMillis");
            if (state == ProcessState.RUNNING && exitCode.isPresent()) {
                throw new IllegalArgumentException("running command cannot have exitCode");
            }
        }
    }

    /**
     * 已脱敏的有界输出页；PTY 合并流放在 stdout。
     *
     * @param stdout 标准输出或 PTY 输出
     * @param stderr 标准错误；PTY 为空字符串
     * @param nextOffsetBytes 服务端字节游标，不以字符串长度计算
     * @param truncated 输出是否达到保留或本页边界
     */
    public record Output(String stdout, String stderr, long nextOffsetBytes, boolean truncated) {
        /** 固定输出与游标。 */
        public Output {
            stdout = Objects.requireNonNull(stdout, "stdout");
            stderr = Objects.requireNonNull(stderr, "stderr");
            CodingContractValidation.nonNegative(nextOffsetBytes, "nextOffsetBytes");
        }
    }

    /**
     * 命令执行事实。
     *
     * @param command 命令和生命周期
     * @param output 有界输出
     */
    public record CommandResult(CommandSummary command, Output output) {
        /** 校验命令与输出。 */
        public CommandResult {
            Objects.requireNonNull(command, "command");
            Objects.requireNonNull(output, "output");
        }
    }

    /**
     * 当前 Turn 的终端状态或控制回执。
     *
     * @param sessionId 服务端拥有的会话标识
     * @param state 终端权威状态
     * @param output 当前查询返回的输出页
     * @param exitCode 退出码，运行中或未知时为空
     */
    public record TerminalResult(String sessionId, ProcessState state, Output output, Optional<Integer> exitCode) {
        /** 校验终端状态。 */
        public TerminalResult {
            sessionId = CodingContractValidation.id(sessionId);
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(output, "output");
            exitCode = Objects.requireNonNull(exitCode, "exitCode");
            if (state == ProcessState.RUNNING && exitCode.isPresent()) {
                throw new IllegalArgumentException("running terminal cannot have exitCode");
            }
        }
    }

    /**
     * 依赖准备的实际结果；后续 build/test 不继承准备阶段联网授权。
     *
     * @param manager 实际依赖管理器
     * @param command 准备命令事实与日志
     * @param toolchains 使用的精确托管工具链
     */
    public record PreparationResult(
            CodingContracts.PackageManager manager,
            CommandResult command,
            List<CodingEnvironmentContracts.ToolchainRef> toolchains) {
        /** 固定准备结果。 */
        public PreparationResult {
            Objects.requireNonNull(manager, "manager");
            Objects.requireNonNull(command, "command");
            toolchains = List.copyOf(toolchains);
        }
    }

    /**
     * 定位已有服务端记录；标识本身不是读取授权。
     *
     * @param resourceId 服务器记录标识
     */
    public record ResourceRead(String resourceId) {
        /** 校验资源标识。 */
        public ResourceRead {
            resourceId = CodingContractValidation.id(resourceId);
        }
    }
    /**
     * 按字节游标读取服务端已授权的有界输出，不接受客户端声明的 Turn 权限。
     *
     * @param resourceId 服务端签发的资源标识
     * @param offsetBytes 非负字节游标
     * @param maxBytes 本页最大字节数，1 至 1 MiB
     */
    public record OutputRead(String resourceId, long offsetBytes, int maxBytes) {
        /** 校验资源身份与有界游标。 */
        public OutputRead {
            resourceId = CodingContractValidation.id(resourceId);
            CodingContractValidation.nonNegative(offsetBytes, "offsetBytes");
            CodingContractValidation.bytes(maxBytes);
        }
    }

    /**
     * 可供模型纠正参数或报告用户的受控失败，不包含原始异常或宿主 Secret。
     *
     * @param errorCode 稳定机器错误码
     * @param message 脱敏说明，最多 2000 字符
     * @param operationId 当前操作的服务端标识
     * @param retryable 当前失败是否允许经新的授权尝试重试
     */
    public record Failure(String errorCode, String message, String operationId, boolean retryable) {
        /** 校验公开错误边界。 */
        public Failure {
            errorCode = CodingContractValidation.id(errorCode);
            message = CodingContractValidation.text(message, 2000, "message");
            operationId = CodingContractValidation.id(operationId);
        }
    }
}
