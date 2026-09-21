package com.javaclaw.desktop.settings;

import java.util.Objects;
import java.util.Optional;

import com.javaclaw.desktop.DesktopNotificationSubscription;
import com.javaclaw.desktop.DesktopPresenter;
import com.javaclaw.desktop.state.ConnectionState;

/** 配置草稿的可取消会话观察者；仅清理业务秘密，不改变共享连接机制。 */
final class ProviderConfigurationSessionWatch implements DesktopNotificationSubscription {
    private final Runnable listener;
    private final DesktopNotificationSubscription connection;
    private AutoCloseable failure;
    private Optional<java.time.Instant> connectedAt = Optional.empty();
    private boolean closed;

    ProviderConfigurationSessionWatch(DesktopPresenter desktop, Runnable listener) {
        this.listener = Objects.requireNonNull(listener, "listener");
        connection = desktop.observeConnection(this::connectionChanged);
        desktop.submitSettingsRequest(client -> client.providers()
                        .configuration()
                        .onSessionFailure(() -> FxStateDispatcher.dispatch(this::invalidated)))
                .whenComplete((subscription, problem) -> {
                    if (closed) {
                        closeQuietly(subscription);
                    } else if (problem != null) {
                        invalidated();
                    } else {
                        failure = subscription;
                    }
                });
    }

    private void connectionChanged(ConnectionState state) {
        boolean changed = connectedAt.isPresent() && !connectedAt.equals(state.connectedAt());
        connectedAt = state.connectedAt();
        if (changed || state.status() != ConnectionState.Status.CONNECTED) {
            invalidated();
        }
    }

    private void invalidated() {
        if (!closed) {
            listener.run();
        }
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            connection.close();
            closeQuietly(failure);
            failure = null;
        }
    }

    private static void closeQuietly(AutoCloseable subscription) {
        if (subscription != null) {
            try {
                subscription.close();
            } catch (Exception ignored) {
                // 观察句柄只移除监听者；关闭失败不改变已完成的秘密清理。
            }
        }
    }
}
