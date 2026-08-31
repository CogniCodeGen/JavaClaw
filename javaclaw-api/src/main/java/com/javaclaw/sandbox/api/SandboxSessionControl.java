package com.javaclaw.sandbox.api;

import java.util.Base64;
import java.util.Objects;

/**
 * Authenticated supervisor-to-launcher control frame.
 *
 * @param nonce 父进程生成的非空白一次性关联值，用于核验管道消息来源
 * @param operation 非空控制操作
 * @param dataBase64 Base64 编码的字节载荷；无数据的控制帧可为 null
 * @param columns RESIZE 的列数，范围 20 到 1000；其他操作可为 null
 * @param rows RESIZE 的行数，范围 5 到 1000；其他操作可为 null
 * @param signal SIGNAL 的信号类型；其他操作可为 null
 */
public record SandboxSessionControl(
        String nonce, Operation operation, String dataBase64, Integer columns, Integer rows, SandboxSignal signal) {

    /** 继承管道允许的控制操作白名单，不承载任意原生调用。 */
    public enum Operation {
        STDIN,
        CLOSE_INPUT,
        RESIZE,
        SIGNAL,
        TERMINATE
    }

    /** 按操作校验载荷和终端尺寸，提前拒绝畸形 Base64 及缺失信号。 */
    public SandboxSessionControl {
        nonce = require(nonce, "nonce");
        operation = Objects.requireNonNull(operation, "operation");
        dataBase64 = dataBase64 == null ? "" : dataBase64;
        if (!dataBase64.isEmpty()) {
            Base64.getDecoder().decode(dataBase64);
        }
        if (operation == Operation.STDIN && dataBase64.isEmpty()) {
            throw new IllegalArgumentException("stdin frame requires data");
        }
        if (operation == Operation.RESIZE
                && (columns == null || rows == null || columns < 20 || columns > 1_000 || rows < 5 || rows > 1_000)) {
            throw new IllegalArgumentException("resize dimensions are invalid");
        }
        if (operation == Operation.SIGNAL && signal == null) {
            throw new IllegalArgumentException("signal frame requires a signal");
        }
    }

    /** 解码标准输入载荷；没有载荷时返回空字节数组。 */
    public byte[] data() {
        return dataBase64.isEmpty() ? new byte[0] : Base64.getDecoder().decode(dataBase64);
    }

    private static String require(String value, String name) {
        value = Objects.requireNonNull(value, name).strip();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
