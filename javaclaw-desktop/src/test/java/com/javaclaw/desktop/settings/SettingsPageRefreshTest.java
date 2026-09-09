package com.javaclaw.desktop.settings;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import javafx.scene.Node;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Test;

import com.javaclaw.desktop.DesktopConfigurationChange;
import com.javaclaw.desktop.DesktopConfigurationEvents;
import com.javaclaw.desktop.DesktopNotificationSubscription;
import com.javaclaw.desktop.FxTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SettingsPageRefreshTest {
    @Test
    void 重开和聚焦复用成功快照而到期后重新查询() {
        FxTestSupport.run(() -> {
            Fixture fixture = new Fixture();
            try {
                fixture.refresh.activate();
                fixture.reads.getFirst().complete(true);
                fixture.refresh.deactivate();
                fixture.refresh.activate();
                fixture.refresh.activate();
                assertEquals(1, fixture.reads.size());
                fixture.clock.set(Duration.ofMinutes(5).toNanos());
                fixture.refresh.activate();
                assertEquals(2, fixture.reads.size());
            } finally {
                fixture.refresh.close();
            }
        });
    }

    @Test
    void 激活期间合并在途读取且失败下次激活重试() {
        FxTestSupport.run(() -> {
            Fixture fixture = new Fixture();
            try {
                fixture.refresh.activate();
                fixture.refresh.activate();
                assertEquals(1, fixture.reads.size());
                fixture.reads.getFirst().complete(false);
                fixture.refresh.activate();
                assertEquals(2, fixture.reads.size());
                fixture.reads.getLast().completeExceptionally(new IllegalStateException("offline"));
                fixture.refresh.activate();
                assertEquals(3, fixture.reads.size());
            } finally {
                fixture.refresh.close();
            }
        });
    }

    @Test
    void 隐藏失效不会查询且在途旧响应不能重新标为有效() {
        FxTestSupport.run(() -> {
            Fixture fixture = new Fixture();
            try {
                fixture.refresh.activate();
                fixture.refresh.deactivate();
                fixture.gateway.changed();
                fixture.reads.getFirst().complete(true);
                assertEquals(1, fixture.reads.size());
                fixture.page.dirty = true;
                fixture.refresh.activate();
                assertEquals(2, fixture.reads.size());
                assertEquals(List.of(false, true), fixture.preserved);
                fixture.refresh.invalidate();
                fixture.reads.getLast().complete(true);
                assertEquals(3, fixture.reads.size());
                fixture.reads.getLast().complete(true);
                fixture.refresh.activate();
                assertEquals(3, fixture.reads.size());
            } finally {
                fixture.refresh.close();
            }
        });
    }

    @Test
    void 写回执前多次失效合并并在操作完成后保留草稿补刷() {
        Fixture fixture = FxTestSupport.call(Fixture::new);
        try {
            FxTestSupport.run(() -> {
                fixture.refresh.activate();
                fixture.reads.getFirst().complete(true);
                fixture.page.pending = true;
                fixture.gateway.changed(DesktopConfigurationChange.Kind.PROVIDERS);
                fixture.gateway.changed(DesktopConfigurationChange.Kind.PROVIDERS);
            });
            FxTestSupport.run(() -> {
                assertEquals(List.of(false), fixture.preserved);
                fixture.page.dirty = true;
                fixture.page.pending = false;
            });
            FxTestSupport.await(() -> FxTestSupport.call(() -> fixture.preserved.size() == 2));
            FxTestSupport.run(() -> assertEquals(List.of(false, true), fixture.preserved));
        } finally {
            FxTestSupport.run(fixture.refresh::close);
        }
    }

    @Test
    void 隐藏时只记失效且重新激活刷新而无关事件不刷新() {
        Fixture fixture = FxTestSupport.call(Fixture::new);
        try {
            FxTestSupport.run(() -> {
                fixture.refresh.activate();
                fixture.reads.getFirst().complete(true);
                fixture.gateway.changed(DesktopConfigurationChange.Kind.ROLES);
                fixture.refresh.deactivate();
                fixture.gateway.changed(DesktopConfigurationChange.Kind.PROVIDERS);
                fixture.gateway.changed(DesktopConfigurationChange.Kind.PROVIDERS);
            });
            FxTestSupport.run(() -> {
                assertEquals(List.of(false), fixture.preserved);
                fixture.refresh.activate();
                assertEquals(List.of(false, false), fixture.preserved);
            });
        } finally {
            FxTestSupport.run(fixture.refresh::close);
        }
    }

    @Test
    void 关闭取消订阅及已排队重验并且重复关闭安全() {
        Fixture fixture = FxTestSupport.call(Fixture::new);
        FxTestSupport.run(() -> {
            fixture.refresh.activate();
            fixture.reads.getFirst().complete(true);
            fixture.page.pending = true;
            fixture.gateway.changed(DesktopConfigurationChange.Kind.PROVIDERS);
        });
        FxTestSupport.run(() -> {
            fixture.refresh.close();
            fixture.refresh.close();
            fixture.page.pending = false;
            fixture.gateway.changed(DesktopConfigurationChange.Kind.PROVIDERS);
            fixture.refresh.activate();
        });
        FxTestSupport.run(() -> assertEquals(List.of(false), fixture.preserved));
    }

    private static final class Fixture {
        private final Gateway gateway = new Gateway();
        private final Page page = new Page();
        private final AtomicLong clock = new AtomicLong();
        private final List<CompletableFuture<Boolean>> reads = new ArrayList<>();
        private final List<Boolean> preserved = new ArrayList<>();
        private final SettingsPageRefresh refresh = new SettingsPageRefresh(
                gateway,
                Set.of(DesktopConfigurationChange.Kind.PROVIDERS),
                page,
                preserve -> {
                    preserved.add(preserve);
                    CompletableFuture<Boolean> result = new CompletableFuture<>();
                    reads.add(result);
                    return result;
                },
                new SettingsCacheFreshness(clock::get),
                () -> true);
    }

    private static final class Gateway extends TestCoreSettingsGateway {
        private final DesktopConfigurationEvents events = new DesktopConfigurationEvents();

        @Override
        public DesktopNotificationSubscription onConfigurationChanged(Consumer<DesktopConfigurationChange> listener) {
            return events.subscribe(listener);
        }

        private void changed() {
            changed(DesktopConfigurationChange.Kind.PROVIDERS);
        }

        private void changed(DesktopConfigurationChange.Kind kind) {
            events.publish(new DesktopConfigurationChange(kind, Optional.empty(), Optional.empty()));
        }
    }

    private static final class Page implements ManagedSettingsPage {
        private final VBox content = new VBox();
        private boolean dirty;
        private boolean pending;

        @Override
        public Node content() {
            return content;
        }

        @Override
        public void activate() {}

        @Override
        public boolean dirty() {
            return dirty;
        }

        @Override
        public boolean pending() {
            return pending;
        }

        @Override
        public void warnUnsavedChanges() {}

        @Override
        public void discardDraft() {}
    }
}
