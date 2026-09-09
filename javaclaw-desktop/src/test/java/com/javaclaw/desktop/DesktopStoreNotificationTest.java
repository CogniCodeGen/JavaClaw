package com.javaclaw.desktop;

import java.util.ArrayList;

import org.junit.jupiter.api.Test;

import com.javaclaw.desktop.state.ConnectionState;
import com.javaclaw.desktop.state.DesktopState;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DesktopStoreNotificationTest {
    @Test
    void 重复事实不触发整壳刷新但真实连接变化立即发布() {
        DesktopStore store = new DesktopStore();
        ArrayList<DesktopState> states = new ArrayList<>();
        store.subscribe(states::add);
        for (int index = 0; index < 24; index++) {
            store.update(state -> new DesktopState(
                    state.connection(), state.navigation(), state.threads(), state.transcript(), state.interaction()));
        }
        assertEquals(1, states.size());
        store.update(state -> new DesktopState(
                ConnectionState.connecting(),
                state.navigation(),
                state.threads(),
                state.transcript(),
                state.interaction()));
        assertEquals(2, states.size());
        assertSame(store.state(), states.getLast());
        ArrayList<DesktopState> lateSubscriber = new ArrayList<>();
        store.subscribe(lateSubscriber::add);
        assertEquals(states.subList(1, 2), lateSubscriber);
    }

    @Test
    void 无效状态不覆盖当前事实也不通知界面() {
        DesktopStore store = new DesktopStore();
        ArrayList<DesktopState> states = new ArrayList<>();
        store.subscribe(states::add);
        DesktopState initial = store.state();
        assertThrows(NullPointerException.class, () -> store.update(state -> null));
        assertSame(initial, store.state());
        assertEquals(1, states.size());
    }
}
