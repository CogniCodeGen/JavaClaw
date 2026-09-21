package com.javaclaw.browser.testing;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.browser.protocol.BrowserFrameIo;
import com.javaclaw.browser.protocol.BrowserRegistrationProtocol;
import com.javaclaw.browser.protocol.BrowserWorkerProtocol;
import com.javaclaw.browser.protocol.InteractiveBrowserProtocol;
import com.javaclaw.browser.protocol.InteractiveBrowserProtocol.Frame;
import com.javaclaw.browser.protocol.InteractiveBrowserProtocol.Kind;
import com.javaclaw.builtin.contracts.BrowserContracts;
import com.javaclaw.builtin.contracts.SiteRegistrationContracts;
import com.javaclaw.protocol.CanonicalJson;

/** 真实私有管道的独立进程夹具；每种故障只由测试初始路径决定，不启动浏览器或访问网络。 */
public final class FakeRegistrationWorkerMain {
    private final CanonicalJson json = new CanonicalJson();
    private SiteRegistrationContracts.WorkerTask task;
    private BrowserContracts.AccessLease lease;
    private long revision = 1;

    private FakeRegistrationWorkerMain() {}

    /**
     * @param args 无参数
     * @throws Exception 固定协议夹具出现 I/O 失败
     */
    public static void main(String[] args) throws Exception {
        new FakeRegistrationWorkerMain().run();
    }

    private void run() throws Exception {
        var initial = BrowserFrameIo.readJson(System.in, json, BrowserWorkerProtocol.Command.class);
        task = json.decode(initial.payload(), SiteRegistrationContracts.WorkerTask.class);
        lease = task.lease();
        if (!initial.operation().equals(BrowserRegistrationProtocol.OPEN) || initial.sensitiveStateBytes() != 0) {
            throw new IllegalArgumentException("Unexpected registration startup");
        }
        if (path("/slow-start")) {
            Thread.sleep(5_000);
        }
        reply(initial.id(), status(SiteRegistrationContracts.State.ACTIVE), new byte[0]);
        while (true) {
            Frame frame = BrowserFrameIo.readJson(System.in, json, Frame.class);
            byte[] input = BrowserFrameIo.readBinary(
                    System.in, frame.binaryBytes(), BrowserWorkerProtocol.MAXIMUM_NETWORK_BYTES);
            Arrays.fill(input, (byte) 0);
            if (!handle(frame)) {
                return;
            }
        }
    }

    private boolean handle(Frame frame) throws Exception {
        switch (frame.operation()) {
            case InteractiveBrowserProtocol.LEASE -> {
                lease = json.decode(frame.payload(), BrowserContracts.AccessLease.class);
                reply(frame.id(), status(SiteRegistrationContracts.State.ACTIVE), new byte[0]);
            }
            case InteractiveBrowserProtocol.STATUS -> {
                if (path("/slow-status")) {
                    Thread.sleep(5_000);
                }
                revision++;
                var state = path("/window-closed")
                        ? SiteRegistrationContracts.State.CANCELLED
                        : SiteRegistrationContracts.State.ACTIVE;
                reply(frame.id(), status(state), path("/private-status") ? new byte[] {1} : new byte[0]);
            }
            case BrowserRegistrationProtocol.COMPLETE -> {
                if (path("/slow-complete")) {
                    Thread.sleep(5_000);
                }
                complete(frame);
            }
            default -> throw new IllegalArgumentException("Unexpected registration command");
        }
        return true;
    }

    private void complete(Frame frame) throws Exception {
        var request = json.decode(frame.payload(), SiteRegistrationContracts.CompleteRequest.class);
        byte[] state = "{\"cookies\":[],\"origins\":[]}".getBytes(StandardCharsets.UTF_8);
        byte[] credentials =
                request.credentialId().isPresent() ? "user\0secret".getBytes(StandardCharsets.UTF_8) : new byte[0];
        byte[] combined = Arrays.copyOf(state, state.length + credentials.length);
        System.arraycopy(credentials, 0, combined, state.length, credentials.length);
        try {
            int extra = path("/bad-size") ? 1 : 0;
            var metadata = new BrowserRegistrationProtocol.PrivateResult(
                    status(SiteRegistrationContracts.State.ACTIVE), state.length + extra, credentials.length);
            reply(frame.id(), metadata, combined);
        } finally {
            Arrays.fill(state, (byte) 0);
            Arrays.fill(credentials, (byte) 0);
            Arrays.fill(combined, (byte) 0);
        }
    }

    private SiteRegistrationContracts.WorkerStatus status(SiteRegistrationContracts.State state) {
        long generation = path("/bad-generation") ? lease.generation() + 1 : lease.generation();
        return new SiteRegistrationContracts.WorkerStatus(
                task.sessionId(),
                state,
                new SiteRegistrationContracts.Access(generation, lease.allowedOrigins(), Set.of(), lease.expiresAt()),
                new SiteRegistrationContracts.Page(
                        revision,
                        Optional.of(SiteRegistrationContracts.displayUri(task.initialUri())),
                        "Example",
                        List.of()));
    }

    private boolean path(String path) {
        return task.initialUri().getPath().equals(path);
    }

    private void reply(long id, Object payload, byte[] bytes) throws Exception {
        BrowserFrameIo.writeJson(
                System.out, json, new Frame(Kind.REPLY, id, "", json.encode(payload), bytes.length, Optional.empty()));
        BrowserFrameIo.writeBinary(System.out, bytes, BrowserWorkerProtocol.MAXIMUM_NETWORK_BYTES);
    }
}
