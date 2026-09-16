package com.javaclaw.browser.testing;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.javaclaw.browser.protocol.BrowserFrameIo;
import com.javaclaw.browser.protocol.BrowserWorkerProtocol;
import com.javaclaw.browser.protocol.InteractiveBrowserProtocol;
import com.javaclaw.browser.protocol.InteractiveBrowserProtocol.Frame;
import com.javaclaw.browser.protocol.InteractiveBrowserProtocol.Kind;
import com.javaclaw.builtin.contracts.BrowserContracts;
import com.javaclaw.protocol.CanonicalJson;

/** 真实子进程与私有 framing 的常驻客户端回归夹具；不启动浏览器或联网。 */
public final class FakeInteractiveBrowserWorkerMain {
    private final CanonicalJson json = new CanonicalJson();
    private BrowserContracts.OpenTask task;
    private BrowserContracts.AccessLease lease;
    private URI uri;
    private long networkId;

    private FakeInteractiveBrowserWorkerMain() {}

    /** @param args 不接受参数 @throws Exception 夹具协议失败 */
    public static void main(String[] args) throws Exception {
        new FakeInteractiveBrowserWorkerMain().run();
    }

    private void run() throws Exception {
        BrowserWorkerProtocol.Command initial =
                BrowserFrameIo.readJson(System.in, json, BrowserWorkerProtocol.Command.class);
        byte[] state = BrowserFrameIo.readBinary(
                System.in, initial.sensitiveStateBytes(), BrowserWorkerProtocol.MAXIMUM_STATE_BYTES);
        Arrays.fill(state, (byte) 0);
        task = json.decode(initial.payload(), BrowserContracts.OpenTask.class);
        lease = task.lease();
        uri = task.uri();
        network(lease.generation());
        result(initial.id(), observation(Optional.empty(), Optional.empty()), new byte[0]);
        while (true) {
            Frame frame = BrowserFrameIo.readJson(System.in, json, Frame.class);
            byte[] bytes = BrowserFrameIo.readBinary(
                    System.in, frame.binaryBytes(), BrowserWorkerProtocol.MAXIMUM_NETWORK_BYTES);
            try {
                handle(frame, bytes);
            } finally {
                Arrays.fill(bytes, (byte) 0);
            }
        }
    }

    private void handle(Frame frame, byte[] bytes) throws Exception {
        switch (frame.operation()) {
            case InteractiveBrowserProtocol.ACTION -> action(frame, bytes);
            case InteractiveBrowserProtocol.LEASE -> {
                long previous = lease.generation();
                lease = json.decode(frame.payload(), BrowserContracts.AccessLease.class);
                if (network(previous)) {
                    throw new IllegalStateException("retired lease was accepted");
                }
                result(frame.id(), view(), new byte[0]);
            }
            case InteractiveBrowserProtocol.SAVE ->
                result(
                        frame.id(),
                        view(),
                        "{\"cookies\":[],\"origins\":[{\"indexedDB\":[]}]}".getBytes(StandardCharsets.UTF_8));
            case InteractiveBrowserProtocol.PREPARE_CREDENTIALS ->
                result(
                        frame.id(),
                        new InteractiveBrowserProtocol.FormsResult(view(), java.util.List.of()),
                        new byte[0]);
            case InteractiveBrowserProtocol.FILL_CREDENTIALS ->
                result(frame.id(), observation(Optional.empty(), Optional.empty()), new byte[0]);
            case InteractiveBrowserProtocol.CAPTURE ->
                result(frame.id(), view(), "alice\0secret".getBytes(StandardCharsets.UTF_8));
            case InteractiveBrowserProtocol.STATUS -> result(frame.id(), view(), new byte[0]);
            default -> throw new IllegalArgumentException("unsupported fake command");
        }
    }

    private void action(Frame frame, byte[] bytes) throws Exception {
        BrowserContracts.Action action = json.decode(frame.payload(), InteractiveBrowserProtocol.ActionRequest.class)
                .action();
        if (action.operation() == BrowserContracts.Operation.NAVIGATE) {
            uri = URI.create(action.input().value());
        }
        if (action.operation() == BrowserContracts.Operation.WAIT) {
            Thread.sleep(5_000);
        }
        if (action.operation() == BrowserContracts.Operation.SCREENSHOT) {
            byte[] image = new byte[] {1, 2, 3};
            BrowserContracts.Artifact artifact = new BrowserContracts.Artifact(
                    new BrowserContracts.FileSpec("browser.png", "image/png"), image.length);
            BrowserContracts.Frame imageFrame = new BrowserContracts.Frame(
                    "frame-1",
                    "page-1",
                    1,
                    lease.generation(),
                    new BrowserContracts.Viewport(1280, 900, 0, 0, 1),
                    1280,
                    900);
            result(frame.id(), observation(Optional.of(imageFrame), Optional.of(artifact)), image);
        } else {
            network(lease.generation());
            result(frame.id(), observation(Optional.empty(), Optional.empty()), new byte[0]);
        }
    }

    private boolean network(long generation) throws Exception {
        BrowserContracts.NetworkRequest request = new BrowserContracts.NetworkRequest(uri, "GET", Map.of());
        Frame frame = new Frame(
                Kind.NETWORK, ++networkId, Long.toString(generation), json.encode(request), 0, Optional.empty());
        BrowserFrameIo.writeJson(System.out, json, frame);
        Frame response = BrowserFrameIo.readJson(System.in, json, Frame.class);
        byte[] bytes = BrowserFrameIo.readBinary(
                System.in, response.binaryBytes(), BrowserWorkerProtocol.MAXIMUM_NETWORK_BYTES);
        Arrays.fill(bytes, (byte) 0);
        return response.error().isEmpty();
    }

    private BrowserContracts.SessionView view() {
        return new BrowserContracts.SessionView(
                task.sessionId(),
                task.owner(),
                BrowserContracts.SessionState.OPEN,
                lease,
                List.of(new BrowserContracts.Tab("page-1", uri, "Fake", true)));
    }

    private BrowserContracts.Observation observation(
            Optional<BrowserContracts.Frame> frame, Optional<BrowserContracts.Artifact> artifact) {
        BrowserContracts.PageSnapshot page = new BrowserContracts.PageSnapshot(
                "page-1", uri, "Fake", "process:" + ProcessHandle.current().pid(), List.of(), List.of());
        return new BrowserContracts.Observation(view(), page, frame, artifact);
    }

    private void result(long id, Object payload, byte[] bytes) throws Exception {
        BrowserFrameIo.writeJson(
                System.out, json, new Frame(Kind.REPLY, id, "", json.encode(payload), bytes.length, Optional.empty()));
        BrowserFrameIo.writeBinary(System.out, bytes, BrowserWorkerProtocol.MAXIMUM_NETWORK_BYTES);
    }
}
