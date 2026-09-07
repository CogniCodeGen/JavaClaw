package com.javaclaw.client.cli;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 唯一阻塞终端 reader。每次读取绑定展示时的 epoch，过期答案不能借下一问题的身份提交。
 *
 * <p>输入与结果队列均有界；退出只中断虚拟线程，不关闭宿主 stdin，也不等待不可中断的终端读取。
 */
final class CliInputReader implements AutoCloseable {
    private static final int MAXIMUM_BYTES = 65_536;
    private final Reader reader;
    private final ArrayBlockingQueue<Long> requests = new ArrayBlockingQueue<>(1);
    private final ArrayBlockingQueue<Answer> answers = new ArrayBlockingQueue<>(1);
    private final AtomicBoolean busy = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Thread worker;

    CliInputReader(Reader reader) {
        this.reader = reader;
        worker = Thread.ofVirtual().name("javaclaw-cli-input").start(this::readLoop);
    }

    boolean busy() {
        return busy.get();
    }

    void request(long epoch) {
        if (!busy.compareAndSet(false, true) || !requests.offer(epoch)) {
            throw new IllegalStateException("终端已有未完成的输入读取");
        }
    }

    Answer poll() {
        Answer answer = answers.poll();
        if (answer != null) {
            busy.set(false);
        }
        return answer;
    }

    private void readLoop() {
        while (!closed.get()) {
            try {
                long epoch = requests.take();
                Answer answer = read(epoch);
                if (!closed.get()) {
                    answers.put(answer);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private Answer read(long epoch) {
        StringBuilder line = new StringBuilder();
        boolean oversized = false;
        try {
            int value;
            while ((value = reader.read()) != -1 && value != '\n') {
                if (line.length() < MAXIMUM_BYTES) {
                    line.append((char) value);
                } else {
                    oversized = true;
                }
            }
            if (value == -1 && line.isEmpty()) {
                return new Answer(epoch, Kind.EOF, "");
            }
            String text = line.toString();
            if (oversized || text.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_BYTES) {
                return new Answer(epoch, Kind.TOO_LONG, "");
            }
            return new Answer(epoch, Kind.LINE, text.strip());
        } catch (IOException failure) {
            return new Answer(epoch, Kind.ERROR, "无法读取终端输入");
        }
    }

    /** 终止队列等待；宿主拥有 reader 的关闭责任。 */
    @Override
    public void close() {
        closed.set(true);
        worker.interrupt();
    }

    enum Kind {
        LINE,
        EOF,
        TOO_LONG,
        ERROR
    }

    /**
     * 一次终端读取结果，不改变其绑定的请求身份。
     *
     * @param epoch 发起读取时的展示代次，无单位
     * @param kind 读取结果类别，不可空
     * @param text 行正文或脱敏错误说明，不可空；EOF 和超长行使用空字符串
     */
    record Answer(long epoch, Kind kind, String text) {}
}
