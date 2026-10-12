package com.javaclaw.framework.springai;

import com.javaclaw.framework.spi.FrameworkContextReadTool;
import com.javaclaw.framework.spi.FrameworkTool;

/** Host implementation identity for internal tools; plugin supplied names and markers do not confer trust. */
public final class TrustedFrameworkToolIdentity {
    private TrustedFrameworkToolIdentity() { }

    public static boolean isContextRead(FrameworkTool tool) {
        if (!(tool instanceof FrameworkContextReadTool)) return false;
        Class<?> owner = tool.getClass().getEnclosingClass();
        return owner == OnDemandContextReads.class || owner == FixedContextSession.class;
    }

    public static boolean isToolCatalog(FrameworkTool tool) {
        return tool != null && tool.getClass() == ToolCatalogSession.class;
    }
}
