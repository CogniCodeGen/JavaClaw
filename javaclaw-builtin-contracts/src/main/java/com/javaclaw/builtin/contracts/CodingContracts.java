package com.javaclaw.builtin.contracts;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.SandboxSignal;

/** Coding v1 工具输入；路径均相对服务端冻结的 executionRoot，输入不携带授权。 */
public final class CodingContracts {
    /** Coding 扩展唯一来源。 */
    public static final String EXTENSION_ID = "com.javaclaw.coding";
    /** Coding 工具与业务 Schema 的首个不可变版本。 */
    public static final long REVISION = 1;

    private CodingContracts() {}

    /**
     * 列出一个目录；不递归跟随符号链接。
     *
     * @param path 相对目录，可用点表示 executionRoot
     * @param afterName 上页最后一个条目名，首批为空
     * @param maxEntries 最大条数，1 到 2000
     */
    public record FileList(String path, Optional<String> afterName, int maxEntries) {
        /** 校验路径和条数。 */
        public FileList {
            path = CodingContractValidation.path(path);
            afterName = Objects.requireNonNull(afterName, "afterName")
                    .map(value -> CodingContractValidation.text(value, 4096, "afterName"));
            CodingContractValidation.range(maxEntries, 1, 2000, "maxEntries");
        }
    }

    /**
     * 有界读取文件；摘要由服务端计算。
     *
     * @param path 相对文件路径
     * @param offsetBytes 起始字节游标，非负
     * @param maxBytes 最大返回字节数，1 到 1048576
     */
    public record FileRead(String path, long offsetBytes, int maxBytes) {
        /** 校验路径和输出边界。 */
        public FileRead {
            path = CodingContractValidation.path(path);
            CodingContractValidation.nonNegative(offsetBytes, "offsetBytes");
            CodingContractValidation.bytes(maxBytes);
        }
    }

    /**
     * 有界字面文本搜索，不把模型输入当正则表达式执行。
     *
     * @param path 搜索根相对路径
     * @param query 非空字面查询，最多 1000 字符
     * @param glob 相对路径过滤模式，最多 1000 字符
     * @param caseSensitive 是否区分大小写
     * @param maxMatches 最大命中数，1 到 1000
     * @param maxBytes 最大扫描字节数，1 到 1048576
     */
    public record FileSearch(
            String path, String query, String glob, boolean caseSensitive, int maxMatches, int maxBytes) {
        /** 校验搜索边界。 */
        public FileSearch {
            path = CodingContractValidation.path(path);
            query = CodingContractValidation.text(query, 1000, "query");
            glob = CodingContractValidation.text(glob, 1000, "glob");
            CodingContractValidation.range(maxMatches, 1, 1000, "maxMatches");
            CodingContractValidation.bytes(maxBytes);
        }
    }

    /**
     * 单文件条件变更；摘要为空只允许创建不存在的文件，不表示无条件覆盖。
     *
     * @param path 相对文件路径
     * @param expectedSha256 修改或删除前的完整 SHA-256；创建时为空
     * @param content 完整 UTF-8 新内容，可为空字符串；容器为空表示删除
     * @param moveTo 可选的相对移动目标，必须不存在；移动可同时改写内容
     */
    public record FileEdit(
            String path, Optional<String> expectedSha256, Optional<String> content, Optional<String> moveTo) {
        /** 校验创建、更新和删除契约；单文件内容最多 1048576 字符。 */
        public FileEdit {
            path = CodingContractValidation.path(path);
            expectedSha256 =
                    Objects.requireNonNull(expectedSha256, "expectedSha256").map(CodingContractValidation::digest);
            content = Objects.requireNonNull(content, "content")
                    .map(value -> CodingContractValidation.content(value, 1_048_576, "content"));
            moveTo = Objects.requireNonNull(moveTo, "moveTo").map(CodingContractValidation::path);
            if (expectedSha256.isEmpty() && content.isEmpty()) {
                throw new IllegalArgumentException("delete requires expectedSha256");
            }
            if (".".equals(path)) {
                throw new IllegalArgumentException("cannot replace executionRoot");
            }
            if (moveTo.isPresent()
                    && (expectedSha256.isEmpty()
                            || moveTo.get().equals(path)
                            || moveTo.get().equals("."))) {
                throw new IllegalArgumentException("move requires an existing source and a distinct file target");
            }
        }
    }

    /**
     * 条件 Patch；冲突预检不构成跨文件与数据库的原子事务。
     *
     * @param changes 1 到 100 个路径不重复的变更
     */
    public record ApplyPatch(List<FileEdit> changes) {
        /** 复制变更并拒绝重复路径和过大总正文。 */
        public ApplyPatch {
            changes = List.copyOf(changes);
            CodingContractValidation.range(changes.size(), 1, 100, "changes");
            HashSet<String> paths = new HashSet<>();
            long characters = 0;
            for (FileEdit change : changes) {
                if (!paths.add(change.path())) {
                    throw new IllegalArgumentException("duplicate patch path");
                }
                if (change.moveTo().isPresent() && !paths.add(change.moveTo().orElseThrow())) {
                    throw new IllegalArgumentException("duplicate patch destination");
                }
                characters += change.content().map(String::length).orElse(0);
            }
            if (characters > 4_194_304) {
                throw new IllegalArgumentException("patch content exceeds 4194304 characters");
            }
        }
    }

    /**
     * 当前 Turn 内的断网命令；服务端按权限、工具链和预算再次收窄。
     *
     * @param argv 1 到 200 个参数；首项是环境中注册的可执行标识，不查宿主 PATH，其余不作 Shell 拼接
     * @param workingDirectory 相对工作目录
     * @param timeoutSeconds 最长秒数，1 到 3600
     * @param maxOutputBytes 最大输出字节数，1 到 1048576
     */
    public record CommandRun(List<String> argv, String workingDirectory, int timeoutSeconds, int maxOutputBytes) {
        /** 校验命令参数和边界，沿用 Core 命令禁止空白参数的契约。 */
        public CommandRun {
            argv = List.copyOf(argv);
            CodingContractValidation.range(argv.size(), 1, 200, "argv");
            CodingContractValidation.text(argv.getFirst(), 4096, "executable");
            argv.forEach(value -> CodingContractValidation.text(value, 32768, "argument"));
            workingDirectory = CodingContractValidation.path(workingDirectory);
            CodingContractValidation.range(timeoutSeconds, 1, 3600, "timeoutSeconds");
            CodingContractValidation.bytes(maxOutputBytes);
        }
    }

    /**
     * 创建仅属于当前 Turn 的 PTY。
     *
     * @param command 断网命令与资源边界
     * @param columns 字符列数，20 到 1000
     * @param rows 字符行数，5 到 1000
     */
    public record TerminalOpen(CommandRun command, int columns, int rows) {
        /** 校验初始尺寸。 */
        public TerminalOpen {
            Objects.requireNonNull(command, "command");
            CodingContractValidation.dimensions(columns, rows);
        }
    }

    /**
     * 从服务端拥有的终端输出游标读取；ID 仅作定位，不授予访问权限。
     *
     * @param sessionId 服务端会话标识
     * @param offsetBytes 已读取字节游标，非负
     * @param maxBytes 单页最大字节数，1 到 1048576
     * @param waitMillis 最长等待毫秒数，0 到 10000
     */
    public record TerminalRead(String sessionId, long offsetBytes, int maxBytes, int waitMillis) {
        /** 校验会话和读取预算。 */
        public TerminalRead {
            sessionId = CodingContractValidation.id(sessionId);
            CodingContractValidation.nonNegative(offsetBytes, "offsetBytes");
            CodingContractValidation.bytes(maxBytes);
            CodingContractValidation.range(waitMillis, 0, 10000, "waitMillis");
        }
    }

    /**
     * 模型工具的 PTY 输入；v1 不向 Desktop 或 CLI 暴露人工输入 command。
     *
     * @param sessionId 当前 Turn 所有的会话标识
     * @param text 原样发送的 UTF-8 文本，最多 65536 字符
     * @param inputSequence 会话内从 1 开始的输入序号，重复序号不能重复写入
     */
    public record TerminalWrite(String sessionId, String text, long inputSequence) {
        /** 校验输入；文本不裁剪空白或控制字符。 */
        public TerminalWrite {
            sessionId = CodingContractValidation.id(sessionId);
            text = CodingContractValidation.content(text, 65536, "text");
            ContractValidation.revision(inputSequence);
        }
    }

    /**
     * 向当前 Turn 的 PTY 发送受控信号。
     *
     * @param sessionId 当前 Turn 的会话标识
     * @param signal 平台允许的信号，不可为空
     */
    public record TerminalSignal(String sessionId, SandboxSignal signal) {
        /** 校验会话和信号。 */
        public TerminalSignal {
            sessionId = CodingContractValidation.id(sessionId);
            Objects.requireNonNull(signal, "signal");
        }
    }

    /**
     * 调整当前 Turn PTY 的字符尺寸。
     *
     * @param sessionId 当前 Turn 的会话标识
     * @param columns 字符列数，20 到 1000
     * @param rows 字符行数，5 到 1000
     */
    public record TerminalResize(String sessionId, int columns, int rows) {
        /** 校验会话和尺寸。 */
        public TerminalResize {
            sessionId = CodingContractValidation.id(sessionId);
            CodingContractValidation.dimensions(columns, rows);
        }
    }

    /**
     * 关闭当前 Turn 所有的 PTY；服务端必须使重复关闭安全。
     *
     * @param sessionId 当前 Turn 的会话标识
     */
    public record TerminalClose(String sessionId) {
        /** 校验会话标识。 */
        public TerminalClose {
            sessionId = CodingContractValidation.id(sessionId);
        }
    }

    /** 支持受控依赖准备的包管理器；可执行命令由服务端构造。 */
    public enum PackageManager {
        /** Maven。 */
        MAVEN,
        /** Gradle。 */
        GRADLE,
        /** npm。 */
        NPM,
        /** pnpm。 */
        PNPM,
        /** pip。 */
        PIP
    }

    /**
     * 当前 Turn 的受治理依赖准备；不允许客户端通过管理 command 提交。
     *
     * @param manager 项目依赖管理器
     * @param workingDirectory 相对项目目录；源和代理授权来自服务端环境配置
     * @param targets Maven 或 Gradle 预热目标，最多 50 项；其他管理器为空
     */
    public record DependenciesPrepare(PackageManager manager, String workingDirectory, List<String> targets) {
        /** 校验管理器和工作目录。 */
        public DependenciesPrepare {
            Objects.requireNonNull(manager, "manager");
            workingDirectory = CodingContractValidation.path(workingDirectory);
            targets = List.copyOf(targets);
            CodingContractValidation.range(targets.size(), 0, 50, "targets");
            targets.forEach(value -> CodingContractValidation.text(value, 500, "target"));
            if (manager != PackageManager.MAVEN && manager != PackageManager.GRADLE && !targets.isEmpty()) {
                throw new IllegalArgumentException("targets are only supported for Maven and Gradle");
            }
        }
    }
}
