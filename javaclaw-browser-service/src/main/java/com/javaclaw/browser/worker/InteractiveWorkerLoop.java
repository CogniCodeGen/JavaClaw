package com.javaclaw.browser.worker;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.function.Supplier;

import com.microsoft.playwright.Playwright;

import com.javaclaw.browser.client.BrowserActionResult;
import com.javaclaw.browser.protocol.BrowserWorkerProtocol;
import com.javaclaw.browser.protocol.InteractiveBrowserProtocol;
import com.javaclaw.browser.protocol.InteractiveBrowserProtocol.Packet;
import com.javaclaw.builtin.contracts.BrowserContracts;
import com.javaclaw.protocol.CanonicalJson;

/** 常驻 Browser 主线程事件循环；等待命令期间仍泵送可见窗口事件，网络在每次 route 重新检查租约。 */
final class InteractiveWorkerLoop {
    private InteractiveWorkerLoop() {}

    static void run(
            BrowserWorkerProtocol.Command initial,
            byte[] state,
            InputStream input,
            OutputStream output,
            CanonicalJson json)
            throws IOException {
        run(initial, state, input, output, json, Playwright::create);
    }

    /** 允许本地夹具替换外部进程创建，命令分发与资源关闭顺序保持一致。 */
    static void run(
            BrowserWorkerProtocol.Command initial,
            byte[] state,
            InputStream input,
            OutputStream output,
            CanonicalJson json,
            Supplier<Playwright> factory)
            throws IOException {
        BrowserContracts.OpenTask task = json.decode(initial.payload(), BrowserContracts.OpenTask.class);
        try (InteractiveWorkerConnection connection = new InteractiveWorkerConnection(input, output, json);
                InteractiveBrowserActor actor = new InteractiveBrowserActor(task, connection, factory)) {
            try (BrowserActionResult result = actor.open(state)) {
                reply(connection, initial.id(), result);
            } catch (RuntimeException failed) {
                connection.failure(initial.id(), "BROWSER_START_FAILED");
                return;
            }
            while (connection.active() && actor.alive()) {
                Packet packet = connection.poll();
                if (packet == null) {
                    actor.pump();
                    continue;
                }
                try (packet) {
                    if (!handle(packet, actor, connection, json)) {
                        return;
                    }
                }
            }
        }
    }

    private static boolean handle(
            Packet packet, InteractiveBrowserActor actor, InteractiveWorkerConnection connection, CanonicalJson json)
            throws IOException {
        long id = packet.frame().id();
        byte[] bytes = packet.bytes();
        try {
            return command(packet, bytes, actor, connection, json);
        } catch (RuntimeException | IOException failure) {
            String message = failure.getMessage();
            String code =
                    message != null && message.matches("BROWSER_[A-Z_]{1,80}") ? message : "BROWSER_ACTION_FAILED";
            connection.failure(id, code);
            return true;
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private static boolean command(
            Packet packet,
            byte[] bytes,
            InteractiveBrowserActor actor,
            InteractiveWorkerConnection connection,
            CanonicalJson json)
            throws IOException {
        long id = packet.frame().id();
        switch (packet.frame().operation()) {
            case InteractiveBrowserProtocol.ACTION -> {
                try (BrowserActionResult result = actor.act(
                        json.decode(packet.frame().payload(), InteractiveBrowserProtocol.ActionRequest.class), bytes)) {
                    reply(connection, id, result);
                }
            }
            case InteractiveBrowserProtocol.LEASE ->
                connection.reply(
                        id,
                        actor.updateLease(json.decode(packet.frame().payload(), BrowserContracts.AccessLease.class)),
                        new byte[0]);
            case InteractiveBrowserProtocol.STATUS -> connection.reply(id, actor.view(), new byte[0]);
            case InteractiveBrowserProtocol.FILL_CREDENTIALS -> {
                try (BrowserActionResult result = actor.fillCredentials(
                        json.decode(packet.frame().payload(), InteractiveBrowserProtocol.CredentialsRequest.class),
                        bytes)) {
                    reply(connection, id, result);
                }
            }
            case InteractiveBrowserProtocol.SAVE -> privateReply(connection, id, actor.view(), actor.saveState());
            case InteractiveBrowserProtocol.CAPTURE ->
                privateReply(
                        connection,
                        id,
                        actor.view(),
                        actor.capture(json.decode(
                                packet.frame().payload(), InteractiveBrowserProtocol.CredentialsRequest.class)));
            case InteractiveBrowserProtocol.PREPARE_CREDENTIALS ->
                connection.reply(
                        id,
                        new InteractiveBrowserProtocol.FormsResult(
                                actor.view(),
                                actor.prepare(json.decode(
                                        packet.frame().payload(), InteractiveBrowserProtocol.FormsRequest.class))),
                        new byte[0]);
            case InteractiveBrowserProtocol.CLOSE -> {
                actor.close();
                connection.reply(id, actor.view(), new byte[0]);
                return false;
            }
            default -> throw new IllegalArgumentException("unsupported interactive Browser command");
        }
        return true;
    }

    private static void reply(InteractiveWorkerConnection connection, long id, BrowserActionResult result)
            throws IOException {
        byte[] bytes = result.content();
        try {
            connection.reply(id, result.observation(), bytes);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private static void privateReply(InteractiveWorkerConnection connection, long id, Object metadata, byte[] bytes)
            throws IOException {
        try {
            connection.reply(id, metadata, bytes);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }
}
