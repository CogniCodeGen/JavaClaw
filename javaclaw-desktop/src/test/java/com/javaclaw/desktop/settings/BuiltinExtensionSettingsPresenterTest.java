package com.javaclaw.desktop.settings;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.client.RemoteRpcException;
import com.javaclaw.extension.spi.ContributionKind;
import com.javaclaw.extension.spi.ExtensionAvailability;
import com.javaclaw.extension.spi.ExtensionState;
import com.javaclaw.protocol.BuiltinExtensionRpcContracts;
import com.javaclaw.protocol.JsonRpcError;
import com.javaclaw.protocol.ProtocolErrorCode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuiltinExtensionSettingsPresenterTest {
    @Test
    void 可选能力启停使用权威结果并保留数据目录选择() {
        FakeGateway gateway = new FakeGateway();
        gateway.extensions.add(status("com.javaclaw.plan", ExtensionAvailability.OPTIONAL, ExtensionState.ENABLED, 1));
        BuiltinExtensionSettingsPresenter presenter = new BuiltinExtensionSettingsPresenter(gateway);
        AtomicReference<BuiltinExtensionSettingsState> latest = new AtomicReference<>();
        presenter.subscribe(latest::set);

        presenter.reload();
        assertEquals(SettingsLoadState.READY, latest.get().phase());
        presenter.setEnabled(false);

        assertEquals(
                ExtensionState.DISABLED, latest.get().selected().orElseThrow().state());
        assertEquals(2, latest.get().selected().orElseThrow().stateRevision());
        assertEquals("内置能力已停用", latest.get().message());
        assertFalse(latest.get().pending());
    }

    @Test
    void 必需能力不伪造停用且冲突保持当前快照() {
        FakeGateway gateway = new FakeGateway();
        BuiltinExtensionRpcContracts.Status required =
                status("com.javaclaw.required", ExtensionAvailability.REQUIRED, ExtensionState.ENABLED, 1);
        gateway.extensions.add(required);
        BuiltinExtensionSettingsPresenter presenter = new BuiltinExtensionSettingsPresenter(gateway);
        AtomicReference<BuiltinExtensionSettingsState> latest = new AtomicReference<>();
        presenter.subscribe(latest::set);
        presenter.reload();

        presenter.setEnabled(false);
        assertEquals(SettingsLoadState.ERROR, latest.get().phase());
        assertEquals(
                ExtensionState.ENABLED, latest.get().selected().orElseThrow().state());

        gateway.extensions.set(0, status(required.id(), ExtensionAvailability.OPTIONAL, ExtensionState.ENABLED, 1));
        presenter.reload();
        gateway.failure = revisionConflict();
        presenter.setEnabled(false);
        assertTrue(latest.get().revisionConflict());
        assertEquals(
                ExtensionState.ENABLED, latest.get().selected().orElseThrow().state());
    }

    private static BuiltinExtensionRpcContracts.Status status(
            String id, ExtensionAvailability availability, ExtensionState state, long revision) {
        return new BuiltinExtensionRpcContracts.Status(
                id,
                id,
                "5.0.0",
                1,
                revision,
                availability,
                state,
                BuiltinExtensionRpcContracts.RuntimeKind.BUNDLE,
                Set.of(ContributionKind.COMMAND, ContributionKind.VIEW),
                Instant.parse("2026-09-01T00:00:00Z"));
    }

    private static RemoteRpcException revisionConflict() {
        return new RemoteRpcException(new JsonRpcError(
                ProtocolErrorCode.REVISION_CONFLICT, "revision 已变化", Optional.of(new CanonicalPayload("{}"))));
    }

    private static final class FakeGateway implements BuiltinExtensionSettingsGateway {
        private final List<BuiltinExtensionRpcContracts.Status> extensions = new ArrayList<>();
        private RuntimeException failure;

        @Override
        public CompletionStage<List<BuiltinExtensionRpcContracts.Status>> builtinExtensions() {
            return CompletableFuture.completedFuture(List.copyOf(extensions));
        }

        @Override
        public CompletionStage<BuiltinExtensionRpcContracts.Status> setBuiltinExtensionEnabled(
                BuiltinExtensionRpcContracts.Status current, boolean enabled) {
            if (failure != null) {
                RuntimeException next = failure;
                failure = null;
                return CompletableFuture.failedFuture(next);
            }
            BuiltinExtensionRpcContracts.Status updated = new BuiltinExtensionRpcContracts.Status(
                    current.id(),
                    current.displayName(),
                    current.version(),
                    current.descriptorRevision(),
                    current.stateRevision() + 1,
                    current.availability(),
                    enabled ? ExtensionState.ENABLED : ExtensionState.DISABLED,
                    current.runtimeKind(),
                    current.contributionKinds(),
                    current.updatedAt());
            extensions.set(extensions.indexOf(current), updated);
            return CompletableFuture.completedFuture(updated);
        }
    }
}
