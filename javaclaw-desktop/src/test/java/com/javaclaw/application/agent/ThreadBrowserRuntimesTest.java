package com.javaclaw.application.agent;

import com.javaclaw.browser.PlaywrightBrowserManager;
import com.javaclaw.framework.api.RunScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

class ThreadBrowserRuntimesTest {
    @TempDir Path directory;

    @Test void browserPagesAreStablePerCompleteThreadScopeAndDeletionCannotRecreateThem() {
        var factory = new PlaywrightBrowserManager(true, directory, directory);
        var cleared = new ArrayList<String>();
        var runtimes = new ThreadBrowserRuntimes("workspace", factory, cleared::add);
        var first = new RunScope("workspace", "user-a", "thread");
        var second = new RunScope("workspace", "user-a", "other");
        var otherUser = new RunScope("workspace", "user-b", "thread");
        var browser = runtimes.acquire(first);
        assertSame(browser, runtimes.acquire(first));
        assertNotSame(browser, runtimes.acquire(second));
        assertNotSame(browser, runtimes.acquire(otherUser));
        factory.activateScope("unrelated-global-selection");
        assertEquals(ThreadBrowserRuntimes.scopeId(first), browser.getActiveScopeId());
        runtimes.deleting(first);
        assertThrows(IllegalStateException.class, () -> runtimes.acquire(first));
        assertThrows(IllegalStateException.class, browser::ensureLaunched);
        assertThrows(IllegalStateException.class, () -> browser.activateScope("late-callback"));
        assertThrows(IllegalStateException.class, () -> browser.createIsolated("late-child"));
        assertTrue(cleared.contains(ThreadBrowserRuntimes.scopeId(first)));
        assertNotNull(runtimes.acquire(second));
        assertThrows(SecurityException.class, () -> runtimes.acquire(new RunScope("other", "user-a", "thread")));
        runtimes.close();
        assertThrows(IllegalStateException.class, () -> runtimes.acquire(second));
        factory.shutdown();
    }
}
