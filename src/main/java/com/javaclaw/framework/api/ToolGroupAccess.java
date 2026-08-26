package com.javaclaw.framework.api;

import java.util.Set;

/** Public request contract for model exposure and execution-time tool-group authorization. */
public final class ToolGroupAccess {
    public static final String ATTRIBUTE = "framework.allowedToolGroups";

    private ToolGroupAccess() { }

    public static boolean allows(RunRequest request, String group) {
        return ToolAccessPolicy.from(request).allowsGroup(group);
    }

    /**
     * Returns the exact configured group set, or {@code null} for legacy/all-groups requests.
     * A present empty array intentionally denies every business tool group.
     */
    public static Set<String> configuredGroups(RunRequest request) {
        return ToolAccessPolicy.from(request).configuredGroupsOrNull();
    }
}
