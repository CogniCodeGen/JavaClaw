package com.javaclaw.site;

import com.javaclaw.util.SensitiveDataRedactor;

/** Value-level validation and defensive copying for site credentials. */
final class SiteCredentialValues {

    private SiteCredentialValues() { }

    static void validateMetadata(SiteCredential credential) {
        String[] fields = {
                credential.getName(), credential.getHostPattern(), credential.getLoginUrl(),
                credential.getUsername(), credential.getNotes()
        };
        for (String field : fields) {
            if (SensitiveDataRedactor.containsLikelyCredential(field)) {
                throw new IllegalArgumentException("站点非密码字段疑似包含密钥或密码，已拒绝保存");
            }
        }
    }

    static SiteCredential copyOf(SiteCredential source) {
        SiteCredential copy = new SiteCredential();
        copy.setId(source.getId());
        copy.setName(source.getName());
        copy.setHostPattern(source.getHostPattern());
        copy.setLoginUrl(source.getLoginUrl());
        copy.setUsername(source.getUsername());
        copy.setPassword(source.getPassword());
        copy.setNotes(source.getNotes());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setLastUsedAt(source.getLastUsedAt());
        copy.setHasSession(source.isHasSession());
        return copy;
    }
}
