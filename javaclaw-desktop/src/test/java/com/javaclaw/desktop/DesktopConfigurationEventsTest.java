package com.javaclaw.desktop;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DesktopConfigurationEventsTest {
    @Test
    void 订阅只属于当前实例且取消和关闭后不再投递() {
        DesktopConfigurationEvents events = new DesktopConfigurationEvents();
        DesktopConfigurationEvents other = new DesktopConfigurationEvents();
        List<DesktopConfigurationChange> received = new ArrayList<>();
        DesktopNotificationSubscription subscription = events.subscribe(received::add);
        DesktopConfigurationChange change = globalChange();

        other.publish(change);
        events.publish(change);
        subscription.close();
        subscription.close();
        events.publish(change);

        assertEquals(List.of(change), received);
        events.subscribe(received::add);
        events.close();
        events.close();
        events.publish(change);
        assertEquals(List.of(change), received);
        assertThrows(IllegalStateException.class, () -> events.subscribe(received::add));
        other.close();
    }

    @Test
    void 异常监听者不能中断成功写入或其他界面的刷新() {
        try (DesktopConfigurationEvents events = new DesktopConfigurationEvents()) {
            List<DesktopConfigurationChange> received = new ArrayList<>();
            events.subscribe(ignored -> {
                throw new IllegalStateException("错误监听者");
            });
            events.subscribe(received::add);
            events.publish(globalChange());
            events.publish(globalChange());
            assertEquals(2, received.size());
        }
    }

    @Test
    void Thread失效不能缺少所属工作区() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DesktopConfigurationChange(
                        DesktopConfigurationChange.Kind.EXECUTION,
                        Optional.empty(),
                        Optional.of(DesktopTestFixtures.thread().id())));
    }

    private static DesktopConfigurationChange globalChange() {
        return new DesktopConfigurationChange(
                DesktopConfigurationChange.Kind.PROVIDERS, Optional.empty(), Optional.empty());
    }
}
