package com.javaclaw.application.inference;

/** 本地推理管理用例保存 TLS 等敏感值时使用的加密端口。 */
public interface InferenceSecretPort {
    String encrypt(String plainText);
    String decrypt(String encryptedText);

    InferenceSecretPort PASSTHROUGH = new InferenceSecretPort() {
        @Override public String encrypt(String value) { return value == null ? "" : value; }
        @Override public String decrypt(String value) { return value == null ? "" : value; }
    };
}
