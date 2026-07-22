package com.javaclaw.plugin;

import com.javaclaw.plugin.api.JavaClawPlugin;
import com.javaclaw.plugin.api.PluginContext;
import com.javaclaw.plugin.api.PluginDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PluginRuntimeTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void resetPluginState() {
        FailingPlugin.starts.set(0);
        FailingPlugin.stops.set(0);
        FailingPlugin.ticks.set(0);
    }

    @Test
    void failedStartRollsBackTasksAndCanLoadFreshInstance() throws Exception {
        Path jar = tempDir.resolve("failing.jar");
        try (JarOutputStream ignored = new JarOutputStream(Files.newOutputStream(jar))) {
            // 入口类由测试类加载器提供；空 jar 用于验证运行时类加载器的关闭与重建。
        }
        PluginDescriptor descriptor = new PluginDescriptor(
                "failing-test", "Failing", "1.0.0", "1.0",
                FailingPlugin.class.getName(), "test", Set.of(), List.of());
        PluginRuntime runtime = new PluginRuntime(
                descriptor, jar, null, getClass().getClassLoader(), tempDir.resolve("data"));

        Exception first = assertThrows(Exception.class, () -> runtime.start(Set.of(), Map.of()));
        assertEquals("start failed", first.getMessage());
        assertEquals(PluginState.FAILED, runtime.state());
        assertEquals(1, FailingPlugin.starts.get());
        assertEquals(1, FailingPlugin.stops.get());
        assertTicksStopAfterRollback();

        assertThrows(Exception.class, () -> runtime.start(Set.of(), Map.of()));
        assertEquals(2, FailingPlugin.starts.get(), "失败后重试应重新加载入口实例");
        assertEquals(2, FailingPlugin.stops.get());
        runtime.unload();
    }

    private void assertTicksStopAfterRollback() throws InterruptedException {
        Thread.sleep(40);
        int afterRollback = FailingPlugin.ticks.get();
        Thread.sleep(80);
        assertEquals(afterRollback, FailingPlugin.ticks.get(), "启动失败后周期任务仍在运行");
    }

    public static final class FailingPlugin implements JavaClawPlugin {
        static final AtomicInteger starts = new AtomicInteger();
        static final AtomicInteger stops = new AtomicInteger();
        static final AtomicInteger ticks = new AtomicInteger();

        public FailingPlugin() {
        }

        @Override
        public void start(PluginContext ctx) throws Exception {
            starts.incrementAndGet();
            ctx.exec().scheduleAtRate(Duration.ofMillis(1), ticks::incrementAndGet);
            throw new Exception("start failed");
        }

        @Override
        public void stop() {
            stops.incrementAndGet();
        }
    }
}
