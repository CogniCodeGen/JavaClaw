package com.javaclaw.server.extension;

import com.javaclaw.api.BrokerRequest;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.server.security.BrowserBrokerResponse;
import com.javaclaw.server.security.PinnedHttpNetworkBroker;

/** Site Browser 仅允许的一跳 HTTPS Broker 边界。 */
@FunctionalInterface
interface SiteNetworkBroker {
    BrowserBrokerResponse exchange(
            BrokerRequest request,
            PermissionProfile permission,
            CancellationToken cancellation,
            PinnedHttpNetworkBroker.AddressAuthorization privateAuthorization,
            PinnedHttpNetworkBroker.RealtimeAuthorization realtimeAuthorization)
            throws Exception;
}
