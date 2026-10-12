package com.javaclaw.infrastructure.site;

import com.javaclaw.application.site.SiteCredentialPort;
import com.javaclaw.site.SiteCredential;
import com.javaclaw.site.SiteCredentialManager;

import java.util.Objects;

/** 用现有加密 H2 管理器实现站点凭据持久化端口。 */
public final class SiteCredentialManagerAdapter implements SiteCredentialPort {

    private final SiteCredentialManager manager;

    public SiteCredentialManagerAdapter(SiteCredentialManager manager) {
        this.manager = Objects.requireNonNull(manager, "manager");
    }

    @Override
    public java.util.List<Entry> list() {
        return manager.all().stream().map(SiteCredentialManagerAdapter::toEntry).toList();
    }

    @Override
    public Entry save(Entry entry) {
        return toEntry(manager.putChecked(toEntity(entry)));
    }

    @Override
    public boolean delete(String id) {
        return manager.removeChecked(id);
    }

    @Override
    public boolean clearSession(String id) {
        if (manager.get(id) == null) return false;
        manager.clearSession(id);
        return true;
    }

    @Override
    public String storageDescription() {
        return manager.getConfigFilePath();
    }

    private static Entry toEntry(SiteCredential credential) {
        return new Entry(credential.getId(), credential.getName(), credential.getHostPattern(),
                credential.getLoginUrl(), credential.getUsername(), credential.getPassword(),
                credential.getNotes(), credential.getCreatedAt(), credential.getLastUsedAt(),
                credential.isHasSession());
    }

    private static SiteCredential toEntity(Entry entry) {
        SiteCredential credential = new SiteCredential();
        credential.setId(entry.id());
        credential.setName(entry.name());
        credential.setHostPattern(entry.hostPattern());
        credential.setLoginUrl(entry.loginUrl());
        credential.setUsername(entry.username());
        credential.setPassword(entry.password());
        credential.setNotes(entry.notes());
        credential.setCreatedAt(entry.createdAt());
        credential.setLastUsedAt(entry.lastUsedAt());
        credential.setHasSession(entry.hasSession());
        return credential;
    }
}
