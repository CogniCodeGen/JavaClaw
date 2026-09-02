package com.javaclaw.browser.protocol;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.LengthPrefixedFraming;

/** Browser Worker JSON 元数据帧与原始字节帧的唯一 framing 实现。 */
public final class BrowserFrameIo {
    private static final int MAXIMUM_METADATA_BYTES = 1024 * 1024;

    private BrowserFrameIo() {}

    /** 读取并解码一帧规范 JSON 元数据。 */
    public static <T> T readJson(InputStream input, CanonicalJson json, Class<T> type) throws IOException {
        byte[] bytes = LengthPrefixedFraming.read(Objects.requireNonNull(input, "input"), MAXIMUM_METADATA_BYTES);
        CanonicalPayload payload =
                Objects.requireNonNull(json, "json").parse(new String(bytes, StandardCharsets.UTF_8));
        return json.decode(payload, Objects.requireNonNull(type, "type"));
    }

    /** 编码并写入一帧规范 JSON 元数据。 */
    public static void writeJson(OutputStream output, CanonicalJson json, Object value) throws IOException {
        byte[] bytes = Objects.requireNonNull(json, "json")
                .encode(Objects.requireNonNull(value, "value"))
                .json()
                .getBytes(StandardCharsets.UTF_8);
        LengthPrefixedFraming.write(Objects.requireNonNull(output, "output"), bytes, MAXIMUM_METADATA_BYTES);
    }

    /** 读取一帧并要求长度精确匹配已验证元数据。 */
    public static byte[] readBinary(InputStream input, int expectedBytes, int maximumBytes) throws IOException {
        requireBinaryLength(expectedBytes, maximumBytes);
        if (expectedBytes == 0) {
            return new byte[0];
        }
        byte[] value = LengthPrefixedFraming.read(Objects.requireNonNull(input, "input"), maximumBytes);
        if (value.length != expectedBytes) {
            java.util.Arrays.fill(value, (byte) 0);
            throw new IOException("Browser Worker binary frame length mismatch");
        }
        return value;
    }

    /** 写入一帧原始字节；0 长度不产生附加帧。 */
    public static void writeBinary(OutputStream output, byte[] bytes, int maximumBytes) throws IOException {
        byte[] checked = Objects.requireNonNull(bytes, "bytes");
        requireBinaryLength(checked.length, maximumBytes);
        if (checked.length != 0) {
            LengthPrefixedFraming.write(Objects.requireNonNull(output, "output"), checked, maximumBytes);
        }
    }

    private static void requireBinaryLength(int value, int maximum) {
        if (value < 0 || value > maximum) {
            throw new IllegalArgumentException("Browser Worker binary length is outside the frame limit");
        }
    }
}
