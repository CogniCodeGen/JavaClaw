package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import com.javaclaw.api.AgentProfile;
import com.javaclaw.api.AgentProfileSpec;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.client.CommandOptions;

/** 查询并显式更新仍引用旧 Provider revision 的 Agent Profile。 */
final class ProviderProfileReferencePresenter {
    private final CoreSettingsGateway gateway;
    private Consumer<ProviderProfileReferenceState> listener = ignored -> {};
    private ProviderProfileReferenceState state = ProviderProfileReferenceState.initial();

    ProviderProfileReferencePresenter(CoreSettingsGateway gateway) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
    }

    void subscribe(Consumer<ProviderProfileReferenceState> value) {
        listener = Objects.requireNonNull(value, "listener");
        listener.accept(state);
    }

    void bind(Optional<ProviderEndpoint> provider) {
        Optional<ProviderEndpoint> checked = Objects.requireNonNull(provider, "provider");
        if (sameProvider(state.provider(), checked)) {
            return;
        }
        long epoch = state.epoch() + 1;
        publish(new ProviderProfileReferenceState(
                SettingsLoadState.LOADING, checked, List.of(), Optional.empty(), "正在检查旧版本引用…", epoch));
        if (checked.isEmpty()) {
            publish(new ProviderProfileReferenceState(
                    SettingsLoadState.READY, checked, List.of(), Optional.empty(), "保存模型服务后可检查引用", epoch));
            return;
        }
        gateway.profiles().whenComplete((profiles, failure) -> completeLoad(epoch, profiles, failure));
    }

    void select(AgentProfile profile) {
        publish(new ProviderProfileReferenceState(
                state.phase(),
                state.provider(),
                state.staleProfiles(),
                Optional.ofNullable(profile),
                state.message(),
                state.epoch()));
    }

    void updateSelected() {
        ProviderEndpoint provider = state.provider().orElseThrow();
        AgentProfile profile = state.selected().orElseThrow();
        String modelId = profile.spec().provider().model();
        boolean available = provider.spec().models().stream()
                .anyMatch(model -> model.modelId().equals(modelId) && model.supports(ProviderModelPurpose.CHAT));
        if (!available) {
            publish(copy(SettingsLoadState.ERROR, "新版本中没有该对话模型，无法更新引用"));
            return;
        }
        AgentProfileSpec previous = profile.spec();
        AgentProfileSpec updated = new AgentProfileSpec(
                previous.displayName(),
                previous.systemInstruction(),
                new ProviderRef(provider.id(), provider.revision(), modelId),
                previous.permissionProfile(),
                previous.visibleTools(),
                previous.budget());
        long epoch = state.epoch() + 1;
        publish(new ProviderProfileReferenceState(
                SettingsLoadState.SAVING,
                state.provider(),
                state.staleProfiles(),
                state.selected(),
                "正在创建智能体方案新版本…",
                epoch));
        gateway.updateProfile(profile.id(), updated, profile.lifecycle(), CommandOptions.create(profile.revision()))
                .whenComplete((saved, failure) -> completeUpdate(epoch, saved, failure));
    }

    ProviderProfileReferenceState state() {
        return state;
    }

    private void completeLoad(long epoch, List<AgentProfile> profiles, Throwable failure) {
        if (epoch != state.epoch()) {
            return;
        }
        if (failure != null) {
            publish(copy(SettingsLoadState.ERROR, SettingsFailures.message(failure)));
            return;
        }
        ProviderEndpoint provider = state.provider().orElseThrow();
        List<AgentProfile> stale = List.copyOf(profiles).stream()
                .filter(profile -> profile.spec().provider().endpointId().equals(provider.id()))
                .filter(profile -> profile.spec().provider().endpointRevision() != provider.revision())
                .toList();
        publish(new ProviderProfileReferenceState(
                SettingsLoadState.READY,
                state.provider(),
                stale,
                stale.stream().findFirst(),
                stale.isEmpty() ? "没有智能体方案引用旧版本" : "请选择需要更新的智能体方案；不会自动修改其他引用",
                epoch));
    }

    private void completeUpdate(long epoch, AgentProfile updated, Throwable failure) {
        if (epoch != state.epoch()) {
            return;
        }
        if (failure != null) {
            publish(copy(SettingsLoadState.ERROR, SettingsFailures.message(failure)));
            return;
        }
        List<AgentProfile> remaining = state.staleProfiles().stream()
                .filter(profile -> !profile.id().equals(updated.id()))
                .toList();
        publish(new ProviderProfileReferenceState(
                SettingsLoadState.READY,
                state.provider(),
                remaining,
                remaining.stream().findFirst(),
                "已创建智能体方案版本 " + updated.revision() + "；其他引用未改变",
                state.epoch()));
    }

    private ProviderProfileReferenceState copy(SettingsLoadState phase, String message) {
        return new ProviderProfileReferenceState(
                phase, state.provider(), state.staleProfiles(), state.selected(), message, state.epoch());
    }

    private void publish(ProviderProfileReferenceState next) {
        state = Objects.requireNonNull(next, "next");
        listener.accept(next);
    }

    private static boolean sameProvider(Optional<ProviderEndpoint> first, Optional<ProviderEndpoint> second) {
        return first.map(value -> value.id() + '@' + value.revision())
                .equals(second.map(value -> value.id() + '@' + value.revision()));
    }
}
