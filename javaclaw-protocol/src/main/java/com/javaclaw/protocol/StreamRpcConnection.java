package com.javaclaw.protocol;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 基于输入/输出流的长度前缀 RPC 连接。
 *
 * <p>构造后连接拥有两个流；一个读取线程与多个发送线程可并发使用，发送帧不会交错。关闭会同时关闭两个流并唤醒阻塞 I/O。
 */
public final class StreamRpcConnection implements RpcConnection {
    private final InputStream input;
    private final OutputStream output;
    private final JsonRpcCodec codec;
    private final int maximumFrameBytes;
    private final ReentrantLock receiveLock = new ReentrantLock();
    private final ReentrantLock sendLock = new ReentrantLock();

    /**
     * 创建使用默认 16 MiB 帧上限的连接。
     *
     * @param input 输入流，所有权转移给连接
     * @param output 输出流，所有权转移给连接
     * @param codec JSON-RPC codec
     */
    public StreamRpcConnection(InputStream input, OutputStream output, JsonRpcCodec codec) {
        this(input, output, codec, LengthPrefixedFraming.DEFAULT_MAX_FRAME_BYTES);
    }

    /**
     * 创建连接。
     *
     * @param input 输入流，所有权转移给连接
     * @param output 输出流，所有权转移给连接
     * @param codec JSON-RPC codec
     * @param maximumFrameBytes 最大帧字节数
     */
    public StreamRpcConnection(InputStream input, OutputStream output, JsonRpcCodec codec, int maximumFrameBytes) {
        this.input = Objects.requireNonNull(input, "input");
        this.output = Objects.requireNonNull(output, "output");
        this.codec = Objects.requireNonNull(codec, "codec");
        if (maximumFrameBytes < 1) {
            throw new IllegalArgumentException("maximumFrameBytes must be positive");
        }
        this.maximumFrameBytes = maximumFrameBytes;
    }

    @Override
    public void send(JsonRpcMessage message) throws IOException {
        byte[] frame = codec.encode(Objects.requireNonNull(message, "message")).getBytes(StandardCharsets.UTF_8);
        sendLock.lock();
        try {
            LengthPrefixedFraming.write(output, frame, maximumFrameBytes);
        } finally {
            sendLock.unlock();
        }
    }

    @Override
    public JsonRpcMessage receive() throws IOException {
        receiveLock.lock();
        try {
            byte[] frame = LengthPrefixedFraming.read(input, maximumFrameBytes);
            return codec.decode(new String(frame, StandardCharsets.UTF_8));
        } finally {
            receiveLock.unlock();
        }
    }

    @Override
    public void close() throws IOException {
        IOException first = null;
        try {
            input.close();
        } catch (IOException failure) {
            first = failure;
        }
        try {
            output.close();
        } catch (IOException failure) {
            if (first == null) {
                first = failure;
            } else {
                first.addSuppressed(failure);
            }
        }
        if (first != null) {
            throw first;
        }
    }
}
