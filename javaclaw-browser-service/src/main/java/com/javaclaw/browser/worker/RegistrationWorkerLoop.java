package com.javaclaw.browser.worker;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.function.Supplier;

import com.microsoft.playwright.Playwright;

import com.javaclaw.browser.protocol.BrowserRegistrationProtocol;
import com.javaclaw.browser.protocol.BrowserWorkerProtocol;
import com.javaclaw.browser.protocol.InteractiveBrowserProtocol;
import com.javaclaw.browser.protocol.InteractiveBrowserProtocol.Packet;
import com.javaclaw.builtin.contracts.BrowserContracts;
import com.javaclaw.builtin.contracts.SiteRegistrationContracts;
import com.javaclaw.protocol.CanonicalJson;

/** 登记独立事件循环复用私有 framing；读线程不触碰 Playwright，完成命令无论成功失败均只执行一次。 */
final class RegistrationWorkerLoop {
    private RegistrationWorkerLoop() {}

    static void run(BrowserWorkerProtocol.Command initial, InputStream input, OutputStream output, CanonicalJson json)
            throws IOException {
        run(initial, input, output, json, Playwright::create);
    }

    static void run(
            BrowserWorkerProtocol.Command initial,
            InputStream input,
            OutputStream output,
            CanonicalJson json,
            Supplier<Playwright> factory)
            throws IOException {
        var task = json.decode(initial.payload(), SiteRegistrationContracts.WorkerTask.class);
        try (InteractiveWorkerConnection connection = new InteractiveWorkerConnection(input, output, json);
                RegistrationBrowserActor actor = new RegistrationBrowserActor(task, connection, factory)) {
            try {
                connection.reply(initial.id(), actor.open(), new byte[0]);
            } catch (RuntimeException failed) {
                actor.close();
                connection.failure(initial.id(), "BROWSER_START_FAILED");
            }
            while (connection.active()) {
                Packet packet = connection.poll();
                if (packet == null) {
                    actor.pump();
                    continue;
                }
                try (packet) {
                    handle(packet, actor, connection, json);
                }
            }
        }
    }

    private static void handle(
            Packet packet, RegistrationBrowserActor actor, InteractiveWorkerConnection connection, CanonicalJson json)
            throws IOException {
        boolean completing =
                BrowserRegistrationProtocol.COMPLETE.equals(packet.frame().operation());
        try {
            if (packet.frame().binaryBytes() != 0) {
                throw new IllegalArgumentException("BROWSER_UNEXPECTED_PRIVATE_INPUT");
            }
            dispatch(packet, actor, connection, json);
        } catch (RuntimeException failure) {
            if (completing) {
                actor.close();
            }
            String message = failure.getMessage();
            connection.failure(
                    packet.frame().id(),
                    message != null && message.matches("BROWSER_[A-Z_]{1,80}") ? message : "BROWSER_ACTION_FAILED");
        }
    }

    private static void dispatch(
            Packet packet, RegistrationBrowserActor actor, InteractiveWorkerConnection connection, CanonicalJson json)
            throws IOException {
        long id = packet.frame().id();
        switch (packet.frame().operation()) {
            case InteractiveBrowserProtocol.STATUS -> {
                var status = actor.view();
                connection.reply(id, status, new byte[0]);
            }
            case InteractiveBrowserProtocol.LEASE ->
                connection.reply(
                        id,
                        actor.updateLease(json.decode(packet.frame().payload(), BrowserContracts.AccessLease.class)),
                        new byte[0]);
            case BrowserRegistrationProtocol.COMPLETE -> {
                try (RegistrationExport exported = actor.complete(
                        json.decode(packet.frame().payload(), SiteRegistrationContracts.CompleteRequest.class))) {
                    byte[] bytes = exported.bytes();
                    try {
                        connection.reply(id, exported.metadata(), bytes);
                    } finally {
                        Arrays.fill(bytes, (byte) 0);
                    }
                }
            }
            case InteractiveBrowserProtocol.CLOSE -> {
                actor.close();
                connection.reply(id, actor.view(), new byte[0]);
            }
            default -> throw new IllegalArgumentException("BROWSER_UNSUPPORTED_REGISTRATION_COMMAND");
        }
        // 完成、取消和明确拒绝只关闭 Context。Worker 根进程等待宿主 owner.close，
        // 保证原生监护能够确认整树回收，不能用自行退出伪造清理成功。
    }
}
