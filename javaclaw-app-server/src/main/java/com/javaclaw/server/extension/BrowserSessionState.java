package com.javaclaw.server.extension;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.TurnId;
import com.javaclaw.builtin.contracts.BrowserContracts;
import com.javaclaw.builtin.contracts.SiteAccountContracts;

/** 单个 Thread 的会话状态；动作锁不用于反向网络，撤权可立即替换 volatile 租约并发布取消。 */
final class BrowserSessionState {
    final String id;
    final BrowserContracts.Owner owner;
    final Optional<SiteAccountContracts.AccountScope> account;
    final ReentrantLock actions = new ReentrantLock();
    final java.util.Map<java.net.URI, Long> pendingOrigins = new java.util.LinkedHashMap<>();
    final java.util.Map<java.net.URI, Long> promptedOrigins = new java.util.LinkedHashMap<>();
    volatile Access access;
    volatile BrowserContracts.SessionView view;
    volatile Instant touchedAt;
    volatile boolean keepLogin;
    volatile boolean closed;
    volatile boolean closing;
    volatile Instant savedIdleAt;
    volatile SiteAccountContracts.StateLease stateLease;

    BrowserSessionState(
            String id,
            BrowserContracts.Owner owner,
            Optional<SiteAccountContracts.AccountScope> account,
            Access access,
            boolean keepLogin,
            Instant now) {
        this.id = id;
        this.owner = owner;
        this.account = account;
        this.access = access;
        this.keepLogin = keepLogin;
        touchedAt = now;
    }

    record Access(
            BrowserContracts.AccessLease lease,
            PermissionProfile permission,
            Optional<TurnId> turn,
            CancellationSource cancelled) {}
}
