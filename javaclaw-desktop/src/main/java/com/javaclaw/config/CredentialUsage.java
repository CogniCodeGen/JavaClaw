package com.javaclaw.config;

/** 在使用边界拒绝为保护存储而原样保留的未解密密文。 */
public final class CredentialUsage {

    private CredentialUsage() {
    }

    public static String requirePlaintext(String value) {
        return requirePlaintext(value, "API 密钥");
    }

    public static String requirePlaintext(String value, String kind) {
        if (value != null && value.stripLeading().startsWith("ENC(")) {
            throw new UnreadableCredentialException(kind);
        }
        return value;
    }

    public static final class UnreadableCredentialException extends IllegalStateException {
        private UnreadableCredentialException(String kind) {
            super(kind + "无法解密，请在设置中重新填写");
        }
    }
}
