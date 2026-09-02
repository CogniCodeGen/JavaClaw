package com.javaclaw.desktop.settings;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ExecutionState;
import com.javaclaw.client.RemoteRpcException;
import com.javaclaw.protocol.JsonRpcError;
import com.javaclaw.protocol.ProtocolErrorCode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutomationJobSettingsPresenterTest {
    @Test
    void 按WorkspaceExtension和状态分页并读取工作单元() {
        TestAutomationJobSettingsGateway gateway = new TestAutomationJobSettingsGateway();
        AutomationJobSettingsPresenter presenter = new AutomationJobSettingsPresenter(gateway);
        AtomicReference<AutomationJobSettingsState> latest = subscribe(presenter);

        presenter.reload();
        assertEquals(2, latest.get().page().jobs().size());
        assertEquals(2, latest.get().detail().value().orElseThrow().units().size());

        presenter.filter(
                Optional.of(gateway.workspace.id()),
                Optional.of("com.javaclaw.workflow"),
                Set.of(ExecutionState.RUNNING));
        assertEquals(Optional.of(gateway.workspace.id()), gateway.lastWorkspace);
        assertEquals(Optional.of("com.javaclaw.workflow"), gateway.lastExtension);
        assertEquals(Set.of(ExecutionState.RUNNING), gateway.lastStates);

        presenter.nextPage();
        assertEquals(1, latest.get().page().index());
        assertTrue(gateway.lastCursor.isPresent());
        assertEquals(
                "knowledge-job", latest.get().page().selected().orElseThrow().id());
        presenter.previousPage();
        assertEquals(0, latest.get().page().index());
    }

    @Test
    void 快速切换Job时丢弃较早详情响应() {
        TestAutomationJobSettingsGateway gateway = new TestAutomationJobSettingsGateway();
        AutomationJobSettingsPresenter presenter = new AutomationJobSettingsPresenter(gateway);
        AtomicReference<AutomationJobSettingsState> latest = subscribe(presenter);
        presenter.reload();
        gateway.manualReads = true;

        presenter.select(gateway.firstPage.get(0));
        presenter.select(gateway.firstPage.get(1));
        gateway.completeRead("plan-job");
        gateway.completeRead("workflow-job");

        assertEquals("plan-job", latest.get().page().selected().orElseThrow().id());
        assertEquals(
                "plan-job", latest.get().detail().value().orElseThrow().job().id());
    }

    @Test
    void 生命周期动作携带幂等键和预期revision并投影冲突() {
        TestAutomationJobSettingsGateway gateway = new TestAutomationJobSettingsGateway();
        AutomationJobSettingsPresenter presenter = new AutomationJobSettingsPresenter(gateway);
        AtomicReference<AutomationJobSettingsState> latest = subscribe(presenter);
        presenter.reload();

        long expectedRevision =
                latest.get().detail().value().orElseThrow().job().revision();
        presenter.pause();
        assertEquals("pause", gateway.lastAction);
        assertEquals(expectedRevision, gateway.lastOptions.expectedRevision());
        assertFalse(gateway.lastOptions.idempotencyKey().isBlank());
        assertEquals(
                ExecutionState.PAUSED,
                latest.get().detail().value().orElseThrow().job().state());

        gateway.nextMutationFailure = revisionConflict();
        presenter.resume();
        assertEquals(SettingsLoadState.ERROR, latest.get().feedback().phase());
        assertTrue(latest.get().detail().revisionConflict());
        assertNotNull(latest.get().detail().value().orElseThrow());

        presenter.refreshSelected();
        presenter.cancel();
        assertEquals("cancel", gateway.lastAction);
        assertEquals(
                ExecutionState.CANCELLED,
                latest.get().detail().value().orElseThrow().job().state());
    }

    @Test
    void 空目录与读取失败使用明确页面状态() {
        TestAutomationJobSettingsGateway gateway = new TestAutomationJobSettingsGateway();
        gateway.firstPage.clear();
        AutomationJobSettingsPresenter presenter = new AutomationJobSettingsPresenter(gateway);
        AtomicReference<AutomationJobSettingsState> latest = subscribe(presenter);

        presenter.reload();
        assertEquals(SettingsLoadState.READY, latest.get().feedback().phase());
        assertTrue(latest.get().page().jobs().isEmpty());
        assertTrue(latest.get().feedback().message().contains("暂无"));

        gateway.nextListFailure = new IllegalStateException("服务暂不可用");
        presenter.refreshPage();
        assertEquals(SettingsLoadState.ERROR, latest.get().feedback().phase());
        assertEquals("服务暂不可用", latest.get().feedback().message());
    }

    private static AtomicReference<AutomationJobSettingsState> subscribe(AutomationJobSettingsPresenter presenter) {
        AtomicReference<AutomationJobSettingsState> latest = new AtomicReference<>();
        presenter.subscribe(latest::set);
        return latest;
    }

    private static RemoteRpcException revisionConflict() {
        return new RemoteRpcException(new JsonRpcError(
                ProtocolErrorCode.REVISION_CONFLICT, "revision 已变化", Optional.of(new CanonicalPayload("{}"))));
    }
}
