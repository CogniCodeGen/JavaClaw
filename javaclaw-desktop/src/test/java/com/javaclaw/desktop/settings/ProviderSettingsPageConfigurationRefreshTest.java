package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

import javafx.scene.Scene;
import javafx.scene.control.ListView;
import javafx.scene.layout.BorderPane;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.desktop.DesktopConfigurationChange;
import com.javaclaw.desktop.DesktopConfigurationEvents;
import com.javaclaw.desktop.DesktopNotificationSubscription;
import com.javaclaw.desktop.FxTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProviderSettingsPageConfigurationRefreshTest {
    @Test
    void 页面持续显示时模型保存通知直接更新目录而隐藏后在重开时补刷() {
        Fixture fixture = FxTestSupport.call(Fixture::new);
        try {
            FxTestSupport.run(() -> fixture.gateway.changed(2));
            FxTestSupport.await(() -> FxTestSupport.call(() -> fixture.revision() == 2));
            FxTestSupport.run(() -> {
                assertEquals(2, fixture.gateway.reads);
                fixture.page.deactivate();
                fixture.gateway.changed(3);
            });
            FxTestSupport.run(() -> {
                assertEquals(2, fixture.gateway.reads);
                assertEquals(2, fixture.revision());
                fixture.page.activate();
                assertEquals(3, fixture.gateway.reads);
                assertEquals(3, fixture.revision());
            });
        } finally {
            FxTestSupport.run(fixture.page::dispose);
        }
        FxTestSupport.run(() -> fixture.gateway.changed(4));
        FxTestSupport.run(() -> assertEquals(3, fixture.gateway.reads));
    }

    private static final class Fixture {
        private final Gateway gateway = new Gateway();
        private final ProviderSettingsPage page = new ProviderSettingsPage(gateway);
        private final BorderPane root = new BorderPane(page.content());

        private Fixture() {
            root.setBottom(page.actionContent().orElseThrow());
            new Scene(root, 1040, 720);
            page.activate();
            root.applyCss();
            root.layout();
        }

        private long revision() {
            ListView<?> list = (ListView<?>) root.lookup(".platform-data-list");
            return ((ProviderEndpoint) list.getItems().getFirst()).revision();
        }
    }

    private static final class Gateway extends TestCoreSettingsGateway {
        private final DesktopConfigurationEvents events = new DesktopConfigurationEvents();
        private int reads;

        @Override
        public DesktopNotificationSubscription onConfigurationChanged(Consumer<DesktopConfigurationChange> listener) {
            return events.subscribe(listener);
        }

        @Override
        public CompletionStage<List<ProviderEndpoint>> providers() {
            reads++;
            return super.providers();
        }

        private void changed(long revision) {
            providers.set(
                    0,
                    TestCoreSettingsFixtures.provider(
                            revision,
                            TestCoreSettingsFixtures.providerSpec(Optional.empty()),
                            ProviderLifecycle.ACTIVE));
            events.publish(new DesktopConfigurationChange(
                    DesktopConfigurationChange.Kind.PROVIDERS, Optional.empty(), Optional.empty()));
        }
    }
}
