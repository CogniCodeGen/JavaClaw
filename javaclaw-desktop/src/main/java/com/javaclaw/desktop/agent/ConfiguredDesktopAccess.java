package com.javaclaw.desktop.agent;

import com.javaclaw.config.AgentConfig;
import com.javaclaw.desktop.api.DesktopAvailability;
import com.javaclaw.desktop.api.DesktopInputPolicy;
import com.javaclaw.desktop.api.DesktopConsentPort;
import com.javaclaw.desktop.api.DesktopSessionOwner;
import com.javaclaw.desktop.api.DesktopSystemPermissionService;
import com.javaclaw.desktop.api.DesktopTarget;
import java.util.Objects;

/** The saved application switch grants desktop sessions while OS permissions remain available. */
public final class ConfiguredDesktopAccess implements DesktopConsentPort {
    private final AgentConfig settings;
    private final DesktopSystemPermissionService permissions;

    public ConfiguredDesktopAccess(AgentConfig settings, DesktopSystemPermissionService permissions) {
        this.settings = Objects.requireNonNull(settings);
        this.permissions = Objects.requireNonNull(permissions);
    }

    @Override public boolean enabled() { return settings.isComputerAppAccessEnabled(); }

    @Override public DesktopAvailability accessStatus() {
        return accessStatus(Purpose.OBSERVE);
    }

    @Override public DesktopAvailability accessStatus(Purpose purpose) {
        if (!enabled()) {
            return new DesktopAvailability(false, "", 0, "请先在设置中开启电脑应用访问");
        }
        DesktopAvailability status = permissions.status(purpose == Purpose.FOREGROUND_TAKEOVER
                ? DesktopInputPolicy.SYSTEM_EXPLICIT : DesktopInputPolicy.BACKGROUND_STRICT);
        boolean ready = purpose == Purpose.OBSERVE
                ? status.available() || (status.capabilities() & DesktopAvailability.CAPTURE) != 0
                : status.available();
        return new DesktopAvailability(ready, status.providerId(), status.capabilities(),
                status.detail());
    }

    @Override public boolean request(DesktopSessionOwner owner, DesktopTarget target, Purpose purpose) {
        if (!enabled()) throw new SecurityException("请先在设置中开启电脑应用访问");
        var status = accessStatus(purpose);
        if (!status.available()) {
            throw new IllegalStateException("电脑应用访问缺少系统权限：" + status.detail());
        }
        return true;
    }
}
