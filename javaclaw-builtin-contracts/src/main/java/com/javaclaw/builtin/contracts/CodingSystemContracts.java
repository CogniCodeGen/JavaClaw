package com.javaclaw.builtin.contracts;

import java.nio.charset.Charset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 系统程序目录和执行契约；登记、发现和快照都不能授予执行或文件权限。 */
public final class CodingSystemContracts {
    private CodingSystemContracts() {}

    /** 程序入口来源。 */
    public enum Source {
        /** 发行代码列出的固定系统入口。 */
        SYSTEM,
        /** 用户在当前 Workspace 显式登记的入口。 */
        REGISTERED
    }

    /**
     * 用户登记的绝对程序位置；依赖目录在执行时仍必须属于有效文件读取授权。
     *
     * @param id 稳定名称，不允许使用保留的 system. 前缀
     * @param path 非空绝对程序路径，不搜索 PATH
     * @param readRoots 可为空的绝对依赖读取目录，不授予写入权
     * @param outputEncoding 标准输出和错误输出的编码名称
     */
    public record Registration(String id, String path, List<String> readRoots, String outputEncoding) {
        /** 规范化有界登记字段，不在契约层访问文件系统。 */
        public Registration {
            id = CodingContractValidation.id(id);
            if (id.startsWith("system.")) {
                throw new IllegalArgumentException("system. is a reserved executable identity prefix");
            }
            path = absolute(path);
            readRoots = readRoots == null ? List.of() : roots(readRoots);
            outputEncoding = outputEncoding == null ? "UTF-8" : encoding(outputEncoding);
        }
    }

    /**
     * Workspace 当前登记配置；版本为零表示尚未保存用户配置。
     *
     * @param revision 非负乐观版本
     * @param registrations 最多 100 项不可变登记列表
     */
    public record Registry(long revision, List<Registration> registrations) {
        /** 校验版本和唯一身份。 */
        public Registry {
            CodingContractValidation.nonNegative(revision, "revision");
            registrations = CodingSystemContracts.registrations(registrations);
        }
    }

    /**
     * 完整替换当前 Workspace 登记配置，仅影响之后新建的普通 Turn。
     *
     * @param registrations 最多 100 项登记，空列表清除用户登记
     */
    public record RegistryUpdate(List<Registration> registrations) {
        /** 拒绝重复登记身份。 */
        public RegistryUpdate {
            registrations = CodingSystemContracts.registrations(registrations);
        }
    }

    /**
     * 发现或冻结的程序入口；不可用项不会阻止普通对话。
     *
     * @param id 稳定程序身份
     * @param path 原始绝对位置
     * @param realPath 可用时的真实绝对位置，不可用时为空
     * @param sha256 可用时的完整文件摘要，不可用时为空
     * @param fileKey 可用时的文件系统对象身份，不可用时为空
     * @param source 系统预设或用户登记
     * @param readRoots 显式依赖目录，执行时重新校验授权
     * @param outputEncoding 输出编码
     * @param available 是否成功核验入口
     * @param unavailableReason 不可用时的稳定原因，可用时为空
     */
    public record Executable(
            String id,
            String path,
            Optional<String> realPath,
            Optional<String> sha256,
            Optional<String> fileKey,
            Source source,
            List<String> readRoots,
            String outputEncoding,
            boolean available,
            Optional<String> unavailableReason) {
        /** 固定目录事实，禁止缺失摘要的入口被标为可用。 */
        public Executable {
            id = CodingContractValidation.id(id);
            path = absolute(path);
            realPath = Objects.requireNonNull(realPath, "realPath").map(CodingSystemContracts::absolute);
            sha256 = Objects.requireNonNull(sha256, "sha256").map(CodingContractValidation::digest);
            fileKey = Objects.requireNonNull(fileKey, "fileKey")
                    .map(value -> CodingContractValidation.text(value, 512, "fileKey"));
            Objects.requireNonNull(source, "source");
            readRoots = roots(readRoots);
            outputEncoding = encoding(outputEncoding);
            unavailableReason = Objects.requireNonNull(unavailableReason, "unavailableReason")
                    .map(CodingContractValidation::id);
            if (available != (realPath.isPresent() && sha256.isPresent() && fileKey.isPresent())
                    || available == unavailableReason.isPresent()) {
                throw new IllegalArgumentException("executable availability and evidence disagree");
            }
        }
    }

    /**
     * 某个配置版本的实际系统目录。
     *
     * @param platform 服务器平台标识
     * @param registryRevision 非负用户配置版本
     * @param executables 最多 150 项程序及不可用原因
     */
    public record Catalog(String platform, long registryRevision, List<Executable> executables) {
        /** 校验目录上限和身份唯一性。 */
        public Catalog {
            platform = CodingContractValidation.id(platform);
            CodingContractValidation.nonNegative(registryRevision, "registryRevision");
            executables = List.copyOf(executables);
            if (executables.size() > 150
                    || executables.stream().map(Executable::id).distinct().count() != executables.size()) {
                throw new IllegalArgumentException("invalid executable catalog bound or identity");
            }
        }
    }

    /**
     * 不经 Shell 的固定入口调用。
     *
     * @param executableId 本 Turn 快照中的程序身份
     * @param arguments 最多 199 个原样传递的非空白参数；空参数须经显式 Shell 表达
     * @param workingDirectory 执行根内相对目录
     * @param timeoutSeconds 请求超时秒数，1 至 3600，仍受 Turn 和权限预算收窄
     * @param maxOutputBytes 输出保留字节数，1 至 1 MiB
     */
    public record CommandRun(
            String executableId,
            List<String> arguments,
            String workingDirectory,
            int timeoutSeconds,
            int maxOutputBytes) {
        /** 保留非空白参数的原文，不解释任何 Shell 字符。 */
        public CommandRun {
            executableId = CodingContractValidation.id(executableId);
            arguments = List.copyOf(arguments);
            if (arguments.size() > 199) {
                throw new IllegalArgumentException("too many system command arguments");
            }
            arguments.forEach(value -> {
                CodingContractValidation.content(value, 16_384, "argument");
                if (value.isBlank()) {
                    throw new IllegalArgumentException("system command argument must not be blank");
                }
            });
            if (arguments.stream().mapToLong(String::length).sum() > 65_536) {
                throw new IllegalArgumentException("system arguments exceed the total bound");
            }
            workingDirectory = CodingContractValidation.path(workingDirectory);
            limits(timeoutSeconds, maxOutputBytes);
        }
    }

    /**
     * 显式系统 Shell 调用；整段命令按一个已批准参数交给平台固定 Shell。
     *
     * @param command 非空命令正文，最多 4096 个 UTF-16 单元
     * @param workingDirectory 执行根内相对目录
     * @param timeoutSeconds 请求超时秒数，1 至 3600
     * @param maxOutputBytes 输出字节预算，1 至 1 MiB
     */
    public record ShellRun(String command, String workingDirectory, int timeoutSeconds, int maxOutputBytes) {
        /** 不拆词、不修剪、不补写用户命令正文。 */
        public ShellRun {
            command = CodingContractValidation.content(command, 4096, "command");
            if (command.isBlank()) {
                throw new IllegalArgumentException("shell command is blank");
            }
            workingDirectory = CodingContractValidation.path(workingDirectory);
            limits(timeoutSeconds, maxOutputBytes);
        }
    }

    private static List<Registration> registrations(List<Registration> source) {
        List<Registration> result = List.copyOf(source);
        if (result.size() > 100
                || result.stream().map(Registration::id).distinct().count() != result.size()) {
            throw new IllegalArgumentException("invalid registration bound or duplicate identity");
        }
        return result;
    }

    private static List<String> roots(List<String> source) {
        List<String> result =
                source.stream().map(CodingSystemContracts::absolute).distinct().toList();
        if (result.size() > 16) {
            throw new IllegalArgumentException("too many runtime dependency roots");
        }
        return result;
    }

    private static String absolute(String source) {
        String value = CodingContractValidation.content(source, 4096, "absolutePath");
        if (!value.startsWith("/") && !value.matches("[A-Za-z]:[\\\\/].*") && !value.startsWith("\\\\")) {
            throw new IllegalArgumentException("system program path must be absolute");
        }
        return value;
    }

    private static String encoding(String source) {
        return Charset.forName(CodingContractValidation.text(source, 80, "outputEncoding"))
                .name();
    }

    private static void limits(int seconds, int bytes) {
        CodingContractValidation.range(seconds, 1, 3600, "timeoutSeconds");
        CodingContractValidation.bytes(bytes);
    }
}
