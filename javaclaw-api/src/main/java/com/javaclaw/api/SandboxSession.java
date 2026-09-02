package com.javaclaw.api;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * 调用方拥有的交互 Sandbox 会话。
 *
 * <p>订阅者必须施加背压；关闭会话时实现必须结束子进程、管道与原生句柄，重复关闭必须安全。
 */
public interface SandboxSession extends AutoCloseable {
    /**
     * 返回有界输出流。
     *
     * @return 支持背压的帧 Publisher
     */
    Flow.Publisher<SandboxFrame> frames();

    /**
     * 写入 PTY 输入。
     *
     * @param bytes 待写字节，实现必须复制
     * @return 写入完成信号
     */
    CompletionStage<Void> send(byte[] bytes);

    /**
     * 向子进程发送受控信号。
     *
     * @param signal 信号
     * @return 信号送达完成
     */
    CompletionStage<Void> signal(SandboxSignal signal);

    /**
     * 更新 PTY 字符尺寸并向前台进程组发送窗口变化信号。
     *
     * @param columns 列数，20 到 1000
     * @param rows 行数，5 到 1000
     * @return 调整完成信号
     */
    CompletionStage<Void> resize(int columns, int rows);

    /**
     * 返回进程终态。
     *
     * @return 只完成一次的结果
     */
    CompletionStage<SandboxResult> completion();

    /** 关闭会话拥有的全部资源。 */
    @Override
    void close();
}
