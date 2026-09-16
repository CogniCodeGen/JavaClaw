package com.javaclaw.server.extension;

import java.time.Clock;

/** 只有宿主接纳的显式动作续期；后台网络、状态查询和自动保存均不得重置空闲期限。 */
final class BrowserSessionActivity {
    private BrowserSessionActivity() {}

    static void touch(BrowserSessionState session, BrowserSessionState.Access access, Clock clock) {
        synchronized (session) {
            if (session.closed
                    || session.closing
                    || session.access != access
                    || access.cancelled().isCancelled()
                    || !access.lease().active(clock.instant())) {
                throw new SecurityException("浏览器动作所属控制代次已失效");
            }
            session.touchedAt = clock.instant();
        }
    }
}
