package com.javaclaw.server.extension;

import java.util.Optional;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ThreadId;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.ManagedExtensionStore;
import com.javaclaw.protocol.CanonicalJson;

/** 浏览器动作在执行前记录身份；失去回执的点击、提交和上传只允许人工核验，不自动重放。 */
final class BrowserOperationLedger {
    private static final ExtensionId SITE = new ExtensionId(BuiltinExtensionIds.SITE);
    private final ManagedExtensionStore store;
    private final CanonicalJson json;

    BrowserOperationLedger(ManagedExtensionStore store, CanonicalJson json) {
        this.store = store;
        this.json = json;
    }

    Optional<CanonicalPayload> begin(ThreadId thread, String identity, CanonicalPayload request) throws Exception {
        return store.inTransaction(SITE, tx -> {
            String key = key(identity);
            var current = tx.get(collection(thread), key);
            if (current.isPresent()) {
                Receipt receipt = json.decode(current.orElseThrow().payload(), Receipt.class);
                if (!receipt.requestDigest().equals(request.sha256())) {
                    throw new IllegalArgumentException("浏览器幂等身份已绑定不同动作");
                }
                if (receipt.result().isEmpty()) {
                    throw new IllegalStateException("浏览器操作结果未知，请先观察页面或人工核验，不能自动重试");
                }
                return receipt.result();
            }
            tx.put(collection(thread), key, 0, json.encode(new Receipt(request.sha256(), Optional.empty())));
            return Optional.empty();
        });
    }

    Optional<CanonicalPayload> recover(ThreadId thread, String identity, CanonicalPayload request) throws Exception {
        return store.inTransaction(SITE, tx -> {
            var current = tx.get(collection(thread), key(identity));
            if (current.isEmpty()) {
                return Optional.empty();
            }
            Receipt receipt = json.decode(current.orElseThrow().payload(), Receipt.class);
            if (!receipt.requestDigest().equals(request.sha256())) {
                throw new IllegalArgumentException("浏览器幂等身份已绑定不同动作");
            }
            if (receipt.result().isEmpty()) {
                throw new IllegalStateException("浏览器操作结果未知，请先观察页面或人工核验，不能自动重试");
            }
            return receipt.result();
        });
    }

    void complete(ThreadId thread, String identity, CanonicalPayload request, CanonicalPayload result)
            throws Exception {
        store.inTransaction(SITE, tx -> {
            String key = key(identity);
            var current = tx.get(collection(thread), key).orElseThrow();
            Receipt receipt = json.decode(current.payload(), Receipt.class);
            if (!receipt.requestDigest().equals(request.sha256())
                    || receipt.result().isPresent()) {
                throw new IllegalStateException("浏览器动作账本已改变");
            }
            tx.put(
                    collection(thread),
                    key,
                    current.revision(),
                    json.encode(new Receipt(request.sha256(), Optional.of(result))));
            return null;
        });
    }

    private String key(String identity) {
        return json.encode(java.util.Map.of("idempotencyKey", identity)).sha256();
    }

    private static String collection(ThreadId thread) {
        return "browser.operations." + thread;
    }

    private record Receipt(String requestDigest, Optional<CanonicalPayload> result) {}
}
