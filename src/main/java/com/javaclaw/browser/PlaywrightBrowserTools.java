package com.javaclaw.browser;

import com.javaclaw.agent.ToolCallOrigin;
import com.javaclaw.agent.ToolObjectProvider;
import com.javaclaw.platform.json.JsonCodec;
import com.javaclaw.site.SiteCredentialManager;

import java.util.List;
import java.util.Objects;

/**
 * Lifecycle owner for the Playwright tool components.
 *
 * <p>The framework scans each object returned by {@link #toolObjects()}. All components share one
 * interruptible operation gate, so a BrowserContext/Page is never accessed concurrently. Closing
 * this bundle shuts down the browser only when ownership was explicitly transferred at construction.
 */
public final class PlaywrightBrowserTools implements AutoCloseable, ToolObjectProvider {

    private final PlaywrightBrowserManager browserManager;
    private final BrowserInteractionState interaction;
    private final boolean ownsBrowserManager;
    private final boolean ownsInteractionState;
    private final List<Object> toolObjects;

    public PlaywrightBrowserTools(PlaywrightBrowserManager browserManager,
            SiteCredentialManager siteCredentials, ToolCallOrigin origin, JsonCodec json,
            boolean ownsBrowserManager, String threadBrowserScope) {
        this(browserManager, siteCredentials, origin, json, ownsBrowserManager,
                threadBrowserScope, new BrowserInteractionState(), true, false);
    }

    public PlaywrightBrowserTools(PlaywrightBrowserManager browserManager,
            SiteCredentialManager siteCredentials, ToolCallOrigin origin, JsonCodec json,
            boolean ownsBrowserManager, String threadBrowserScope, BrowserInteractionState interaction) {
        this(browserManager, siteCredentials, origin, json, ownsBrowserManager,
                threadBrowserScope, interaction, ownsBrowserManager, false);
    }

    public PlaywrightBrowserTools(PlaywrightBrowserManager browserManager,
            SiteCredentialManager siteCredentials, ToolCallOrigin origin, JsonCodec json,
            boolean ownsBrowserManager, String threadBrowserScope, BrowserInteractionState interaction,
            boolean eventDrivenInteraction) {
        this(browserManager, siteCredentials, origin, json, ownsBrowserManager,
                threadBrowserScope, interaction, ownsBrowserManager, eventDrivenInteraction);
    }

    private PlaywrightBrowserTools(PlaywrightBrowserManager browserManager,
            SiteCredentialManager siteCredentials, ToolCallOrigin origin, JsonCodec json,
            boolean ownsBrowserManager, String threadBrowserScope, BrowserInteractionState interaction,
            boolean ownsInteractionState, boolean eventDrivenInteraction) {
        this.browserManager = Objects.requireNonNull(browserManager, "browserManager");
        this.interaction = Objects.requireNonNull(interaction, "interaction");
        SiteCredentialManager checkedCredentials =
                Objects.requireNonNull(siteCredentials, "siteCredentials");
        ToolCallOrigin checkedOrigin = origin == null ? ToolCallOrigin.UNKNOWN : origin;
        SnapshotManager snapshots = interaction.snapshots();
        BrowserOperationGate gate = interaction.gate();
        BrowserSiteTools site =
                new BrowserSiteTools(
                        browserManager, checkedCredentials, snapshots, checkedOrigin, gate, json,
                        interaction, eventDrivenInteraction);
        BrowserPageTools page = new BrowserPageTools(browserManager, snapshots, checkedOrigin, gate);
        BrowserReadTools read = new BrowserReadTools(browserManager, snapshots, gate);
        BrowserSessionTools session = new BrowserSessionTools(browserManager, snapshots, checkedOrigin, gate);
        this.toolObjects = List.of(site, page, read, session);
        this.ownsBrowserManager = ownsBrowserManager;
        this.ownsInteractionState = ownsInteractionState;
        if (threadBrowserScope != null) {
            browserManager.activateScope(threadBrowserScope);
        } else if (checkedOrigin.kind() != ToolCallOrigin.Kind.INTERACTIVE) {
            browserManager.activateScope(checkedOrigin.browserScopeId());
        }
    }

    /**
     * Returns an immutable component list. Components share this bundle's lifecycle and must not be
     * retained after {@link #close()}.
     */
    @Override
    public List<Object> toolObjects() {
        return toolObjects;
    }

    /** Tool component types used for startup-time authorization contract validation. */
    public static List<Class<?>> toolContractTypes() {
        return List.of(BrowserSiteTools.class, BrowserPageTools.class,
                BrowserReadTools.class, BrowserSessionTools.class);
    }

    @Override
    public void close() {
        try {
            if (ownsInteractionState) interaction.close();
        } finally {
            if (ownsBrowserManager) browserManager.shutdown();
        }
    }
}
