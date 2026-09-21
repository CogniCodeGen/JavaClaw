package com.javaclaw.desktop;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.javaclaw.desktop.state.ConnectionState;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopProviderConnectionObservationTest {
    @Test
    void 配置连接观察立即读取快照且关闭后不再接收重连事件() throws Exception {
        try (DesktopPresenter presenter = new DesktopPresenter(
                ignored -> {
                    throw new IllegalStateException("固定连接失败");
                },
                Runnable::run,
                Clock.systemUTC())) {
            List<ConnectionState.Status> observed = new CopyOnWriteArrayList<>();
            var subscription = presenter.observeConnection(state -> observed.add(state.status()));
            assertEquals(List.of(ConnectionState.Status.DISCONNECTED), observed);
            assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> presenter.reconnect().get(5, TimeUnit.SECONDS));
            assertTrue(observed.contains(ConnectionState.Status.CONNECTING));
            subscription.close();
            subscription.close();
            int before = observed.size();
            assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> presenter.reconnect().get(5, TimeUnit.SECONDS));
            assertEquals(before, observed.size());
        }
    }
}
