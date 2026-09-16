package com.javaclaw.browser.worker;

import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.microsoft.playwright.Page;

import com.javaclaw.builtin.contracts.BrowserContracts;
import com.javaclaw.builtin.contracts.BrowserContracts.Action;
import com.javaclaw.builtin.contracts.BrowserContracts.Operation;

/** 动作测试使用真实引用管理与私有网络通道，只替换 Playwright I/O。 */
final class InteractiveActionFixture implements AutoCloseable {
    final InteractivePlaywrightFixture playwright = new InteractivePlaywrightFixture();
    final InteractiveBrowserPages pages = new InteractiveBrowserPages(playwright.context);
    final BrowserContracts.AccessLease lease = new BrowserContracts.AccessLease(
            BrowserContracts.ControlMode.ASSISTANT,
            UUID.randomUUID().toString(),
            1,
            Instant.now().plusSeconds(60),
            Set.of(URI.create("https://docs.example.com")));
    final InteractiveBrowserActions actions;
    final Page page;

    InteractiveActionFixture() {
        this(null);
    }

    InteractiveActionFixture(InteractiveWorkerConnection connection) {
        page = pages.newPage();
        page.navigate("https://docs.example.com/");
        actions = new InteractiveBrowserActions(
                playwright.context, pages, new InteractiveBrowserNetwork(connection, () -> lease), () -> lease);
    }

    InteractivePlaywrightFixture.FakePage fake() {
        return playwright.pages.getFirst();
    }

    Action element(Operation operation, int index, BrowserContracts.ActionInput input) {
        var snapshot = pages.snapshot(page);
        return new Action(
                operation,
                new BrowserContracts.Target(
                        snapshot.pageId(), snapshot.elements().get(index).reference(), ""),
                input);
    }

    Action element(Operation operation, int index, String value) {
        return element(operation, index, BrowserContracts.ActionInput.text(value));
    }

    static Action action(Operation operation, String value) {
        return new Action(operation, BrowserContracts.Target.current(), BrowserContracts.ActionInput.text(value));
    }

    Action drag(BrowserContracts.Frame frame, BrowserContracts.Point to) {
        return new Action(
                Operation.DRAG,
                new BrowserContracts.Target(frame.pageId(), "", frame.frameId()),
                new BrowserContracts.ActionInput(
                        "",
                        Optional.empty(),
                        Optional.of(new BrowserContracts.Drag(new BrowserContracts.Point(256, 180), to)),
                        Optional.empty()));
    }

    @Override
    public void close() {
        pages.close();
    }
}
