package com.javaclaw.server.security;

import java.io.IOException;
import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.NetworkPermission;

/** 经过权限、DNS 与公网地址校验后固定地址集合的单次 HTTP 目标。 */
record BrokerTarget(URI uri, String host, int port, boolean tls, List<InetAddress> addresses) {
    private static final int MAXIMUM_DNS_ADDRESSES = 32;

    BrokerTarget {
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(host, "host");
        addresses = List.copyOf(addresses);
        if (port < 1 || port > 65_535 || addresses.isEmpty()) {
            throw new IllegalArgumentException("Broker target port or addresses are invalid");
        }
    }

    static BrokerTarget resolve(
            URI uri,
            NetworkPermission permission,
            HostResolver resolver,
            BrokerDeadline deadline,
            CancellationToken cancellation)
            throws IOException {
        return resolve(uri, permission, resolver, deadline, cancellation, (origin, addresses) -> {
            throw new SecurityException("DNS returned a non-public address");
        });
    }

    static BrokerTarget resolve(
            URI uri,
            NetworkPermission permission,
            HostResolver resolver,
            BrokerDeadline deadline,
            CancellationToken cancellation,
            PinnedHttpNetworkBroker.AddressAuthorization privateAuthorization)
            throws IOException {
        URI checked = Objects.requireNonNull(uri, "uri").normalize();
        String scheme =
                Objects.requireNonNull(checked.getScheme(), "uri scheme").toLowerCase(Locale.ROOT);
        boolean tls =
                switch (scheme) {
                    case "https" -> true;
                    case "http" -> false;
                    default -> throw new SecurityException("Network Broker supports only HTTP and HTTPS");
                };
        String host = asciiHost(checked);
        int port = checked.getPort() < 0 ? (tls ? 443 : 80) : checked.getPort();
        requirePermission(permission, host, port, tls);
        List<InetAddress> resolved = resolver.resolve(host, deadline.remaining(cancellation), cancellation);
        LinkedHashSet<InetAddress> unique = new LinkedHashSet<>(resolved);
        if (unique.isEmpty() || unique.size() > MAXIMUM_DNS_ADDRESSES) {
            throw new SecurityException("DNS result count is outside the Broker limit");
        }
        List<InetAddress> addresses = List.copyOf(unique);
        if (addresses.stream().anyMatch(address -> !PublicNetworkAddress.isAllowed(address))) {
            if (!tls) {
                throw new SecurityException("Private network grants require HTTPS");
            }
            requireGrantablePrivate(addresses);
            Set<String> numericAddresses = addresses.stream()
                    .map(InetAddress::getHostAddress)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            privateAuthorization.authorize(origin(scheme, host, port), numericAddresses);
        }
        return new BrokerTarget(checked, host, port, tls, addresses);
    }

    String requestTarget() {
        String path = uri.getRawPath();
        String target = path == null || path.isEmpty() ? "/" : path;
        return uri.getRawQuery() == null ? target : target + "?" + uri.getRawQuery();
    }

    String hostHeader() {
        String formatted = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
        boolean defaultPort = tls && port == 443 || !tls && port == 80;
        return defaultPort ? formatted : formatted + ":" + port;
    }

    private static String asciiHost(URI uri) {
        String host = uri.getHost();
        if (host == null || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new SecurityException("Broker URI must contain an unambiguous host");
        }
        String unwrapped = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        if (unwrapped.indexOf(':') >= 0) {
            return unwrapped.toLowerCase(Locale.ROOT);
        }
        return IDN.toASCII(unwrapped, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
    }

    private static void requirePermission(NetworkPermission permission, String host, int port, boolean tls) {
        NetworkPermission checked = Objects.requireNonNull(permission, "permission");
        if (checked.hosts().contains(NetworkPermission.ANY_HOST)
                || checked.ports().contains(NetworkPermission.ANY_PORT)) {
            throw new SecurityException("wildcard network permission must be narrowed before execution");
        }
        if (!checked.allowsHost(host) || !checked.allowsPort(port) || checked.tlsOnly() && !tls) {
            throw new SecurityException("Broker target is outside the effective network permission");
        }
    }

    private static void requireGrantablePrivate(List<InetAddress> addresses) {
        boolean safe = addresses.stream()
                .filter(address -> !PublicNetworkAddress.isAllowed(address))
                .allMatch(PublicNetworkAddress::isGrantablePrivate);
        if (!safe) {
            throw new SecurityException("DNS returned a permanently forbidden address");
        }
    }

    private static URI origin(String scheme, String host, int port) {
        if (!"https".equals(scheme)) {
            throw new SecurityException("Private network grant origin must use HTTPS");
        }
        String portPart = port == 443 ? "" : ":" + port;
        return URI.create(scheme + "://" + (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + portPart);
    }

    @FunctionalInterface
    interface HostResolver {
        List<InetAddress> resolve(String host, Duration timeout, CancellationToken cancellation) throws IOException;
    }

    /** 使用虚拟线程为可能阻塞的系统 DNS 查询施加调用级时限。 */
    static final class SystemHostResolver implements HostResolver {
        private final DnsLookup lookup;

        SystemHostResolver() {
            this(InetAddress::getAllByName);
        }

        SystemHostResolver(DnsLookup lookup) {
            this.lookup = Objects.requireNonNull(lookup, "lookup");
        }

        @Override
        public List<InetAddress> resolve(String host, Duration timeout, CancellationToken cancellation)
                throws IOException {
            FutureTask<List<InetAddress>> task = new FutureTask<>(() -> List.of(lookup.resolve(host)));
            Thread thread = Thread.ofVirtual().name("javaclaw-broker-dns").start(task);
            try {
                while (true) {
                    cancellation.throwIfCancelled();
                    long wait = Math.max(1, Math.min(timeout.toMillis(), 100));
                    try {
                        return task.get(wait, TimeUnit.MILLISECONDS);
                    } catch (TimeoutException pending) {
                        timeout = timeout.minusMillis(wait);
                        if (timeout.isZero() || timeout.isNegative()) {
                            throw new java.net.SocketTimeoutException("Network Broker DNS lookup timed out");
                        }
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new java.io.InterruptedIOException("Network Broker DNS lookup was interrupted");
            } catch (ExecutionException failure) {
                Throwable cause = failure.getCause();
                if (cause instanceof IOException io) {
                    throw io;
                }
                throw new IOException("Network Broker DNS lookup failed", cause);
            } finally {
                task.cancel(true);
                thread.interrupt();
            }
        }

        @FunctionalInterface
        interface DnsLookup {
            InetAddress[] resolve(String host) throws IOException;
        }
    }
}
