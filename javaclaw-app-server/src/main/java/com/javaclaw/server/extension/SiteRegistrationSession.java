package com.javaclaw.server.extension;

import java.net.URI;
import java.time.Clock;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.NetworkPermission;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.BrowserContracts;
import com.javaclaw.builtin.contracts.SiteContracts;
import com.javaclaw.builtin.contracts.SiteRegistrationContracts;

/** Workspace 临时登记状态；租约替换立即使在途 Broker 请求失效，不授予 Thread 或普通工具网络权限。 */
final class SiteRegistrationSession {
    final WorkspaceId workspace;
    final String id;
    final PermissionProfile permission;
    final CancellationSource cancelled = new CancellationSource();
    final Set<URI> pending = ConcurrentHashMap.newKeySet();
    volatile BrowserContracts.AccessLease lease;
    volatile SiteRegistrationContracts.Page page =
            new SiteRegistrationContracts.Page(0, Optional.empty(), "", java.util.List.of());

    SiteRegistrationSession(
            WorkspaceId workspace, String id, PermissionProfile permission, BrowserContracts.AccessLease lease) {
        this.workspace = workspace;
        this.id = id;
        this.permission = permission;
        this.lease = lease;
    }

    void requireCurrent(BrowserContracts.AccessLease expected, Clock clock) {
        cancelled.throwIfCancelled();
        if (lease != expected || !expected.active(clock.instant())) {
            throw new SecurityException("网站登记授权已改变或到期");
        }
    }

    PermissionProfile authorize(BrowserContracts.AccessLease expected, URI uri, Clock clock) {
        requireCurrent(expected, clock);
        URI origin = SiteContracts.originOf(uri);
        if (!expected.allowedOrigins().contains(origin)) {
            throw new SecurityException("网站登记来源尚未得到用户授权");
        }
        // 仅为本登记 Broker 构造精确来源投影；私网地址仍由独立授权回调检查。
        return new PermissionProfile(
                "site-registration",
                permission.version(),
                permission.files(),
                new NetworkPermission(
                        Set.of(origin.getHost()), Set.of(origin.getPort() < 0 ? 443 : origin.getPort()), true),
                permission.processes(),
                permission.tools(),
                permission.resources());
    }

    CancellationToken cancellation(BrowserContracts.AccessLease expected, Clock clock, CancellationToken caller) {
        return new CancellationToken() {
            @Override
            public boolean isCancelled() {
                return caller.isCancelled()
                        || cancelled.isCancelled()
                        || lease != expected
                        || !expected.active(clock.instant());
            }

            @Override
            public Optional<String> reason() {
                return isCancelled() ? Optional.of("网站登记已取消或授权代次失效") : Optional.empty();
            }
        };
    }

    SiteRegistrationContracts.Session snapshot(SiteRegistrationContracts.State state) {
        BrowserContracts.AccessLease current = lease;
        Set<URI> denied = Set.copyOf(pending);
        return new SiteRegistrationContracts.Session(
                id,
                state,
                new SiteRegistrationContracts.Access(
                        current.generation(), current.allowedOrigins(), denied, current.expiresAt()),
                page,
                Optional.empty());
    }
}
