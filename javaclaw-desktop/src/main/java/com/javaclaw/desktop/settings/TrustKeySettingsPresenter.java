package com.javaclaw.desktop.settings;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import com.javaclaw.protocol.BundleRpcContracts;

/** Trust Key Attachment 上传、指纹确认、导入与实时撤销状态机。 */
public final class TrustKeySettingsPresenter {
    private final BundleSettingsGateway gateway;
    private Consumer<TrustKeySettingsState> listener = ignored -> {};
    private TrustKeySettingsState state = TrustKeySettingsState.initial();

    /** @param gateway 强类型 SDK 设置边界 */
    public TrustKeySettingsPresenter(BundleSettingsGateway gateway) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
    }

    /** @param value 完整状态订阅者；注册后立即收到当前快照 */
    public void subscribe(Consumer<TrustKeySettingsState> value) {
        listener = Objects.requireNonNull(value, "listener");
        listener.accept(state);
    }

    /** 读取 Trust Key；本地指纹草稿不会被静默覆盖。 */
    public void reload() {
        if (state.dirty()) {
            warnUnsavedChanges();
            return;
        }
        loadCatalog(Optional.empty(), "正在读取信任公钥…", "");
    }

    /** @param key 选择的 Trust Key */
    public void select(BundleRpcContracts.TrustKey key) {
        if (state.dirty()) {
            warnUnsavedChanges();
            return;
        }
        publish(copy(state.phase(), Optional.ofNullable(key), state.keyId(), state.draft(), "", false, state.epoch()));
    }

    /** @param value manifest 使用的 signingKeyId */
    public void updateKeyId(String value) {
        publish(copy(
                SettingsLoadState.READY,
                state.selected(),
                Objects.requireNonNullElse(value, ""),
                state.draft(),
                "公钥导入草稿尚未提交",
                false,
                state.epoch()));
    }

    /** @param file 用户选择的 DER 或 Base64 DER 公钥 */
    public void prepare(Path file) {
        long epoch = begin("正在上传公钥附件并计算规范指纹…");
        gateway.prepareTrustKey(file).whenComplete((draft, failure) -> completePrepare(epoch, draft, failure));
    }

    /** 用户已在页面核对指纹后导入。 */
    public void importPrepared() {
        String keyId;
        try {
            keyId = requireKeyId();
        } catch (IllegalArgumentException failure) {
            fail(state.epoch(), failure);
            return;
        }
        TrustKeyImportDraft draft = state.draft().orElseThrow();
        long epoch = begin("正在导入已确认指纹的信任公钥…");
        gateway.importTrustKey(keyId, draft)
                .whenComplete((key, failure) -> completeWrite(epoch, key, "信任公钥已导入", failure));
    }

    /** 实时撤销所选 Trust Key，并由服务端禁用其签名 Bundle。 */
    public void revoke() {
        if (state.dirty()) {
            throw new IllegalStateException("请先丢弃导入草稿");
        }
        BundleRpcContracts.TrustKey key = state.selected().orElseThrow();
        long epoch = begin("正在撤销信任公钥并停用关联扩展包…");
        gateway.revokeTrustKey(key)
                .whenComplete((updated, failure) -> completeWrite(epoch, updated, "信任公钥已撤销", failure));
    }

    /** 丢弃本地导入标识和指纹草稿。 */
    public void discardDraft() {
        publish(copy(SettingsLoadState.READY, state.selected(), "", Optional.empty(), "导入草稿已丢弃", false, state.epoch()));
    }

    /** 显示统一离页保护消息。 */
    public void warnUnsavedChanges() {
        publish(copy(
                state.phase(),
                state.selected(),
                state.keyId(),
                state.draft(),
                "请先确认导入或丢弃信任公钥草稿",
                false,
                state.epoch()));
    }

    /** @return 当前不可变状态 */
    public TrustKeySettingsState state() {
        return state;
    }

    private void loadCatalog(Optional<String> selectedId, String progress, String resultMessage) {
        long epoch = nextEpoch();
        publish(copy(SettingsLoadState.LOADING, state.selected(), "", Optional.empty(), progress, false, epoch));
        gateway.trustKeys()
                .whenComplete((keys, failure) -> completeCatalog(epoch, selectedId, resultMessage, keys, failure));
    }

    private void completeCatalog(
            long epoch,
            Optional<String> selectedId,
            String resultMessage,
            List<BundleRpcContracts.TrustKey> keys,
            Throwable failure) {
        if (stale(epoch)) {
            return;
        }
        if (failure != null) {
            fail(epoch, failure);
            return;
        }
        List<BundleRpcContracts.TrustKey> sorted = keys.stream()
                .sorted(Comparator.comparing(BundleRpcContracts.TrustKey::id))
                .toList();
        Optional<BundleRpcContracts.TrustKey> selected = selectedId
                .flatMap(id ->
                        sorted.stream().filter(value -> value.id().equals(id)).findFirst())
                .or(() -> sorted.stream().findFirst());
        publish(new TrustKeySettingsState(
                SettingsLoadState.READY,
                sorted,
                selected,
                "",
                Optional.empty(),
                resultMessage.isBlank() && sorted.isEmpty() ? "暂无信任公钥" : resultMessage,
                false,
                epoch));
    }

    private void completePrepare(long epoch, TrustKeyImportDraft draft, Throwable failure) {
        if (stale(epoch)) {
            return;
        }
        if (failure != null) {
            fail(epoch, failure);
            return;
        }
        publish(copy(
                SettingsLoadState.READY,
                state.selected(),
                state.keyId(),
                Optional.of(draft),
                "请逐字核对 SHA-256 指纹后确认导入",
                false,
                epoch));
    }

    private void completeWrite(long epoch, BundleRpcContracts.TrustKey key, String success, Throwable failure) {
        if (stale(epoch)) {
            return;
        }
        if (failure != null) {
            fail(epoch, failure);
            return;
        }
        publish(copy(SettingsLoadState.READY, Optional.of(key), "", Optional.empty(), success, false, epoch));
        loadCatalog(Optional.of(key.id()), "正在刷新信任公钥…", success);
    }

    private String requireKeyId() {
        String keyId = state.keyId().strip();
        if (!keyId.matches("[A-Za-z0-9._-]{1,80}")) {
            throw new IllegalArgumentException("signingKeyId 只能包含字母、数字、点、下划线和短横线");
        }
        return keyId;
    }

    private long begin(String message) {
        long epoch = nextEpoch();
        publish(copy(SettingsLoadState.SAVING, state.selected(), state.keyId(), state.draft(), message, false, epoch));
        return epoch;
    }

    private void fail(long epoch, Throwable failure) {
        publish(copy(
                SettingsLoadState.ERROR,
                state.selected(),
                state.keyId(),
                state.draft(),
                SettingsFailures.message(failure),
                SettingsFailures.revisionConflict(failure),
                epoch));
    }

    private TrustKeySettingsState copy(
            SettingsLoadState phase,
            Optional<BundleRpcContracts.TrustKey> selected,
            String keyId,
            Optional<TrustKeyImportDraft> draft,
            String message,
            boolean conflict,
            long epoch) {
        return new TrustKeySettingsState(phase, state.keys(), selected, keyId, draft, message, conflict, epoch);
    }

    private long nextEpoch() {
        return Math.incrementExact(state.epoch());
    }

    private boolean stale(long epoch) {
        return epoch != state.epoch();
    }

    private void publish(TrustKeySettingsState value) {
        state = Objects.requireNonNull(value, "value");
        listener.accept(state);
    }
}
