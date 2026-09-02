package com.javaclaw.api;

import java.util.Objects;
import java.util.Optional;

/**
 * 单个项目约定文件的脱敏解析结果；不包含文件正文或绝对路径。
 *
 * @param scope 来源层级
 * @param relativePath 相对受管根的正斜杠路径
 * @param digest 实际纳入 Prompt 的 UTF-8 字节 SHA-256；读取失败时为空
 * @param byteCount 文件观测字节数
 * @param includedBytes 实际纳入 Prompt 的字节数
 * @param truncated 是否因层级总限额被截断
 * @param errorCode 脱敏错误代码；成功时为空
 */
public record InstructionSourceResolution(
        InstructionScope scope,
        String relativePath,
        Optional<String> digest,
        long byteCount,
        long includedBytes,
        boolean truncated,
        Optional<String> errorCode) {
    /** 校验路径、摘要、字节数和错误状态的一致性。 */
    public InstructionSourceResolution {
        Objects.requireNonNull(scope, "scope");
        relativePath = relativePath(relativePath);
        digest = Objects.requireNonNull(digest, "digest").map(value -> Preconditions.digest(value, "digest"));
        errorCode = Objects.requireNonNull(errorCode, "errorCode")
                .map(value -> Preconditions.identifier(value, "errorCode"));
        if (byteCount < 0 || includedBytes < 0 || includedBytes > byteCount) {
            throw new IllegalArgumentException("instruction byte counts are inconsistent");
        }
        if (errorCode.isPresent() == digest.isPresent()) {
            throw new IllegalArgumentException("instruction source must contain either digest or errorCode");
        }
        if (errorCode.isPresent() && (includedBytes != 0 || truncated)) {
            throw new IllegalArgumentException("failed instruction source cannot contain effective bytes");
        }
        if (truncated && includedBytes >= byteCount) {
            throw new IllegalArgumentException("truncated instruction source must omit bytes");
        }
    }

    private static String relativePath(String value) {
        String checked = Preconditions.text(value, "relativePath").replace('\\', '/');
        if (checked.startsWith("/") || checked.startsWith("../") || checked.equals("..") || checked.contains("/../")) {
            throw new IllegalArgumentException("instruction path must be relative");
        }
        return checked;
    }
}
