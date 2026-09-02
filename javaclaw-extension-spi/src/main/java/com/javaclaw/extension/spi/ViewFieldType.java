package com.javaclaw.extension.spi;

/**
 * 平台拥有的非敏感表单字段类型。
 *
 * <p>ViewSchema 不表示 Secret。凭据必须由强类型核心页面通过 SealedSecret 和 Vault 专用 API 写入，OAuth 与 Browser 授权必须使用平台动作。
 */
public enum ViewFieldType {
    /** 单行文本。 */
    TEXT,
    /** 多行文本。 */
    MULTILINE,
    /** 十进制数值。 */
    NUMBER,
    /** 布尔值。 */
    BOOLEAN,
    /** 受限选项。 */
    CHOICE,
    /** 由平台选择本地文件并先上传为 Core Attachment 的输入。 */
    ATTACHMENT
}
