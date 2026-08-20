package com.javaclaw.application.plugin;

import com.javaclaw.application.error.ConflictException;
import com.javaclaw.application.error.NotFoundException;
import com.javaclaw.application.error.RejectedException;
import com.javaclaw.application.plugin.PluginManagementApplicationService.Catalog;
import com.javaclaw.application.plugin.PluginManagementApplicationService.ConfigField;
import com.javaclaw.application.plugin.PluginManagementApplicationService.Plugin;
import com.javaclaw.application.plugin.PluginManagementApplicationService.State;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginManagementUseCaseTest {

    @Test
    void refreshAndToggleReturnImmutableCatalogs() {
        FakePort port = new FakePort();
        PluginManagementUseCase useCase = new PluginManagementUseCase(port);

        Catalog refreshed = useCase.refresh();
        Catalog enabled = useCase.setEnabled("demo", true);

        assertEquals(1, port.refreshes);
        assertFalse(refreshed.require("demo").active());
        assertTrue(enabled.require("demo").active());
        assertThrows(UnsupportedOperationException.class,
                () -> enabled.plugins().add(plugin("other", State.STOPPED)));
    }

    @Test
    void installRejectsInvalidDescriptorAndReturnsInstalledSelection() {
        FakePort port = new FakePort();
        PluginManagementUseCase useCase = new PluginManagementUseCase(port);
        port.installResult = null;

        assertThrows(RejectedException.class,
                () -> useCase.install(Path.of("broken.jar")));

        port.installResult = "installed";
        PluginManagementApplicationService.InstallResult result =
                useCase.install(Path.of("plugin.jar"));
        assertEquals("installed", result.pluginId());
        assertEquals("installed", result.catalog().require("installed").id());
    }

    @Test
    void detailsAndConfigurationDoNotExposeMutableMaps() {
        FakePort port = new FakePort();
        PluginManagementUseCase useCase = new PluginManagementUseCase(port);

        PluginManagementApplicationService.Details details = useCase.details("demo");
        assertEquals("secret", details.configValues().get("token"));
        assertThrows(UnsupportedOperationException.class,
                () -> details.configValues().put("token", "changed"));

        useCase.saveConfig("demo", Map.of("token", "new"));
        assertEquals(Map.of("token", "new"), port.savedConfig);
    }

    @Test
    void uninstallFailureHasConflictSemanticsAndSubscriptionIsClosable() throws Exception {
        FakePort port = new FakePort();
        PluginManagementUseCase useCase = new PluginManagementUseCase(port);
        port.uninstallResult = false;
        assertThrows(ConflictException.class, () -> useCase.uninstall("demo"));

        AtomicBoolean called = new AtomicBoolean();
        AutoCloseable subscription = useCase.onCatalogChanged(() -> called.set(true));
        port.listener.run();
        subscription.close();
        assertTrue(called.get());
        assertTrue(port.subscriptionClosed);
    }

    @Test
    void noOpAndRejectedTransitionsHaveExplicitSemantics() {
        FakePort port = new FakePort();
        PluginManagementUseCase useCase = new PluginManagementUseCase(port);

        assertFalse(useCase.setEnabled("demo", false).require("demo").active());
        port.ignoreTransitions = true;
        assertThrows(RejectedException.class, () -> useCase.setEnabled("demo", true));
        port.transitionError = "缺少权限";
        assertTrue(assertThrows(RejectedException.class,
                () -> useCase.setEnabled("demo", true)).getMessage().contains("缺少权限"));

        port.ignoreTransitions = false;
        assertTrue(useCase.setEnabled("demo", true).require("demo").active());
        assertTrue(useCase.setEnabled("demo", true).require("demo").active());
        port.ignoreTransitions = true;
        assertThrows(ConflictException.class, () -> useCase.setEnabled("demo", false));
    }

    @Test
    void onlyPendingServicePluginsCanBeExplicitlyApproved() {
        FakePort port = new FakePort();
        port.plugins.clear();
        port.plugins.add(plugin("service", State.PENDING_APPROVAL));
        PluginManagementUseCase useCase = new PluginManagementUseCase(port);

        port.approvalAllowed = false;
        var rejected = useCase.approveServicePlugin("service");
        assertFalse(rejected.approved());
        assertEquals(State.PENDING_APPROVAL,
                rejected.catalog().require("service").state());

        port.approvalAllowed = true;
        var approved = useCase.approveServicePlugin("service");
        assertTrue(approved.approved());
        assertTrue(approved.catalog().plugins().isEmpty());
        assertEquals(2, port.approvalCalls);

        port.plugins.add(plugin("plain", State.STOPPED));
        assertThrows(ConflictException.class,
                () -> useCase.approveServicePlugin("plain"));
    }

    @Test
    void successfulUninstallNullConfigAndDirectoriesRemainDefensive() {
        FakePort port = new FakePort();
        PluginManagementUseCase useCase = new PluginManagementUseCase(port);

        useCase.saveConfig("demo", null);
        assertEquals(Map.of(), port.savedConfig);
        assertEquals(Path.of("plugins").toAbsolutePath().normalize(), useCase.pluginsDirectory());
        assertTrue(useCase.uninstall("demo").plugins().isEmpty());
        assertThrows(NotFoundException.class, () -> useCase.details("missing"));

        port.plugins.add(pluginWithoutConfig("plain"));
        assertThrows(ConflictException.class, () -> useCase.saveConfig("plain", Map.of()));
        assertThrows(NullPointerException.class, () -> useCase.install(null));
        assertThrows(NullPointerException.class, () -> useCase.onCatalogChanged(null));
        assertThrows(NullPointerException.class, () -> new PluginManagementUseCase(null));
    }

    @Test
    void blankInstalledIdsAreRejectedLikeMissingDescriptors() {
        FakePort port = new FakePort();
        port.installResult = " ";
        PluginManagementUseCase useCase = new PluginManagementUseCase(port);

        assertThrows(RejectedException.class, () -> useCase.install(Path.of("blank.jar")));
    }

    private static Plugin plugin(String id, State state) {
        return new Plugin(id, id, "1.0.0", "description", List.of(),
                List.of(new ConfigField("token", "Token", true)),
                List.of(), List.of(), state, "");
    }

    private static Plugin pluginWithoutConfig(String id) {
        return new Plugin(id, id, "1.0.0", "description", List.of(),
                List.of(), List.of(), List.of(), State.STOPPED, "");
    }

    private static final class FakePort implements PluginManagementPort {
        private final List<Plugin> plugins = new ArrayList<>(List.of(plugin("demo", State.STOPPED)));
        private int refreshes;
        private String installResult = "installed";
        private boolean uninstallResult = true;
        private Map<String, String> savedConfig;
        private Runnable listener;
        private boolean subscriptionClosed;
        private boolean ignoreTransitions;
        private boolean approvalAllowed = true;
        private int approvalCalls;
        private String transitionError = "";

        @Override public List<Plugin> list() { return List.copyOf(plugins); }
        @Override public void refresh() { refreshes++; }

        @Override
        public void setEnabled(String pluginId, boolean enabled) {
            Plugin old = plugins.removeFirst();
            plugins.add(new Plugin(old.id(), old.name(), old.version(), old.description(),
                    old.permissions(), old.config(), old.skills(), old.tools(),
                    ignoreTransitions ? old.state() : enabled ? State.ACTIVE : State.STOPPED,
                    transitionError));
        }

        @Override
        public boolean approveServicePlugin(String pluginId) {
            approvalCalls++;
            if (approvalAllowed) {
                plugins.removeIf(plugin -> plugin.id().equals(pluginId));
            }
            return approvalAllowed;
        }

        @Override
        public String install(Path jar) {
            if (installResult != null && !installResult.isBlank()) {
                plugins.add(plugin(installResult, State.STOPPED));
            }
            return installResult;
        }

        @Override
        public boolean uninstall(String pluginId) {
            if (uninstallResult) plugins.removeIf(plugin -> plugin.id().equals(pluginId));
            return uninstallResult;
        }

        @Override public Map<String, String> config(String pluginId) {
            return Map.of("token", "secret");
        }
        @Override public void saveConfig(String pluginId, Map<String, String> values) {
            savedConfig = values;
        }
        @Override public Path pluginsDirectory() { return Path.of("plugins"); }

        @Override
        public AutoCloseable onChanged(Runnable listener) {
            this.listener = listener;
            return () -> subscriptionClosed = true;
        }
    }
}
