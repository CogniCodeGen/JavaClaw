package com.javaclaw.desktop.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.protocol.CanonicalJson;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebChatScrollResponsivenessTest {
    @Test
    void 短历史连续滚动接近原生位移且不重新挂载消息() throws IOException {
        verifyResponsiveness(32);
    }

    @Test
    void 大型历史缓冲区内连续滚动接近原生位移且不重新挂载消息() throws IOException {
        verifyResponsiveness(500);
    }

    @Test
    void 阅读时追加末尾回复保留消息节点且不校正未移动的锚点() {
        var fixture = open(32);
        try {
            double before = FxTestSupport.call(fixture::positions).chat();
            FxTestSupport.run(fixture::appendTail);
            FxTestSupport.await(() -> FxTestSupport.call(fixture::ready));
            awaitStable(fixture);
            var metrics = FxTestSupport.call(fixture::chatMetrics);
            assertAll(
                    () -> assertEquals(before, metrics.scrollY(), 1),
                    () -> assertEquals(0, metrics.added()),
                    () -> assertEquals(0, metrics.removed()),
                    () -> assertEquals(0, metrics.remounted()),
                    () -> assertEquals(0, metrics.scrollBy()));
        } finally {
            FxTestSupport.run(fixture::close);
        }
    }

    private static void verifyResponsiveness(int count) throws IOException {
        WebChatScrollResponsivenessFixture fixture = open(count);
        try {
            var initial = FxTestSupport.call(fixture::positions);
            FxTestSupport.run(fixture::observeAndStart);
            FxTestSupport.await(() -> FxTestSupport.call(fixture::streamFinished));
            awaitStable(fixture);
            var chat = FxTestSupport.call(fixture::chatMetrics);
            var baseline = FxTestSupport.call(fixture::baselineMetrics);
            double chatDistance = initial.chat() - chat.scrollY();
            double baselineDistance = initial.baseline() - baseline.scrollY();
            String evidence = saveEvidence(count, chatDistance, baselineDistance, chat, baseline);
            assertAll(
                    evidence,
                    () -> assertEquals(WebChatScrollResponsivenessFixture.EVENT_COUNT, chat.wheels()),
                    () -> assertEquals(WebChatScrollResponsivenessFixture.EVENT_COUNT, baseline.wheels()),
                    () -> assertTrue(baselineDistance > 50, "基准必须实际完成连续向上滚动"),
                    () -> assertTrue(chatDistance >= baselineDistance * 0.8, "聊天滚动位移不得显著低于同事件原生基准"),
                    () -> assertEquals(Math.min(count, 128), chat.articles()),
                    () -> assertEquals(0, chat.added(), "缓冲区内部没有新增消息，不需要挂载 article"),
                    () -> assertEquals(0, chat.removed(), "滚动不应移除已有 article"),
                    () -> assertEquals(0, chat.remounted(), "滚动不应反复挂载同一 article"),
                    () -> assertEquals(0, chat.scrollBy(), "缓冲区内部不应使用锚点修正打断原生动画"));
        } finally {
            FxTestSupport.run(fixture::close);
        }
    }

    private static WebChatScrollResponsivenessFixture open(int count) {
        var fixture = FxTestSupport.call(() -> new WebChatScrollResponsivenessFixture(count));
        try {
            FxTestSupport.await(() -> FxTestSupport.call(fixture::ready));
            FxTestSupport.run(fixture::positionChat);
            awaitStable(fixture);
            FxTestSupport.run(fixture::loadBaseline);
            FxTestSupport.await(() -> FxTestSupport.call(fixture::baselineReady));
            FxTestSupport.run(fixture::alignBaseline);
            awaitStable(fixture);
            return fixture;
        } catch (RuntimeException | Error failure) {
            FxTestSupport.run(fixture::close);
            throw failure;
        }
    }

    private static void awaitStable(WebChatScrollResponsivenessFixture fixture) {
        StablePositions stable = new StablePositions();
        // 连续半秒没有位移才读取结果，不能把尚未完成的 WebKit 原生动画误认为较慢。
        FxTestSupport.await(() -> stable.accept(FxTestSupport.call(fixture::positions)));
    }

    private static String saveEvidence(
            int count,
            double chatDistance,
            double baselineDistance,
            WebChatScrollResponsivenessFixture.Metrics chat,
            WebChatScrollResponsivenessFixture.Metrics baseline)
            throws IOException {
        Map<String, Object> measurement = new LinkedHashMap<>();
        measurement.put("messageCount", count);
        measurement.put("eventCount", WebChatScrollResponsivenessFixture.EVENT_COUNT);
        measurement.put("deltaPixels", WebChatScrollResponsivenessFixture.DELTA_PIXELS);
        measurement.put("eventPeriodMillis", WebChatScrollResponsivenessFixture.PERIOD_MILLIS);
        measurement.put("chatDistancePixels", chatDistance);
        measurement.put("baselineDistancePixels", baselineDistance);
        measurement.put("distanceRatio", baselineDistance == 0 ? 0 : chatDistance / baselineDistance);
        measurement.put("chat", chat);
        measurement.put("baseline", baseline);
        String json = new CanonicalJson().encode(measurement).json();
        Path directory = Path.of(
                System.getProperty("javaclaw.scroll.measurements.output", "target/acceptance/scroll-responsiveness"));
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("scroll-response-" + count + ".json"), json);
        System.out.println("SCROLL_RESPONSE " + json);
        return json;
    }

    private static final class StablePositions {
        private WebChatScrollResponsivenessFixture.Positions previous;
        private long unchangedSince;

        private boolean accept(WebChatScrollResponsivenessFixture.Positions current) {
            long now = System.nanoTime();
            if (previous == null
                    || Math.abs(previous.chat() - current.chat()) > 0.1
                    || Math.abs(previous.baseline() - current.baseline()) > 0.1) {
                previous = current;
                unchangedSince = now;
                return false;
            }
            return now - unchangedSince >= 500_000_000L;
        }
    }
}
