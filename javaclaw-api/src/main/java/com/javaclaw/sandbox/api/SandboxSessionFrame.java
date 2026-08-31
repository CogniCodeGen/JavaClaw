package com.javaclaw.sandbox.api;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

/**
 * Authenticated launcher-to-supervisor frame for a long-lived sandbox process.
 *
 * @param nonce 父进程生成的非空白一次性关联值，用于核验管道消息来源
 * @param kind 非空输出帧类型
 * @param dataBase64 Base64 编码的字节载荷；无数据的控制帧可为 null
 * @param exitCode 仅 EXIT 帧携带的退出码；其他帧必须为 null
 * @param truncated 输出是否因大小上限而截断
 * @param detail 后端或错误详情；null 归一为空字符串，ERROR 不允许空白
 */
public record SandboxSessionFrame(
        String nonce, Kind kind, String dataBase64, Integer exitCode, boolean truncated, String detail) {

    /** 输出帧生命周期；标准输出和标准错误保持独立通道。 */
    public enum Kind {
        READY,
        STDOUT,
        STDERR,
        EXIT,
        ERROR
    }

    /** 按帧类型校验载荷、退出码和错误详情，拒绝将非终态帧伪装成进程退出。 */
    public SandboxSessionFrame {
        nonce = require(nonce, "nonce");
        kind = Objects.requireNonNull(kind, "kind");
        dataBase64 = dataBase64 == null ? "" : dataBase64;
        detail = detail == null ? "" : detail;
        if ((kind == Kind.STDOUT || kind == Kind.STDERR) && dataBase64.isEmpty()) {
            throw new IllegalArgumentException("stream frame requires data");
        }
        if (kind == Kind.EXIT && exitCode == null) {
            throw new IllegalArgumentException("exit frame requires exitCode");
        }
        if (kind != Kind.EXIT && exitCode != null) {
            throw new IllegalArgumentException("only exit frames carry exitCode");
        }
        if (kind == Kind.ERROR && detail.isBlank()) {
            throw new IllegalArgumentException("error frame requires detail");
        }
        if (!dataBase64.isEmpty()) {
            Base64.getDecoder().decode(dataBase64);
        }
    }

    /** 解码输出载荷；无数据帧返回空字节数组。 */
    public byte[] data() {
        return dataBase64.isEmpty() ? new byte[0] : Base64.getDecoder().decode(dataBase64);
    }

    /** 按 UTF-8 解码当前数据帧，供文本消费者使用；二进制消费者应调用 data。 */
    public String utf8() {
        return new String(data(), StandardCharsets.UTF_8);
    }

    /** 创建启动就绪帧，detail 记录实施隔离的 backend。 */
    public static SandboxSessionFrame ready(String nonce, String backend) {
        return new SandboxSessionFrame(nonce, Kind.READY, "", null, false, backend);
    }

    /** 将字节编码为 STDOUT/STDERR 帧；拒绝其他类型，避免混淆控制与数据。 */
    public static SandboxSessionFrame stream(String nonce, Kind kind, byte[] value) {
        if (kind != Kind.STDOUT && kind != Kind.STDERR) {
            throw new IllegalArgumentException("stream kind is required");
        }
        return new SandboxSessionFrame(nonce, kind, Base64.getEncoder().encodeToString(value), null, false, "");
    }

    /** 创建唯一携带退出码的终态帧，同时保留截断和诊断信息。 */
    public static SandboxSessionFrame exit(String nonce, int code, boolean truncated, String detail) {
        return new SandboxSessionFrame(nonce, Kind.EXIT, "", code, truncated, detail);
    }

    /** 创建非空错误详情的失败帧；详情必须在发送前脱敏。 */
    public static SandboxSessionFrame error(String nonce, String detail) {
        return new SandboxSessionFrame(nonce, Kind.ERROR, "", null, false, detail);
    }

    private static String require(String value, String name) {
        value = Objects.requireNonNull(value, name).strip();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
