package com.javaclaw.builtin.contracts;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** 受治理 JShell 内联脚本契约；源码是本次调用的输入，不携带运行时或权限选择。 */
public final class CodingScriptContracts {
    /** 单次脚本的 UTF-8 字节硬上限。 */
    public static final int MAX_SOURCE_BYTES = 65_536;

    private CodingScriptContracts() {}

    /**
     * 在冻结托管 JDK 中创建独立 JShell 并执行完整内联源码。
     *
     * @param source 非空 Java 片段，UTF-8 最多 65536 字节，不允许 NUL 或不成对代理字符
     * @param workingDirectory 相对 executionRoot 的目录；缺省或 null 使用点
     * @param timeoutSeconds 最大秒数，1 到 3600；缺省为 30，实际仍受权限和 Turn 预算限制
     * @param maxOutputBytes stdout/stderr 合计最大字节数，1 到 1048576；缺省为 65536
     */
    public record ScriptRun(String source, String workingDirectory, Integer timeoutSeconds, Integer maxOutputBytes) {
        /** 验证原始源码并补齐稳定缺省值；不裁剪或重写源码。 */
        public ScriptRun {
            source = validateSource(source);
            workingDirectory = CodingContractValidation.path(workingDirectory == null ? "." : workingDirectory);
            timeoutSeconds = timeoutSeconds == null ? 30 : timeoutSeconds;
            maxOutputBytes = maxOutputBytes == null ? 65_536 : maxOutputBytes;
            CodingContractValidation.range(timeoutSeconds, 1, 3600, "timeoutSeconds");
            CodingContractValidation.bytes(maxOutputBytes);
        }
    }

    private static String validateSource(String source) {
        Objects.requireNonNull(source, "source");
        if (source.isBlank() || source.indexOf('\0') >= 0 || source.length() > MAX_SOURCE_BYTES) {
            throw new IllegalArgumentException("source must contain bounded Java source without NUL");
        }
        for (int index = 0; index < source.length(); index++) {
            char current = source.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (++index >= source.length() || !Character.isLowSurrogate(source.charAt(index))) {
                    throw new IllegalArgumentException("source contains an unpaired surrogate");
                }
            } else if (Character.isLowSurrogate(current)) {
                throw new IllegalArgumentException("source contains an unpaired surrogate");
            }
        }
        if (source.getBytes(StandardCharsets.UTF_8).length > MAX_SOURCE_BYTES) {
            throw new IllegalArgumentException("source exceeds 65536 UTF-8 bytes");
        }
        return source;
    }
}
