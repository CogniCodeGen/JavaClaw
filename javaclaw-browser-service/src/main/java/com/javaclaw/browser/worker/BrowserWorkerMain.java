package com.javaclaw.browser.worker;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;

import com.javaclaw.browser.protocol.BrowserFrameIo;
import com.javaclaw.browser.protocol.BrowserWorkerProtocol;
import com.javaclaw.protocol.CanonicalJson;

/** 只通过 stdin/stdout 私有 framing 提供页面快照的隔离 Worker 入口。 */
public final class BrowserWorkerMain {
    private BrowserWorkerMain() {}

    /**
     * 运行 Worker，直到宿主关闭 stdin。
     *
     * @param args 不接受参数
     * @throws Exception framing 或 stdout 写入失败
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            throw new IllegalArgumentException("Browser Worker does not accept arguments");
        }
        CanonicalJson json = new CanonicalJson();
        BrowserWorkerProtocol.Command command =
                BrowserFrameIo.readJson(System.in, json, BrowserWorkerProtocol.Command.class);
        byte[] storageState = BrowserFrameIo.readBinary(
                System.in, command.sensitiveStateBytes(), BrowserWorkerProtocol.MAXIMUM_STATE_BYTES);
        BrowserNetworkChannel network = new BrowserNetworkChannel(command.id(), System.in, System.out, json);
        try (BrowserRequestHandler handler =
                        new BrowserRequestHandler(new PlaywrightBrowserSession(Clock.systemUTC(), network), json);
                BrowserLoginControl control = loginControl(command)) {
            try (BrowserWorkerReply reply =
                    handler.handle(command, storageState, control, event -> writeEvent(json, event))) {
                BrowserFrameIo.writeJson(System.out, json, reply.message());
                byte[] sensitive = reply.sensitiveBytes();
                try {
                    BrowserFrameIo.writeBinary(System.out, sensitive, BrowserWorkerProtocol.MAXIMUM_STATE_BYTES);
                } finally {
                    Arrays.fill(sensitive, (byte) 0);
                }
            }
        } finally {
            Arrays.fill(storageState, (byte) 0);
        }
    }

    private static BrowserLoginControl loginControl(BrowserWorkerProtocol.Command command) {
        if (!BrowserWorkerProtocol.LOGIN.equals(command.operation())
                && !BrowserWorkerProtocol.OAUTH.equals(command.operation())) {
            return new BrowserLoginControl() {
                @Override
                public Decision decision() {
                    return Decision.WAIT;
                }

                @Override
                public void close() {}
            };
        }
        String root = System.getenv(FileBrowserLoginControl.CONTROL_ROOT_ENVIRONMENT);
        if (root == null || root.isBlank()) {
            throw new IllegalStateException("Browser login control root is unavailable");
        }
        if (BrowserWorkerProtocol.LOGIN.equals(command.operation())) {
            BrowserWorkerProtocol.LoginTask task =
                    new CanonicalJson().decode(command.payload(), BrowserWorkerProtocol.LoginTask.class);
            return new FileBrowserLoginControl(Path.of(root), task.sessionId());
        }
        BrowserWorkerProtocol.OAuthTask task =
                new CanonicalJson().decode(command.payload(), BrowserWorkerProtocol.OAuthTask.class);
        return new FileBrowserOAuthControl(Path.of(root), task.sessionId());
    }

    private static void writeEvent(CanonicalJson json, BrowserWorkerProtocol.WorkerMessage event) {
        try {
            BrowserFrameIo.writeJson(System.out, json, event);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Browser Worker event channel failed", failure);
        }
    }
}
