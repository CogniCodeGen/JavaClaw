package com.javaclaw.desktop;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import com.javaclaw.desktop.state.DesktopState;

/** 仅在 JavaFX 调度器上提交状态的轻量 Store；订阅时立即发布快照，之后仅在事实变化时通知监听者。 */
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
        DesktopState next =
                Objects.requireNonNull(Objects.requireNonNull(change, "change").apply(state), "next");
        // 迟到响应与重复通知可以归并为原状态；没有变化时不能让整壳重复处理同一份事实。
        if (state.equals(next)) {
            return;
        }
        state = next;
        listeners.forEach(listener -> listener.accept(state));
    }
}
