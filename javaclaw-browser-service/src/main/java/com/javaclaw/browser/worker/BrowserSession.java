package com.javaclaw.browser.worker;

import java.net.URI;

import com.javaclaw.browser.protocol.BrowserWorkerProtocol;
import com.javaclaw.builtin.contracts.SiteContracts;

/** 单进程内的 Browser 生命周期边界。 */
interface BrowserSession extends AutoCloseable {
    SiteContracts.PageSnapshot snapshot(BrowserWorkerProtocol.SnapshotTask task, byte[] storageState);

    byte[] login(
            BrowserWorkerProtocol.LoginTask task, byte[] storageState, BrowserLoginControl control, Runnable ready);

    URI oauth(BrowserWorkerProtocol.OAuthTask task, BrowserLoginControl control, Runnable ready);

    @Override
    void close();
}
