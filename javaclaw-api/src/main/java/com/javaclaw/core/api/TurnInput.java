package com.javaclaw.core.api;

import java.util.Objects;

/** 用户输入的封闭类型；附件只允许内容摘要引用，不接受客户端文件路径。 */
public sealed interface TurnInput permits TurnInput.Text, TurnInput.AttachmentRef {
    /** 返回稳定输入类型标记，供显式协议映射使用。 */
    String type();

    /**
     * 用户提交的原始文本输入。
     *
     * @param text 非空文本，允许空字符串
     */
    record Text(String text) implements TurnInput {
        /** 要求文本引用非空；保留原文空白，不进行内容改写。 */
        public Text {
            text = Objects.requireNonNull(text, "text");
        }

        @Override
        public String type() {
            return "text";
        }
    }

    /**
     * A durable App Server attachment reference; clients never send local filesystem paths.
     *
     * @param sha256 内容的 SHA-256，小写十六进制 64 位字符串
     * @param mediaType 非空白 MIME 类型
     * @param displayName 展示文件名；null/空白使用 attachment，最长 255 字符且不能包含路径分隔符或 NUL
     */
    record AttachmentRef(String sha256, String mediaType, String displayName) implements TurnInput {
        /** 校验内容摘要和展示名，拒绝将路径片段伪装成附件名称；不读取本地文件。 */
        public AttachmentRef {
            sha256 = Objects.requireNonNull(sha256, "sha256").toLowerCase(java.util.Locale.ROOT);
            if (!sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("sha256 must be 64 lowercase hex characters");
            }
            mediaType = ThreadId.required(mediaType, "mediaType");
            displayName = displayName == null || displayName.isBlank() ? "attachment" : displayName.strip();
            if (displayName.length() > 255
                    || displayName.contains("/")
                    || displayName.contains("\\")
                    || displayName.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("displayName is invalid");
            }
        }

        @Override
        public String type() {
            return "attachment";
        }
    }
}
