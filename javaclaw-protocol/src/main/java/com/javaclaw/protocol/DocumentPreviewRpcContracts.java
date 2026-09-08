package com.javaclaw.protocol;

import java.util.Objects;
import java.util.UUID;

import com.javaclaw.api.DocumentReference;

/** 只读文档内容及连接内预览句柄控制的 Protocol v3 契约。 */
public final class DocumentPreviewRpcContracts {
    /** 连接内快照失效通知，不携带正文或路径。 */
    public static final String INVALIDATED = "document/preview/invalidated";

    /**
     * 已失效快照的公开通知。
     *
     * @param handleId 当前连接内的快照 UUID
     * @param reasonCode EXPIRED、REVOKED、CLOSED 或 CORRUPT
     */
    public record Invalidated(String handleId, String reasonCode) {
        /** 校验通知身份和固定错误码。 */
        public Invalidated {
            handleId = UUID.fromString(handleId).toString();
            if (!java.util.Set.of("EXPIRED", "REVOKED", "CLOSED", "CORRUPT").contains(reasonCode)) {
                throw new IllegalArgumentException("未知预览失效原因");
            }
        }
    }
    /** 必须显式协商的稳定能力。 */
    public static final String CAPABILITY = "core.document-preview-v1";
    /** 创建有界不可变版本。 */
    public static final String RESOLVE = "document/preview/resolve";
    /** 读取版本中的原始字节。 */
    public static final String READ = "document/preview/readChunk";
    /** 在父版本同一权限范围解析相对资源。 */
    public static final String RESOURCE = "document/preview/resolveResource";
    /** 重新检查授权并延长闲置租约。 */
    public static final String RENEW = "document/preview/renew";
    /** 幂等释放连接内句柄。 */
    public static final String CLOSE = "document/preview/close";

    private DocumentPreviewRpcContracts() {}

    /** @param reference 来源引用；不接受绝对路径或客户端权限配置 */
    public record ResolvePayload(DocumentReference reference) {
        /** 校验来源存在。 */
        public ResolvePayload {
            Objects.requireNonNull(reference, "reference");
        }
    }

    /** @param handleId 当前连接拥有的预览 UUID */
    public record HandlePayload(String handleId) {
        /** 校验句柄格式，身份仍需由服务端验证。 */
        public HandlePayload {
            handleId = UUID.fromString(handleId).toString();
        }
    }

    /**
     * @param handleId 版本 UUID
     * @param offsetBytes 起始字节
     * @param maxBytes 最大字节数，1 至 256 KiB
     */
    public record ReadPayload(String handleId, long offsetBytes, int maxBytes) {
        /** 校验有界读取，禁止负偏移或大帧。 */
        public ReadPayload {
            handleId = UUID.fromString(handleId).toString();
            if (offsetBytes < 0 || maxBytes < 1 || maxBytes > 256 * 1024) {
                throw new IllegalArgumentException("预览读取必须是有界分块");
            }
        }
    }

    /**
     * @param parentHandleId 父版本 UUID
     * @param href 父文档中存在的相对链接，最大 4096 字符
     */
    public record ResourcePayload(String parentHandleId, String href) {
        /** 校验长度；相对路径和实际链接由服务端内容解析器验证。 */
        public ResourcePayload {
            parentHandleId = UUID.fromString(parentHandleId).toString();
            href = Objects.requireNonNull(href, "href");
            if (href.isBlank() || href.length() > 4096) {
                throw new IllegalArgumentException("相对资源引用无效");
            }
        }
    }

    /**
     * @param handleId 已释放 UUID
     * @param closed 总为 true，重复关闭同样成功
     */
    public record CloseResult(String handleId, boolean closed) {
        /** 校验关闭回执。 */
        public CloseResult {
            handleId = UUID.fromString(handleId).toString();
            if (!closed) {
                throw new IllegalArgumentException("关闭回执必须确认已释放");
            }
        }
    }
}
