package com.javaclaw.server.rpc;

import java.util.Objects;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.protocol.BundleRpcContracts;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.extension.thirdparty.ThirdPartyExtensionHost;

/** Protocol v2 Bundle、Trust Key 与 Trash 管理方法的薄映射。 */
public final class ExtensionBundleRpcHandlers {
    private final ThirdPartyExtensionHost extensions;
    private final CanonicalJson json;

    /**
     * 创建管理 handlers。
     *
     * @param extensions 第三方 Host
     * @param json 共享 JSON codec
     */
    public ExtensionBundleRpcHandlers(ThirdPartyExtensionHost extensions, CanonicalJson json) {
        this.extensions = Objects.requireNonNull(extensions, "extensions");
        this.json = Objects.requireNonNull(json, "json");
    }

    /**
     * 注册 Bundle、Trust Key 与 Trash 的完整管理面。
     *
     * @param builder Router Builder
     */
    public void register(RpcRouter.Builder builder) {
        registerBundle(builder);
        registerTrash(builder);
        registerTrust(builder);
    }

    private void registerBundle(RpcRouter.Builder builder) {
        builder.register("extension/bundle/list", this::listBundles)
                .register("extension/bundle/read", this::readBundle)
                .register("extension/bundle/stage", this::stage)
                .register("extension/bundle/install", this::install)
                .register("extension/bundle/upgrade", this::upgrade)
                .register("extension/bundle/health/probe", this::probe)
                .register("extension/bundle/enable", this::enable)
                .register("extension/bundle/disable", this::disable)
                .register("extension/bundle/uninstall", this::uninstall);
    }

    private void registerTrash(RpcRouter.Builder builder) {
        builder.register("extension/bundle/trash/list", this::listTrash)
                .register("extension/bundle/trash/read", this::readTrash)
                .register("extension/bundle/trash/restore", this::restoreTrash)
                .register("extension/bundle/trash/purge", this::purgeTrash);
    }

    private void registerTrust(RpcRouter.Builder builder) {
        builder.register("extension/trustKey/list", this::listTrustKeys)
                .register("extension/trustKey/read", this::readTrustKey)
                .register("extension/trustKey/import", this::importTrustKey)
                .register("extension/trustKey/revoke", this::revokeTrustKey);
    }

    private CanonicalPayload listBundles(CanonicalPayload params) {
        requireEmpty(params);
        return json.encode(new BundleRpcContracts.BundleListResult(extensions.listBundles()));
    }

    private CanonicalPayload readBundle(CanonicalPayload params) {
        var payload = json.decode(params, BundleRpcContracts.BundlePayload.class);
        return json.encode(new BundleRpcContracts.BundleResult(extensions.readBundle(payload.extensionId())));
    }

    private CanonicalPayload stage(CanonicalPayload params) {
        WriteCommand command = command(params);
        requireCreateRevision(command, "Bundle staging");
        var payload = json.decode(command.payload(), BundleRpcContracts.StagePayload.class);
        return json.encode(extensions.stage(payload.attachment()));
    }

    private CanonicalPayload install(CanonicalPayload params) {
        WriteCommand command = command(params);
        var payload = json.decode(command.payload(), BundleRpcContracts.CommitPayload.class);
        return json.encode(
                new BundleRpcContracts.BundleResult(extensions.install(payload, command.expectedRevision())));
    }

    private CanonicalPayload upgrade(CanonicalPayload params) {
        WriteCommand command = command(params);
        var payload = json.decode(command.payload(), BundleRpcContracts.CommitPayload.class);
        return json.encode(
                new BundleRpcContracts.BundleResult(extensions.upgrade(payload, command.expectedRevision())));
    }

    private CanonicalPayload probe(CanonicalPayload params) {
        return lifecycle(params, Lifecycle.PROBE);
    }

    private CanonicalPayload enable(CanonicalPayload params) {
        return lifecycle(params, Lifecycle.ENABLE);
    }

    private CanonicalPayload disable(CanonicalPayload params) {
        return lifecycle(params, Lifecycle.DISABLE);
    }

    private CanonicalPayload uninstall(CanonicalPayload params) {
        WriteCommand command = command(params);
        var payload = json.decode(command.payload(), BundleRpcContracts.BundlePayload.class);
        return json.encode(new BundleRpcContracts.TrashResult(
                extensions.uninstall(payload.extensionId(), command.expectedRevision())));
    }

    private CanonicalPayload listTrash(CanonicalPayload params) {
        requireEmpty(params);
        return json.encode(new BundleRpcContracts.TrashListResult(extensions.listTrash()));
    }

    private CanonicalPayload readTrash(CanonicalPayload params) {
        var payload = json.decode(params, BundleRpcContracts.TrashPayload.class);
        return json.encode(new BundleRpcContracts.TrashResult(extensions.readTrash(payload.trashId())));
    }

    private CanonicalPayload restoreTrash(CanonicalPayload params) {
        WriteCommand command = command(params);
        var payload = json.decode(command.payload(), BundleRpcContracts.TrashPayload.class);
        return json.encode(new BundleRpcContracts.BundleResult(
                extensions.restoreTrash(payload.trashId(), command.expectedRevision())));
    }

    private CanonicalPayload purgeTrash(CanonicalPayload params) {
        WriteCommand command = command(params);
        var payload = json.decode(command.payload(), BundleRpcContracts.TrashPurgePayload.class);
        return json.encode(new BundleRpcContracts.TrashResult(
                extensions.purgeTrash(payload.trashId(), command.expectedRevision())));
    }

    private CanonicalPayload listTrustKeys(CanonicalPayload params) {
        requireEmpty(params);
        return json.encode(new BundleRpcContracts.TrustKeyListResult(extensions.listTrustKeys()));
    }

    private CanonicalPayload readTrustKey(CanonicalPayload params) {
        var payload = json.decode(params, BundleRpcContracts.TrustKeyPayload.class);
        return json.encode(new BundleRpcContracts.TrustKeyResult(extensions.readTrustKey(payload.keyId())));
    }

    private CanonicalPayload importTrustKey(CanonicalPayload params) {
        WriteCommand command = command(params);
        var payload = json.decode(command.payload(), BundleRpcContracts.TrustKeyImportPayload.class);
        return json.encode(
                new BundleRpcContracts.TrustKeyResult(extensions.importTrustKey(payload, command.expectedRevision())));
    }

    private CanonicalPayload revokeTrustKey(CanonicalPayload params) {
        WriteCommand command = command(params);
        var payload = json.decode(command.payload(), BundleRpcContracts.TrustKeyPayload.class);
        return json.encode(new BundleRpcContracts.TrustKeyResult(
                extensions.revokeTrustKey(payload.keyId(), command.expectedRevision())));
    }

    private CanonicalPayload lifecycle(CanonicalPayload params, Lifecycle lifecycle) {
        WriteCommand command = command(params);
        var payload = json.decode(command.payload(), BundleRpcContracts.BundlePayload.class);
        BundleRpcContracts.Bundle bundle =
                switch (lifecycle) {
                    case PROBE -> extensions.probe(payload.extensionId(), command.expectedRevision());
                    case ENABLE -> extensions.enable(payload.extensionId(), command.expectedRevision());
                    case DISABLE -> extensions.disable(payload.extensionId(), command.expectedRevision());
                };
        return json.encode(new BundleRpcContracts.BundleResult(bundle));
    }

    private WriteCommand command(CanonicalPayload params) {
        return json.decode(params, WriteCommand.class);
    }

    private static void requireCreateRevision(WriteCommand command, String action) {
        if (command.expectedRevision() != 0) {
            throw new IllegalArgumentException(action + " expected revision must be 0");
        }
    }

    private static void requireEmpty(CanonicalPayload params) {
        if (!"{}".equals(params.json())) {
            throw new IllegalArgumentException("query params must be empty");
        }
    }

    private enum Lifecycle {
        PROBE,
        ENABLE,
        DISABLE
    }
}
