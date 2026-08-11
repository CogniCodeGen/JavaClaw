package com.javaclaw.application.site;

import java.util.List;

/** 站点凭据持久化端口；实现负责事务、加密和工作区隔离。 */
public interface SiteCredentialPort {

    List<Entry> list();

    Entry save(Entry entry);

    boolean delete(String id);

    boolean clearSession(String id);

    String storageDescription();

    record Entry(
            String id,
            String name,
            String hostPattern,
            String loginUrl,
            String username,
            String password,
            String notes,
            long createdAt,
            long lastUsedAt,
            boolean hasSession) {}
}
