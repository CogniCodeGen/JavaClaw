package com.javaclaw.server.extension;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.javaclaw.api.BrokerRequest;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.server.security.BrowserBrokerResponse;

final class SiteBrowserTestBroker implements SiteNetworkBroker {
    final java.util.ArrayList<BrokerRequest> requests = new java.util.ArrayList<>();
    Runnable beforeRealtime = () -> {};
    boolean socketOpened;

    @Override
    public BrowserBrokerResponse exchange(
            BrokerRequest request,
            PermissionProfile permission,
            com.javaclaw.api.CancellationToken cancellation,
            com.javaclaw.server.security.PinnedHttpNetworkBroker.AddressAuthorization privateAuthorization,
            com.javaclaw.server.security.PinnedHttpNetworkBroker.RealtimeAuthorization realtimeAuthorization)
            throws Exception {
        requests.add(request);
        beforeRealtime.run();
        realtimeAuthorization.authorize();
        socketOpened = true;
        return new BrowserBrokerResponse(200, Map.of(), "ok".getBytes(StandardCharsets.UTF_8), false);
    }
}
