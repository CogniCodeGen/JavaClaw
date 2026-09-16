package com.javaclaw.desktop.settings;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import com.javaclaw.api.WorkspaceId;
import com.javaclaw.desktop.DesktopNotificationSubscription;
import com.javaclaw.protocol.ExtensionRpcContracts;

/** FX 线程拥有的页面事件订阅；固定平台页只在激活期间订阅，旧句柄排队中的通知按代次丢弃。 */
final class ViewPageEventSubscription implements AutoCloseable {
    private final ExtensionSettingsGateway gateway;
    private final String extensionId;
    private final Consumer<ExtensionRpcContracts.ExtensionEvent> listener;
    private final Runnable paused;
    private Optional<WorkspaceId> workspace = Optional.empty();
    private DesktopNotificationSubscription subscription;
    private long epoch;
    private boolean visibleOnly;
    private boolean active;
    private boolean disposed;

    ViewPageEventSubscription(
            ExtensionSettingsGateway gateway,
            String extensionId,
            Consumer<ExtensionRpcContracts.ExtensionEvent> listener,
            Runnable paused) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.extensionId = Objects.requireNonNull(extensionId, "extensionId");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.paused = Objects.requireNonNull(paused, "paused");
    }

    void visibleOnly(boolean value) {
        visibleOnly = value;
        if (visibleOnly && !active) {
            detach();
        }
    }

    void activate() {
        active = true;
        attach();
    }

    void deactivate() {
        boolean previouslyActive = active;
        active = false;
        if (visibleOnly && previouslyActive) {
            detach();
            // 未订阅期间可能遗漏目录和数据变更，重开必须重新读取；调用方保留草稿及已发送命令。
            paused.run();
        }
    }

    void workspaceChanged(Optional<WorkspaceId> next) {
        clear();
        workspace = Objects.requireNonNull(next, "next");
        attach();
    }

    void clear() {
        detach();
        workspace = Optional.empty();
    }

    private void attach() {
        if (disposed || subscription != null || workspace.isEmpty() || visibleOnly && !active) {
            return;
        }
        long requestEpoch = ++epoch;
        DesktopNotificationSubscription opened = gateway.subscribe(workspace.orElseThrow(), extensionId, event -> {
            if (requestEpoch == epoch && !disposed) {
                listener.accept(event);
            }
        });
        if (requestEpoch == epoch && !disposed) {
            subscription = opened;
        } else {
            opened.close();
        }
    }

    private void detach() {
        epoch++;
        DesktopNotificationSubscription previous = subscription;
        subscription = null;
        if (previous != null) {
            previous.close();
        }
    }

    @Override
    public void close() {
        disposed = true;
        clear();
    }
}
