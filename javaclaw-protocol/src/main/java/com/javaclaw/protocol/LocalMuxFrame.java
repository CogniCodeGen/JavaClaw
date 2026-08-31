package com.javaclaw.protocol;

import java.util.Base64;
import java.util.Objects;

/**
 * Bounded inherited-pipe frame used only between the Windows Transport Host and App Server.
 *
 * @param connectionId 当前本地连接标识，用于路由和诊断
 * @param kind 非空 OPEN/DATA/CLOSE 帧类型
 * @param dataBase64 数据载荷；null 归一为空字符串，仅 DATA 帧允许且必须携带数据
 */
public record LocalMuxFrame(String connectionId, Kind kind, String dataBase64) {
    public static final int MAX_DATA_BYTES = 1024 * 1024;

    /** Windows 辅助进程继承管道的连接生命周期帧类型。 */
    public enum Kind {
        OPEN,
        DATA,
        CLOSE
    }

    /** 校验 connectionId、Base64 和 1 MiB 解码上限，拒绝控制帧夹带数据。 */
    public LocalMuxFrame {
        connectionId = Objects.requireNonNull(connectionId, "connectionId").strip();
        if (!connectionId.matches("[A-Za-z0-9_-]{16,96}")) {
            throw new IllegalArgumentException("invalid local mux connectionId");
        }
        kind = Objects.requireNonNull(kind, "kind");
        dataBase64 = dataBase64 == null ? "" : dataBase64;
        byte[] decoded;
        try {
            decoded = dataBase64.isEmpty() ? new byte[0] : Base64.getDecoder().decode(dataBase64);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("invalid local mux base64", failure);
        }
        if (decoded.length > MAX_DATA_BYTES) {
            throw new IllegalArgumentException("local mux data exceeds 1 MiB");
        }
        if (kind == Kind.DATA && decoded.length == 0) {
            throw new IllegalArgumentException("DATA frame is empty");
        }
        if (kind != Kind.DATA && decoded.length != 0) {
            throw new IllegalArgumentException("only DATA frames carry data");
        }
    }

    /** 解码本帧字节；OPEN/CLOSE 返回空数组。 */
    public byte[] data() {
        return dataBase64.isEmpty() ? new byte[0] : Base64.getDecoder().decode(dataBase64);
    }

    /** 为指定连接创建无载荷 OPEN 帧，不创建 TCP 或 Named Pipe 监听器。 */
    public static LocalMuxFrame open(String id) {
        return new LocalMuxFrame(id, Kind.OPEN, "");
    }

    /** 复制指定数组切片并编码为 DATA 帧；越界、空载荷或超过 1 MiB 均拒绝。 */
    public static LocalMuxFrame data(String id, byte[] data, int offset, int length) {
        Objects.requireNonNull(data, "data");
        Objects.checkFromIndexSize(offset, length, data.length);
        if (length < 1 || length > MAX_DATA_BYTES) {
            throw new IllegalArgumentException("local mux data length is invalid");
        }
        return new LocalMuxFrame(
                id,
                Kind.DATA,
                Base64.getEncoder().encodeToString(java.util.Arrays.copyOfRange(data, offset, offset + length)));
    }

    /** 为指定连接创建无载荷 CLOSE 帧，由接收方释放该连接资源。 */
    public static LocalMuxFrame close(String id) {
        return new LocalMuxFrame(id, Kind.CLOSE, "");
    }
}
