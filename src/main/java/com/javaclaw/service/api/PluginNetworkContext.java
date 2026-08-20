package com.javaclaw.service.api;

import java.net.InetAddress;
import java.util.List;

public record PluginNetworkContext(boolean lanListeningAllowed, List<InetAddress> allowedAddresses) {
    public PluginNetworkContext {
        allowedAddresses = allowedAddresses == null ? List.of() : List.copyOf(allowedAddresses);
    }
}
