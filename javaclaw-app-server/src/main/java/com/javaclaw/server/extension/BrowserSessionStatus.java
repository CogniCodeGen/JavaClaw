package com.javaclaw.server.extension;

import java.util.Optional;

import com.javaclaw.api.ThreadId;
import com.javaclaw.builtin.contracts.BrowserCommands;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.ManagedExtensionStore;

/** 浏览器管理投影独立读取续接状态；未创建窗口时仍展示预算或配置失败。 */
final class BrowserSessionStatus {
    private BrowserSessionStatus() {}

    static BrowserCommands.Status read(
            SiteBrowserHostContext host,
            ManagedExtensionStore store,
            boolean available,
            BrowserSessionState session,
            ThreadId thread,
            BrowserPendingOrigins pending) {
        String detail = available ? "浏览器已就绪" : "当前平台尚未通过可见浏览器隔离验证";
        try {
            var workspace = host.core().workspaceForThread(thread).id();
            var continuation = store.inTransaction(
                    new ExtensionId(BuiltinExtensionIds.SITE),
                    tx -> tx.get("browser.continuation-status." + workspace, thread.toString())
                            .map(value ->
                                    host.json().decode(value.payload(), BrowserCommands.ContinuationStatus.class)));
            return new BrowserCommands.Status(
                    available,
                    session == null ? Optional.empty() : Optional.ofNullable(session.view),
                    session == null ? detail : pending.detail(session, detail),
                    continuation);
        } catch (Exception failure) {
            throw new IllegalStateException("浏览器续接状态读取失败", failure);
        }
    }
}
