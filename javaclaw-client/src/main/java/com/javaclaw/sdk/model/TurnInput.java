package com.javaclaw.sdk.model;

/** Only text and server attachment references may cross the SDK boundary. */
public sealed interface TurnInput permits TurnInput.Text, TurnInput.Attachment {
    /**
     * SDK 文本输入；要求非空白，不支持用空文本启动 Turn。
     *
     * @param text 非空白用户输入文本，保留原文内容
     */
    record Text(String text) implements TurnInput {
        /** 拒绝 null 或空白输入，避免向服务端提交无内容的文本请求。 */
        public Text {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("text is blank");
            }
        }
    }

    /**
     * SDK 附件输入；只携带服务端内容摘要，不发送本地文件路径。
     *
     * @param sha256 内容 SHA-256，小写十六进制 64 位摘要
     * @param mediaType MIME 类型；null 使用 application/octet-stream
     * @param displayName 展示名；null 使用 attachment，服务端继续校验路径分隔符等约束
     */
    record Attachment(String sha256, String mediaType, String displayName) implements TurnInput {
        /** 校验 SHA-256 格式并填充可选 MIME/展示名；附件所有权由服务端验证。 */
        public Attachment {
            if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("invalid attachment sha256");
            }
            mediaType = mediaType == null ? "application/octet-stream" : mediaType;
            displayName = displayName == null ? "attachment" : displayName;
        }
    }
}
