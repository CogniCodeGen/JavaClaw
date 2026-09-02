package com.javaclaw.model;

import java.util.Arrays;
import java.util.Objects;

/**
 * 由 Vault 临时借出的可清零凭据缓冲区。
 *
 * <p>实现不变量：构造和读取都复制数组；关闭会清零内部所有权副本。实例关闭后禁止再次读取。
 */
public final class CredentialMaterial implements AutoCloseable {
    private char[] value;

    /**
     * 取得凭据副本所有权。
     *
     * @param value Secret 字符
     */
    public CredentialMaterial(char[] value) {
        this.value = Objects.requireNonNull(value, "value").clone();
        if (value.length == 0) {
            throw new IllegalArgumentException("credential must not be empty");
        }
    }

    /**
     * 返回供 Adapter 构造器使用的独立副本。
     *
     * @return Secret 副本
     */
    public synchronized char[] copy() {
        if (value == null) {
            throw new IllegalStateException("credential material is closed");
        }
        return value.clone();
    }

    /** 清零内部缓冲区。 */
    @Override
    public synchronized void close() {
        if (value != null) {
            Arrays.fill(value, '\0');
            value = null;
        }
    }
}
