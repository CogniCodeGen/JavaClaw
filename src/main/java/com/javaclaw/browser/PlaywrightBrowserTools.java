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
    private final boolean ownsBrowserManager;
    private final List<Object> toolObjects;

    public PlaywrightBrowserTools(PlaywrightBrowserManager browserManager,
            SiteCredentialManager siteCredentials, ToolCallOrigin origin, JsonCodec json,
            boolean ownsBrowserManager, String threadBrowserScope) {
        this.browserManager = Objects.requireNonNull(browserManager, "browserManager");
        SiteCredentialManager checkedCredentials =
                Objects.requireNonNull(siteCredentials, "siteCredentials");
        ToolCallOrigin checkedOrigin = origin == null ? ToolCallOrigin.UNKNOWN : origin;
        SnapshotManager snapshots = new SnapshotManager();
        BrowserOperationGate gate = new BrowserOperationGate();
        BrowserSiteTools site =
                new BrowserSiteTools(
                        browserManager, checkedCredentials, snapshots, checkedOrigin, gate, json);
        BrowserPageTools page = new BrowserPageTools(browserManager, snapshots, checkedOrigin, gate);
        BrowserReadTools read = new BrowserReadTools(browserManager, snapshots, gate);
        BrowserSessionTools session = new BrowserSessionTools(browserManager, snapshots, checkedOrigin, gate);
        this.toolObjects = List.of(site, page, read, session);
        this.ownsBrowserManager = ownsBrowserManager;
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
        if (ownsBrowserManager) {
            browserManager.shutdown();
        }
    }
}
