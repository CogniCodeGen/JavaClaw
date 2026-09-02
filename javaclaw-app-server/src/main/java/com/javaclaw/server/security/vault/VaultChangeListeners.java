package com.javaclaw.server.security.vault;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 在 Vault 事务完成后通知依赖方刷新，不允许监听失败反向回滚已提交事务。 */
final class VaultChangeListeners {
    private final List<Runnable> listeners = new ArrayList<>();

    void add(Runnable listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    void notifyAllListeners() {
        for (Runnable listener : List.copyOf(listeners)) {
            try {
                listener.run();
            } catch (RuntimeException failure) {
                System.getLogger(VaultChangeListeners.class.getName())
                        .log(System.Logger.Level.WARNING, "Vault 变化后的热更新失败", failure);
            }
        }
    }
}
