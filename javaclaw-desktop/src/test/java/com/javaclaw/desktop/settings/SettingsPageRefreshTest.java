package com.javaclaw.desktop.settings;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
    void 写回执前多次失效合并并在操作完成后保留草稿补刷() {
        Fixture fixture = FxTestSupport.call(Fixture::new);
        try {
            FxTestSupport.run(() -> {
                fixture.refresh.activate();
                fixture.page.pending = true;
                fixture.changed(DesktopConfigurationChange.Kind.PROVIDERS);
                fixture.changed(DesktopConfigurationChange.Kind.PROVIDERS);
            });
            FxTestSupport.run(() -> {
                assertEquals(List.of(false), fixture.calls);
                fixture.page.dirty = true;
                fixture.page.pending = false;
            });
            FxTestSupport.await(() -> FxTestSupport.call(() -> fixture.calls.size() == 2));
            FxTestSupport.run(() -> assertEquals(List.of(false, true), fixture.calls));
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
                fixture.changed(DesktopConfigurationChange.Kind.ROLES);
                fixture.refresh.deactivate();
                fixture.changed(DesktopConfigurationChange.Kind.PROVIDERS);
                fixture.changed(DesktopConfigurationChange.Kind.PROVIDERS);
            });
            FxTestSupport.run(() -> {
                assertEquals(List.of(false), fixture.calls);
                fixture.refresh.activate();
                assertEquals(List.of(false, false), fixture.calls);
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
            fixture.page.pending = true;
            fixture.changed(DesktopConfigurationChange.Kind.PROVIDERS);
        });
        FxTestSupport.run(() -> {
            fixture.refresh.close();
            fixture.refresh.close();
            fixture.page.pending = false;
            fixture.changed(DesktopConfigurationChange.Kind.PROVIDERS);
            fixture.refresh.activate();
        });
        FxTestSupport.run(() -> assertEquals(List.of(false), fixture.calls));
    }

    private static final class Fixture {
        private final EventGateway gateway = new EventGateway();
        private final Page page = new Page();
        private final List<Boolean> calls = new ArrayList<>();
        private final SettingsPageRefresh refresh =
                new SettingsPageRefresh(gateway, Set.of(DesktopConfigurationChange.Kind.PROVIDERS), page, calls::add);

        private void changed(DesktopConfigurationChange.Kind kind) {
            gateway.events.publish(new DesktopConfigurationChange(kind, Optional.empty(), Optional.empty()));
        }
    }

    private static final class EventGateway extends TestCoreSettingsGateway {
        private final DesktopConfigurationEvents events = new DesktopConfigurationEvents();

        @Override
        public DesktopNotificationSubscription onConfigurationChanged(Consumer<DesktopConfigurationChange> listener) {
            return events.subscribe(listener);
        }
    }

    private static final class Page implements ManagedSettingsPage {
        private final VBox content = new VBox();
        private boolean pending;
        private boolean dirty;

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
