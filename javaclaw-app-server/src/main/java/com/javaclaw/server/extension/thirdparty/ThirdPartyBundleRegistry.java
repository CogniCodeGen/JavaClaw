package com.javaclaw.server.extension.thirdparty;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import com.javaclaw.extension.spi.ExtensionId;

/** 只缓存已经重新验签并编译成功的第三方运行索引。 */
final class ThirdPartyBundleRegistry {
    private final ConcurrentHashMap<ExtensionId, InstalledThirdPartyBundle> bundles = new ConcurrentHashMap<>();

    InstalledThirdPartyBundle require(ExtensionId id) {
        InstalledThirdPartyBundle bundle = bundles.get(id);
        if (bundle == null) {
            throw new IllegalArgumentException("third-party extension is unavailable: " + id.value());
        }
        return bundle;
    }

    synchronized void put(InstalledThirdPartyBundle bundle) {
        java.util.Set<String> incoming = bundle.tools().keySet();
        bundles.values().stream()
                .filter(existing ->
                        !existing.descriptor().id().equals(bundle.descriptor().id()))
                .flatMap(existing -> existing.tools().keySet().stream())
                .filter(incoming::contains)
                .findFirst()
                .ifPresent(name -> {
                    throw new IllegalArgumentException("duplicate third-party tool name: " + name);
                });
        bundles.put(bundle.descriptor().id(), bundle);
    }

    void remove(ExtensionId id) {
        bundles.remove(id);
    }

    List<InstalledThirdPartyBundle> sorted() {
        return bundles.values().stream()
                .sorted(Comparator.comparing(bundle -> bundle.descriptor().id().value()))
                .toList();
    }

    Collection<InstalledThirdPartyBundle> values() {
        return List.copyOf(bundles.values());
    }

    void clear() {
        bundles.clear();
    }
}
