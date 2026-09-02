package com.javaclaw.desktop.settings;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Function;

import com.javaclaw.api.CredentialClearReceipt;
import com.javaclaw.api.CredentialMetadata;
import com.javaclaw.api.CredentialRef;
import com.javaclaw.builtin.contracts.SiteContracts;
import com.javaclaw.client.CommandOptions;

/** 协调 Site 命名空间 Secret 的创建、轮换、永久清除与脱敏目录。 */
final class SiteCredentialSettingsPresenter {
    private final CoreSettingsGateway gateway;
    private final Runnable authorityRefresh;
    private Consumer<SiteCredentialSettingsState> listener = ignored -> {};
    private SiteCredentialSettingsState state = SiteCredentialSettingsState.initial();

    SiteCredentialSettingsPresenter(CoreSettingsGateway gateway, Runnable authorityRefresh) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.authorityRefresh = Objects.requireNonNull(authorityRefresh, "authorityRefresh");
    }

    void subscribe(Consumer<SiteCredentialSettingsState> value) {
        listener = Objects.requireNonNull(value, "listener");
        listener.accept(state);
    }

    void reload() {
        long epoch = nextEpoch();
        publish(copy(SettingsLoadState.LOADING, "正在读取 Site 凭据元数据…", epoch));
        gateway.credentials(SiteContracts.SITE_CREDENTIAL_NAMESPACE)
                .whenComplete((credentials, failure) -> completeReload(epoch, credentials, failure));
    }

    void select(CredentialMetadata credential) {
        CredentialMetadata checked = Objects.requireNonNull(credential, "credential");
        if (!state.credentials().contains(checked)) {
            throw new IllegalArgumentException("Site 凭据不在当前脱敏目录中");
        }
        publish(new SiteCredentialSettingsState(
                state.phase(), state.credentials(), Optional.of(checked), state.message(), state.epoch()));
    }

    void create(char[] secret) {
        submitSecret(
                secret,
                "正在创建 Site 凭据…",
                value -> gateway.createCredential(
                        SiteContracts.SITE_CREDENTIAL_NAMESPACE, value, CommandOptions.create(0)));
    }

    void rotate(char[] secret) {
        CredentialMetadata current = selected();
        submitSecret(
                secret,
                "正在轮换 Site 凭据…",
                value -> gateway.rotateCredential(
                        current.reference(), value, CommandOptions.create(current.revision())));
    }

    void clear() {
        CredentialMetadata current = selected();
        long epoch = nextEpoch();
        publish(copy(SettingsLoadState.SAVING, "正在永久清除 Site 凭据…", epoch));
        gateway.clearCredential(current.reference(), CommandOptions.create(current.revision()))
                .whenComplete((receipt, failure) -> completeClear(epoch, current, receipt, failure));
    }

    SiteCredentialSettingsState state() {
        return state;
    }

    private void submitSecret(
            char[] secret, String message, Function<char[], CompletionStage<CredentialMetadata>> operation) {
        char[] checked = Objects.requireNonNull(secret, "secret");
        if (checked.length == 0) {
            publish(copy(SettingsLoadState.ERROR, "Secret 不能为空", state.epoch()));
            return;
        }
        long epoch = nextEpoch();
        publish(copy(SettingsLoadState.SAVING, message, epoch));
        CompletionStage<CredentialMetadata> request;
        try {
            request = Objects.requireNonNull(operation.apply(checked), "request");
        } catch (RuntimeException failure) {
            fail(epoch, failure);
            return;
        } finally {
            Arrays.fill(checked, '\0');
        }
        request.whenComplete((credential, failure) -> completeWrite(epoch, credential, failure));
    }

    private void completeReload(long epoch, List<CredentialMetadata> credentials, Throwable failure) {
        if (stale(epoch)) {
            return;
        }
        if (failure != null) {
            fail(epoch, failure);
            return;
        }
        List<CredentialMetadata> loaded = List.copyOf(credentials).stream()
                .filter(this::isSiteCredential)
                .sorted(Comparator.comparing(value -> value.reference().id()))
                .toList();
        Optional<CredentialMetadata> selected = state.selected()
                .flatMap(current -> find(loaded, current.reference()))
                .or(() -> loaded.stream().findFirst());
        publish(new SiteCredentialSettingsState(SettingsLoadState.READY, loaded, selected, "", epoch));
    }

    private void completeWrite(long epoch, CredentialMetadata credential, Throwable failure) {
        if (stale(epoch)) {
            return;
        }
        if (failure != null) {
            fail(epoch, failure);
            return;
        }
        CredentialMetadata checked = requireSiteCredential(credential);
        List<CredentialMetadata> updated = state.credentials().stream()
                .filter(candidate -> !candidate.reference().equals(checked.reference()))
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        updated.add(checked);
        updated.sort(Comparator.comparing(value -> value.reference().id()));
        publish(new SiteCredentialSettingsState(
                SettingsLoadState.READY, updated, Optional.of(checked), "Site 凭据已保存；Secret 不可回读", epoch));
        authorityRefresh.run();
    }

    private void completeClear(
            long epoch, CredentialMetadata current, CredentialClearReceipt receipt, Throwable failure) {
        if (stale(epoch)) {
            return;
        }
        if (failure != null) {
            fail(epoch, failure);
            return;
        }
        CredentialClearReceipt checked = Objects.requireNonNull(receipt, "receipt");
        if (!checked.reference().equals(current.reference()) || checked.clearedRevision() != current.revision()) {
            fail(epoch, new IllegalStateException("Credential 清除回执与请求不一致"));
            return;
        }
        List<CredentialMetadata> updated = state.credentials().stream()
                .filter(candidate -> !candidate.reference().equals(current.reference()))
                .toList();
        publish(new SiteCredentialSettingsState(
                SettingsLoadState.READY, updated, updated.stream().findFirst(), "Site 凭据已永久清除；引用立即失效", epoch));
        authorityRefresh.run();
    }

    private CredentialMetadata selected() {
        return state.selected().orElseThrow(() -> new IllegalStateException("请先选择 Site 凭据"));
    }

    private CredentialMetadata requireSiteCredential(CredentialMetadata value) {
        CredentialMetadata checked = Objects.requireNonNull(value, "credential");
        if (!isSiteCredential(checked)) {
            throw new IllegalArgumentException("服务端返回了非 Site 命名空间凭据");
        }
        return checked;
    }

    private boolean isSiteCredential(CredentialMetadata value) {
        return SiteContracts.SITE_CREDENTIAL_NAMESPACE.equals(value.reference().namespace());
    }

    private Optional<CredentialMetadata> find(List<CredentialMetadata> credentials, CredentialRef reference) {
        return credentials.stream()
                .filter(value -> value.reference().equals(reference))
                .findFirst();
    }

    private void fail(long epoch, Throwable failure) {
        publish(copy(SettingsLoadState.ERROR, SettingsFailures.message(failure), epoch));
    }

    private SiteCredentialSettingsState copy(SettingsLoadState phase, String message, long epoch) {
        return new SiteCredentialSettingsState(phase, state.credentials(), state.selected(), message, epoch);
    }

    private long nextEpoch() {
        return state.epoch() + 1;
    }

    private boolean stale(long epoch) {
        return epoch != state.epoch();
    }

    private void publish(SiteCredentialSettingsState next) {
        state = Objects.requireNonNull(next, "next");
        listener.accept(state);
    }
}
