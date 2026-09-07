package com.javaclaw.nativehost.sandbox;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.SandboxFrame;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SandboxOutputObservationTest {
    @Test
    void 共享预算只观察实际保留字节且回调不能修改最终结果() throws Exception {
        var frames = new ArrayList<SandboxFrame>();
        var observation = new SandboxOutputObservation(new CancellationSource(), frame -> {
            frames.add(frame);
            frame.bytes()[0] = 'X';
        });
        var remaining = new AtomicLong(5);
        byte[] out = collect("abcd", remaining, observation, "stdout");
        byte[] err = collect("efgh", remaining, observation, "stderr");
        byte[] discarded = collect("more", remaining, observation, "stdout");
        assertArrayEquals("abcd".getBytes(StandardCharsets.UTF_8), out);
        assertArrayEquals(new byte[] {'e'}, err);
        assertArrayEquals(new byte[0], discarded);
        assertEquals(2, frames.size());
        assertEquals("stdout", frames.getFirst().channel());
        assertEquals("stderr", frames.getLast().channel());
        assertEquals(5, frames.stream().mapToInt(frame -> frame.bytes().length).sum());
        assertEquals(0, remaining.get());
    }

    @Test
    void 首个回调异常停止观察但继续排空并保留清理失败() throws Exception {
        var cancellation = new CancellationSource();
        var rejected = new IllegalStateException("fixture persistence failure");
        var observation = new SandboxOutputObservation(cancellation, frame -> {
            throw rejected;
        });
        byte[] bytes = collect("retained", new AtomicLong(1024), observation, "stdout");
        assertEquals("retained", new String(bytes, StandardCharsets.UTF_8));
        assertTrue(observation.isCancelled());
        assertFalse(cancellation.isCancelled());
        assertTrue(observation.reason().orElseThrow().contains("observer"));
        assertSame(rejected, assertThrows(IllegalStateException.class, observation::throwIfFailed));
        var cleanup = new IOException("fixture close failure");
        assertSame(rejected, observation.preferFailure(cleanup));
        assertArrayEquals(new Throwable[] {cleanup}, rejected.getSuppressed());
        assertSame(rejected, observation.preferFailure(rejected));
        collect("ignored callback", new AtomicLong(1024), observation, "stderr");
        assertEquals(1, rejected.getSuppressed().length);
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test
    void Error同样触发本次取消且外部取消原因原样透传() {
        var cancelled = new CancellationSource();
        var failure = new AssertionError("fixture observer error");
        var observation = new SandboxOutputObservation(cancelled, frame -> {
            throw failure;
        });
        assertFalse(observation.isCancelled());
        assertTrue(observation.reason().isEmpty());
        var unrelated = new IOException("fixture IO failure");
        assertSame(unrelated, observation.preferFailure(unrelated));
        cancelled.cancel("turn cancelled");
        assertTrue(observation.isCancelled());
        assertEquals("turn cancelled", observation.reason().orElseThrow());
        observation.channel("stdout").accept(new byte[] {1});
        assertSame(
                failure,
                assertThrows(IllegalStateException.class, observation::throwIfFailed)
                        .getCause());
    }

    @Test
    void 旧收集器构造与空观察者校验保持明确() throws Exception {
        assertArrayEquals(
                new byte[] {1},
                new BoundedStreamCollector(new ByteArrayInputStream(new byte[] {1, 2}), new AtomicLong(1)).call());
        assertThrows(NullPointerException.class, () -> new SandboxOutputObservation(new CancellationSource(), null));
        assertThrows(NullPointerException.class, () -> new SandboxOutputObservation(null, frame -> {}));
        assertThrows(
                NullPointerException.class,
                () -> new BoundedStreamCollector(new ByteArrayInputStream(new byte[0]), new AtomicLong(1), null));
    }

    @Test
    void 受控取消关闭管道时返回前缀而未知IO失败继续上报() throws Exception {
        var cancellation = new CancellationSource();
        var failing = new InputStream() {
            private int reads;

            @Override
            public int read() throws IOException {
                if (reads++ == 0) {
                    return 'a';
                }
                throw new IOException("fixture pipe closed");
            }
        };
        byte[] kept = new BoundedStreamCollector(
                        failing, new AtomicLong(1024), bytes -> cancellation.cancel("stop"), cancellation::isCancelled)
                .call();
        assertArrayEquals(new byte[] {'a'}, kept);
        var broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("unexpected IO");
            }
        };
        var error = assertThrows(
                IOException.class,
                () -> new BoundedStreamCollector(broken, new AtomicLong(1024), bytes -> {}, () -> false).call());
        assertEquals("unexpected IO", error.getMessage());
    }

    private static byte[] collect(
            String value, AtomicLong remaining, SandboxOutputObservation observation, String channel) throws Exception {
        return new BoundedStreamCollector(
                        new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)),
                        remaining,
                        observation.channel(channel))
                .call();
    }
}
