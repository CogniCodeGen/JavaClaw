package com.javaclaw.ui.javafx.settings;

import com.javaclaw.application.settings.ModelDiscoveryApplicationService.DiscoveryRequest;
import com.javaclaw.application.settings.ModelDiscoveryApplicationService.Usage;
import com.javaclaw.application.settings.ModelSettingsApplicationService.ModelSettings;

/** 分级模型发现时的高性能模型地址和凭据继承规则。 */
final class TieredModelDiscovery {
    private final ModelDiscoveryCredentialScope normalCredential =
            new ModelDiscoveryCredentialScope();
    private final ModelDiscoveryCredentialScope lightCredential =
            new ModelDiscoveryCredentialScope();
    private ModelSettings highModel;

    void setHighModel(ModelSettings value) {
        highModel = value;
    }

    void capture(boolean light, String provider, String baseUrl) {
        credential(light).capture(provider, effectiveUrl(provider, baseUrl));
    }

    String ownKeyFor(boolean light, String provider, String baseUrl, String key) {
        return credential(light).keyFor(provider, effectiveUrl(provider, baseUrl), key);
    }

    boolean requiresNewKey(boolean light, String provider, String baseUrl) {
        String url = effectiveUrl(provider, baseUrl);
        return url.isBlank() && (baseUrl == null || baseUrl.isBlank())
                || credential(light).requiresNewKey(provider, url);
    }

    DiscoveryRequest request(boolean light, String provider, String baseUrl, String key) {
        if (provider == null || provider.isBlank()) return null;
        String url = effectiveUrl(provider, baseUrl);
        String allowedKey = key == null ? "" : key;
        if (allowedKey.isBlank() && highModel != null
                && ModelDiscoveryCredentialScope.sameEndpoint(
                        provider, url, highModel.provider(), highModel.baseUrl())) {
            allowedKey = highModel.apiKey();
        } else {
            allowedKey = credential(light).keyFor(provider, url, allowedKey);
        }
        return new DiscoveryRequest(provider, url, allowedKey, Usage.CHAT);
    }

    private String effectiveUrl(String provider, String baseUrl) {
        String url = baseUrl == null ? "" : baseUrl.strip();
        if (url.isBlank() && highModel != null
                && provider.equalsIgnoreCase(highModel.provider())) {
            return highModel.baseUrl();
        }
        return url;
    }

    private ModelDiscoveryCredentialScope credential(boolean light) {
        return light ? lightCredential : normalCredential;
    }
}
