package com.javaclaw.builtin.contracts;

import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 本地文件系统工具的独立 v1 契约；不修改既有文本 Patch 的 Schema 或语义。 */
public final class CodingFileSystemContracts {
    /** 新文件系统变更回执的独立 Schema。 */
    public static final String RESULT_SCHEMA = "javaclaw.coding/filesystem-result/v1";
    /** 直接读写的原始字节上限，不按 Base64 字符数计量。 */
    public static final int MAX_CONTENT_BYTES = 1_048_576;

    private CodingFileSystemContracts() {}

    /** 写入正文编码。 */
    public enum Encoding {
        /** UTF-8 文本。 */
        UTF8,
        /** 标准 Base64 编码的任意原始字节。 */
        BASE64
    }

    /** 由原始字节验证的内容种类；不通过是否生成 Diff 推测。 */
    public enum ContentKind {
        /** before 与 after 均可严格解码为不含 NUL 的 UTF-8。 */
        TEXT,
        /** before 或 after 含二进制字节，原始内容保持不变。 */
        BINARY,
        /** 目录对象，没有文件内容摘要或大小。 */
        DIRECTORY
    }

    /** @param path executionRoot 内相对路径，点表示根；不可空 */
    public record FileStat(String path) {
        /** 校验相对路径。 */
        public FileStat {
            path = CodingContractValidation.path(path);
        }
    }

    /** @param entry 文件或目录元数据；不存在时为空，权限错误不能转换为不存在 */
    public record FileStatResult(Optional<CodingResults.FileEntry> entry) {
        /** 固定可空语义。 */
        public FileStatResult {
            entry = Objects.requireNonNull(entry, "entry");
        }
    }

    /**
     * @param path 普通文件相对路径，不可空
     * @param offsetBytes 非负字节偏移
     * @param maxBytes 本页原始字节上限，1 至 1 MiB
     */
    public record FileReadBinary(String path, long offsetBytes, int maxBytes) {
        /** 校验字节页边界。 */
        public FileReadBinary {
            path = filePath(path);
            CodingContractValidation.nonNegative(offsetBytes, "offsetBytes");
            CodingContractValidation.bytes(maxBytes);
        }
    }

    /**
     * @param path 普通文件相对路径
     * @param contentBase64 本页原始字节的标准 Base64，不可空
     * @param sha256 完整文件摘要，不是本页摘要
     * @param sizeBytes 完整文件大小，字节
     * @param offsetBytes 请求的字节偏移
     * @param nextOffsetBytes 已返回字节后的游标
     * @param truncated 是否仍有未返回内容
     */
    public record FileReadBinaryResult(
            String path,
            String contentBase64,
            String sha256,
            long sizeBytes,
            long offsetBytes,
            long nextOffsetBytes,
            boolean truncated) {
        /** 校验原始字节页和单调游标。 */
        public FileReadBinaryResult {
            path = filePath(path);
            sha256 = CodingContractValidation.digest(sha256);
            byte[] content = decode(contentBase64, Encoding.BASE64);
            CodingContractValidation.nonNegative(sizeBytes, "sizeBytes");
            CodingContractValidation.nonNegative(offsetBytes, "offsetBytes");
            if (nextOffsetBytes != offsetBytes + content.length
                    || content.length > Math.max(0, sizeBytes - offsetBytes)
                    || truncated != (nextOffsetBytes < sizeBytes)) {
                throw new IllegalArgumentException("invalid binary page cursor");
            }
        }
    }

    /**
     * @param path 普通文件相对路径
     * @param content 按 encoding 解释的完整正文；可为空字符串，不可空
     * @param encoding UTF8 或 BASE64
     * @param expectedSha256 旧摘要；为空时只允许创建不存在文件
     */
    public record FileWrite(String path, String content, Encoding encoding, Optional<String> expectedSha256) {
        /** 拒绝超过 1 MiB 的原始正文及无条件覆盖。 */
        public FileWrite {
            path = filePath(path);
            decode(content, encoding);
            expectedSha256 =
                    Objects.requireNonNull(expectedSha256, "expectedSha256").map(CodingContractValidation::digest);
        }

        /** @return 经上限校验的新字节数组；调用方拥有数组 */
        public byte[] bytes() {
            return decode(content, encoding);
        }
    }

    /**
     * @param path 普通源文件相对路径
     * @param destination 必须不存在的目标文件相对路径
     * @param expectedSha256 源文件完整摘要
     */
    public record FileTransfer(String path, String destination, String expectedSha256) {
        /** 复制和移动均不允许覆盖目标或操作同一路径。 */
        public FileTransfer {
            path = filePath(path);
            destination = filePath(destination);
            expectedSha256 = CodingContractValidation.digest(expectedSha256);
            if (path.equals(destination)) {
                throw new IllegalArgumentException("source and destination must differ");
            }
        }
    }

    /**
     * @param path 普通文件相对路径；不可空
     * @param expectedSha256 删除前完整摘要，不可空
     */
    public record FileDelete(String path, String expectedSha256) {
        /** 删除仍需服务端检查 allowDelete。 */
        public FileDelete {
            path = filePath(path);
            expectedSha256 = CodingContractValidation.digest(expectedSha256);
        }
    }

    /**
     * @param path 目录相对路径，不可空
     * @param parents 是否允许逐级创建缺失的父目录
     */
    public record FileMkdir(String path, boolean parents) {
        /** 已存在普通目录可返回无变化；根不接受修改。 */
        public FileMkdir {
            path = filePath(path);
        }
    }

    /** @param path 待删除空目录相对路径，不可空；根不可删除 */
    public record FileRmdir(String path) {
        /** 不提供递归开关。 */
        public FileRmdir {
            path = filePath(path);
        }
    }

    /**
     * @param path 变更的相对路径
     * @param kind TEXT、BINARY 或 DIRECTORY，由服务端对实际快照判定
     * @param operation create、update、delete；移动以两项真实变化表示
     * @param beforeSha256 原文件摘要，目录及创建为空
     * @param afterSha256 新文件摘要，目录及删除为空
     * @param beforeSizeBytes 原文件大小，字节；目录及创建为空
     * @param afterSizeBytes 新文件大小，字节；目录及删除为空
     * @param textDiff 可选有界 UTF-8 Diff；二进制和目录为空
     */
    public record FileSystemChange(
            String path,
            ContentKind kind,
            String operation,
            Optional<String> beforeSha256,
            Optional<String> afterSha256,
            Optional<Long> beforeSizeBytes,
            Optional<Long> afterSizeBytes,
            Optional<String> textDiff) {
        /** 拒绝目录伪造文件摘要或二进制大小。 */
        public FileSystemChange {
            path = filePath(path);
            Objects.requireNonNull(kind, "kind");
            if (!List.of("create", "update", "delete").contains(operation)) {
                throw new IllegalArgumentException("invalid filesystem change kind or operation");
            }
            beforeSha256 = Objects.requireNonNull(beforeSha256, "beforeSha256").map(CodingContractValidation::digest);
            afterSha256 = Objects.requireNonNull(afterSha256, "afterSha256").map(CodingContractValidation::digest);
            beforeSizeBytes = size(beforeSizeBytes);
            afterSizeBytes = size(afterSizeBytes);
            textDiff = Objects.requireNonNull(textDiff, "textDiff");
            if (kind == ContentKind.DIRECTORY
                    && (operation.equals("update")
                            || beforeSha256.isPresent()
                            || afterSha256.isPresent()
                            || beforeSizeBytes.isPresent()
                            || afterSizeBytes.isPresent()
                            || textDiff.isPresent())) {
                throw new IllegalArgumentException("directory changes have no file content");
            }
            validateFileChange(kind, operation, beforeSha256, afterSha256, beforeSizeBytes, afterSizeBytes);
            textDiff.ifPresent(value -> CodingContractValidation.content(value, 65_536, "textDiff"));
        }
    }

    /**
     * @param operationId 服务端操作身份
     * @param changes 已确认的真实变化，包括部分完成
     * @param complete 操作是否完整完成
     * @param failureCode 未完成原因；完整成功时为空
     * @param recoveryPaths 保存原 inode 的相对恢复目录，不授予访问权限
     */
    public record FileSystemResult(
            String operationId,
            List<FileSystemChange> changes,
            boolean complete,
            Optional<String> failureCode,
            List<String> recoveryPaths) {
        /** 固定回执；不能把失败编码与成功状态混用。 */
        public FileSystemResult {
            operationId = CodingContractValidation.id(operationId);
            changes = List.copyOf(changes);
            failureCode = Objects.requireNonNull(failureCode, "failureCode");
            recoveryPaths = List.copyOf(recoveryPaths);
            if (changes.size() > 400 || recoveryPaths.size() > 200 || complete == failureCode.isPresent()) {
                throw new IllegalArgumentException("invalid filesystem result");
            }
            recoveryPaths.forEach(CodingContractValidation::path);
        }
    }

    private static Optional<Long> size(Optional<Long> value) {
        Objects.requireNonNull(value, "size").ifPresent(size -> CodingContractValidation.nonNegative(size, "size"));
        return value;
    }

    private static String filePath(String value) {
        String path = CodingContractValidation.path(value);
        if (path.equals(".")) {
            throw new IllegalArgumentException("cannot modify or read executionRoot as a file");
        }
        return path;
    }

    private static byte[] decode(String content, Encoding encoding) {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(encoding, "encoding");
        if (content.length() > 1_398_104) {
            throw new IllegalArgumentException("encoded content exceeds limit");
        }
        byte[] bytes =
                encoding == Encoding.UTF8 ? utf8(content) : Base64.getDecoder().decode(content);
        if (bytes.length > MAX_CONTENT_BYTES
                || encoding == Encoding.BASE64
                        && !Base64.getEncoder().encodeToString(bytes).equals(content)) {
            throw new IllegalArgumentException("content exceeds byte limit or is not canonical Base64");
        }
        return bytes;
    }

    private static byte[] utf8(String value) {
        try {
            var encoded = StandardCharsets.UTF_8
                    .newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException invalid) {
            throw new IllegalArgumentException("UTF8 content has an unpaired surrogate", invalid);
        }
    }

    private static void validateFileChange(
            ContentKind kind,
            String operation,
            Optional<String> before,
            Optional<String> after,
            Optional<Long> beforeSize,
            Optional<Long> afterSize) {
        if (kind != ContentKind.DIRECTORY
                && (before.isPresent() != beforeSize.isPresent()
                        || after.isPresent() != afterSize.isPresent()
                        || before.isPresent() != !operation.equals("create")
                        || after.isPresent() != !operation.equals("delete"))) {
            throw new IllegalArgumentException("file change content does not match operation");
        }
    }
}
