package com.javaclaw.sandbox.api;

import java.time.Duration;

/** A process session owned by the sandbox launcher, never by the App Server directly. */
public interface SandboxSession extends AutoCloseable {
    /** 返回本会话的稳定关联标识，供取消和诊断使用。 */
    String id();

    /** Returns null on timeout. */
    SandboxSessionFrame read(Duration timeout) throws Exception;

    /**
     * 向沙箱标准输入写入字节；输入关闭或会话终止后不再接受新数据。
     *
     * @throws Exception 管道不可写或会话已经结束
     */
    void write(byte[] input) throws Exception;

    /**
     * 关闭标准输入并向子进程传递 EOF；不等同于终止整个会话。
     *
     * @throws Exception 输入管道关闭失败
     */
    void closeInput() throws Exception;

    /**
     * 调整 PTY 字符尺寸；仅伪终端会话支持，管道会话不得假装成功。
     *
     * @throws Exception 平台不支持或尺寸更新失败
     */
    void resize(int columns, int rows) throws Exception;

    /**
     * 向受控会话发送信号；实现不得把信号转发给沙箱之外的进程。
     *
     * @throws Exception 信号投递失败或平台不支持
     */
    void signal(SandboxSignal signal) throws Exception;

    /** 判断受监督的会话是否仍在运行，不代表有可读输出。 */
    boolean isAlive();

    /** 终止会话并回收受控子进程树；调用方无需自行查找或杀死派生进程。 */
    void terminate();

    @Override
    default void close() {
        terminate();
    }
}
