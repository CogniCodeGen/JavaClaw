package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.AgentProfile;
import com.javaclaw.api.ProviderEndpoint;

/** Provider 新版本与仍引用旧版本的 Agent Profile 列表状态。 */
record ProviderProfileReferenceState(
        SettingsLoadState phase,
        Optional<ProviderEndpoint> provider,
        List<AgentProfile> staleProfiles,
        Optional<AgentProfile> selected,
        String message,
        long epoch) {
    ProviderProfileReferenceState {
        phase = Objects.requireNonNull(phase, "phase");
        provider = Objects.requireNonNull(provider, "provider");
        staleProfiles = List.copyOf(staleProfiles);
        selected = Objects.requireNonNull(selected, "selected");
        message = Objects.requireNonNullElse(message, "");
    }

    static ProviderProfileReferenceState initial() {
        return new ProviderProfileReferenceState(
                SettingsLoadState.INITIAL, Optional.empty(), List.of(), Optional.empty(), "", 0);
    }

    boolean pending() {
        return phase == SettingsLoadState.LOADING || phase == SettingsLoadState.SAVING;
    }
}
