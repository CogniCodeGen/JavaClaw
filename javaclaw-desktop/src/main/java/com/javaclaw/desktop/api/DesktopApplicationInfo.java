package com.javaclaw.desktop.api;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/** Installed application identity; contains no executable path or user file content. */
public record DesktopApplicationInfo(String name, String displayName,
                                     String applicationId, String launchName, List<String> aliases) {
    public DesktopApplicationInfo {
        name = Objects.requireNonNull(name, "name");
        displayName = displayName == null ? "" : displayName;
        applicationId = Objects.requireNonNull(applicationId, "applicationId");
        launchName = Objects.requireNonNull(launchName, "launchName");
        if (!validIdentity(applicationId) || !validIdentity(launchName))
            throw new IllegalArgumentException("invalid installed application identity");
        aliases = List.copyOf(aliases);
        if (!validIdentity(name) || !displayName.isEmpty() && !validIdentity(displayName)
                || aliases.size() > 16 || aliases.stream().anyMatch(alias -> !validIdentity(alias)))
            throw new IllegalArgumentException("invalid or overlarge application metadata");
    }

    private static boolean validIdentity(String value) {
        return !value.isBlank() && value.equals(value.strip()) && !value.contains("..")
                && value.indexOf('/') < 0 && value.indexOf('\\') < 0 && value.indexOf(':') < 0
                && value.codePoints().noneMatch(Character::isISOControl)
                && value.getBytes(StandardCharsets.UTF_8).length <= 256;
    }
}
