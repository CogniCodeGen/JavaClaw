package com.javaclaw.protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** 四字节大端长度前缀 framing，适用于 stdio、UDS 与 Named Pipe。 */
public final class LengthPrefixedFraming {
    /** 默认最大单帧为 16 MiB。 */
    public static final int DEFAULT_MAX_FRAME_BYTES = 16 * 1024 * 1024;

    private LengthPrefixedFraming() {}

    /**
     * 读取一帧。
     *
     * @param input 输入流；调用方拥有并负责关闭
     * @param maximumBytes 最大帧长
     * @return 帧内容
     * @throws IOException EOF、长度越界或读取失败
     */
    public static byte[] read(InputStream input, int maximumBytes) throws IOException {
        validateMaximum(maximumBytes);
        DataInputStream data = new DataInputStream(input);
        int length;
        try {
            length = data.readInt();
        } catch (EOFException failure) {
            throw new EOFException("RPC stream ended before frame length");
        }
        if (length < 1 || length > maximumBytes) {
            throw new IOException("RPC frame length is outside the configured limit");
        }
        byte[] frame = data.readNBytes(length);
        if (frame.length != length) {
            throw new EOFException("RPC stream ended inside a frame");
        }
        return frame;
    }

    /**
     * 写入一帧并 flush。
     *
     * @param output 输出流；调用方拥有并负责关闭
     * @param frame 帧内容
     * @param maximumBytes 最大帧长
     * @throws IOException 长度越界或写入失败
     */
    public static void write(OutputStream output, byte[] frame, int maximumBytes) throws IOException {
        validateMaximum(maximumBytes);
        if (frame.length < 1 || frame.length > maximumBytes) {
            throw new IOException("RPC frame length is outside the configured limit");
        }
        DataOutputStream data = new DataOutputStream(output);
        data.writeInt(frame.length);
        data.write(frame);
        data.flush();
    }

    private static void validateMaximum(int maximumBytes) {
        if (maximumBytes < 1) {
            throw new IllegalArgumentException("maximumBytes must be positive");
        }
    }
}
