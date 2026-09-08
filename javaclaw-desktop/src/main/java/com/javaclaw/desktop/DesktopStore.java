package com.javaclaw.desktop;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import com.javaclaw.desktop.state.DesktopState;

/** 仅在 JavaFX 调度器上提交状态的轻量 Store；监听者永远接收完整不可变快照。 */
final class DesktopStore {
    private final CopyOnWriteArrayList<Consumer<DesktopState>> listeners = new CopyOnWriteArrayList<>();
    private volatile DesktopState state = DesktopState.initial();

    DesktopState state() {
        return state;
    }

    void subscribe(Consumer<DesktopState> listener) {
        Consumer<DesktopState> checked = Objects.requireNonNull(listener, "listener");
        listeners.add(checked);
        checked.accept(state);
    }

    void update(UnaryOperator<DesktopState> change) {
        state = Objects.requireNonNull(change, "change").apply(state);
        listeners.forEach(listener -> listener.accept(state));
    }
}
