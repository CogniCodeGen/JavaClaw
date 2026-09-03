package com.javaclaw.desktop.settings;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.client.RemoteRpcException;
import com.javaclaw.protocol.BundleRpcContracts;
import com.javaclaw.protocol.JsonRpcError;
import com.javaclaw.protocol.ProtocolErrorCode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BundleSettingsPresentersTest {
    @Test
    void bundleStaging阻止隐式离页并在确认后安装() {
        TestBundleSettingsGateway gateway = new TestBundleSettingsGateway();
        gateway.bundles.add(TestBundleSettingsGateway.bundle("com.example.current", "1.0.0", 3, "DISABLED"));
        BundleSettingsPresenter presenter = new BundleSettingsPresenter(gateway);
        AtomicReference<BundleSettingsState> latest = new AtomicReference<>();
        presenter.subscribe(latest::set);

        presenter.reload();
        assertEquals(SettingsLoadState.READY, latest.get().phase());
        presenter.stage(Path.of("example.zip"));
        assertTrue(latest.get().dirty());
        assertEquals("com.example.new", latest.get().staging().orElseThrow().extensionId());

        presenter.select(gateway.bundles.getFirst());
        assertTrue(latest.get().message().contains("待安装文件"));
        presenter.install();

        assertFalse(latest.get().dirty());
        assertEquals("com.example.new", latest.get().selected().orElseThrow().id());
        assertEquals("扩展包已安装", latest.get().message());
    }

    @Test
    void bundle升级冲突保留Staging且健康启停使用权威结果() {
        TestBundleSettingsGateway gateway = new TestBundleSettingsGateway();
        BundleRpcContracts.Bundle current =
                TestBundleSettingsGateway.bundle("com.example.current", "1.0.0", 3, "DISABLED");
        gateway.bundles.add(current);
        BundleSettingsPresenter presenter = new BundleSettingsPresenter(gateway);
        AtomicReference<BundleSettingsState> latest = new AtomicReference<>();
        presenter.subscribe(latest::set);
        presenter.reload();

        presenter.probe();
        assertEquals(
                BundleRpcContracts.HealthState.HEALTHY,
                latest.get().selected().orElseThrow().health().state());
        presenter.toggleEnabled();
        assertEquals("ENABLED", latest.get().selected().orElseThrow().state());
        presenter.toggleEnabled();
        assertEquals("DISABLED", latest.get().selected().orElseThrow().state());

        gateway.staging = TestBundleSettingsGateway.stage("com.example.current", "Current 2", "2.0.0");
        presenter.stage(Path.of("upgrade.zip"));
        gateway.nextFailure = revisionConflict();
        presenter.upgrade();
        assertTrue(latest.get().revisionConflict());
        assertTrue(latest.get().dirty());
        assertEquals("2.0.0", latest.get().staging().orElseThrow().version());
    }

    @Test
    void trustKey要求合法标识与完整指纹确认并实时撤销() {
        TestBundleSettingsGateway gateway = new TestBundleSettingsGateway();
        TrustKeySettingsPresenter presenter = new TrustKeySettingsPresenter(gateway);
        AtomicReference<TrustKeySettingsState> latest = new AtomicReference<>();
        presenter.subscribe(latest::set);
        presenter.reload();
        assertEquals("暂无信任公钥", latest.get().message());

        presenter.updateKeyId("bad key id");
        presenter.prepare(Path.of("release.pub"));
        assertEquals(
                TestBundleSettingsGateway.FINGERPRINT,
                latest.get().draft().orElseThrow().fingerprint());
        presenter.importPrepared();
        assertEquals(SettingsLoadState.ERROR, latest.get().phase());
        assertTrue(latest.get().dirty());

        presenter.updateKeyId("release-key");
        presenter.importPrepared();
        assertFalse(latest.get().dirty());
        assertEquals("信任公钥已导入", latest.get().message());
        presenter.revoke();
        assertEquals(
                BundleRpcContracts.TrustState.REVOKED,
                latest.get().selected().orElseThrow().state());
        assertEquals("信任公钥已撤销", latest.get().message());
    }

    @Test
    void trustKey撤销冲突与Bundle读取失败不伪造成功() {
        TestBundleSettingsGateway gateway = new TestBundleSettingsGateway();
        gateway.trustKeys.add(TestBundleSettingsGateway.activeKey());
        TrustKeySettingsPresenter trust = new TrustKeySettingsPresenter(gateway);
        AtomicReference<TrustKeySettingsState> trustState = new AtomicReference<>();
        trust.subscribe(trustState::set);
        trust.reload();
        gateway.nextFailure = revisionConflict();
        trust.revoke();
        assertTrue(trustState.get().revisionConflict());
        assertEquals(SettingsLoadState.ERROR, trustState.get().phase());

        BundleSettingsPresenter bundles = new BundleSettingsPresenter(gateway);
        AtomicReference<BundleSettingsState> bundleState = new AtomicReference<>();
        bundles.subscribe(bundleState::set);
        gateway.nextFailure = new IllegalStateException("服务端不可用");
        gateway.staging = TestBundleSettingsGateway.stage("com.example.new", "New", "1.0.0");
        bundles.stage(Path.of("bundle.zip"));
        assertEquals(SettingsLoadState.ERROR, bundleState.get().phase());
        assertTrue(bundleState.get().message().contains("服务端不可用"));
        assertFalse(bundleState.get().dirty());
    }

    @Test
    void trash只允许精确危险确认并保留恢复和清除历史() {
        TestBundleSettingsGateway gateway = new TestBundleSettingsGateway();
        BundleRpcContracts.Bundle removed =
                TestBundleSettingsGateway.bundle("com.example.removed", "1.0.0", 1, "DISABLED");
        gateway.trash.add(TestBundleSettingsGateway.trash(removed, "trash-1"));
        BundleTrashSettingsPresenter presenter = new BundleTrashSettingsPresenter(gateway);
        AtomicReference<BundleTrashSettingsState> latest = new AtomicReference<>();
        presenter.subscribe(latest::set);
        presenter.reload();

        presenter.purge("PURGE wrong");
        assertEquals(SettingsLoadState.ERROR, latest.get().phase());
        assertEquals(
                BundleRpcContracts.TrashState.TRASHED,
                latest.get().selected().orElseThrow().state());
        presenter.restore();
        assertEquals(
                BundleRpcContracts.TrashState.RESTORED,
                latest.get().selected().orElseThrow().state());
        assertEquals("扩展包已恢复为版本 2", latest.get().message());

        BundleRpcContracts.Bundle second =
                TestBundleSettingsGateway.bundle("com.example.purge", "1.0.0", 1, "DISABLED");
        gateway.trash.add(TestBundleSettingsGateway.trash(second, "trash-2"));
        presenter.reload();
        presenter.select(gateway.trash.stream()
                .filter(value -> value.trashId().equals("trash-2"))
                .findFirst()
                .orElseThrow());
        presenter.purge("PURGE trash-2");
        assertEquals(
                BundleRpcContracts.TrashState.PURGED,
                latest.get().selected().orElseThrow().state());
        assertEquals("回收站文件已永久清除", latest.get().message());
    }

    private static RemoteRpcException revisionConflict() {
        return new RemoteRpcException(new JsonRpcError(
                ProtocolErrorCode.REVISION_CONFLICT, "revision 已改变", Optional.of(new CanonicalPayload("{}"))));
    }
}
