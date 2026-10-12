package com.javaclaw.desktop.api;

import java.util.List;

/** A bounded installed application directory; truncation never asserts an app is absent. */
public record DesktopApplicationCatalog(List<DesktopApplicationInfo> applications, boolean truncated) {
    public DesktopApplicationCatalog {
        applications = List.copyOf(applications);
        if (applications.size() > 256) throw new IllegalArgumentException("application catalog exceeds its bound");
    }
}
