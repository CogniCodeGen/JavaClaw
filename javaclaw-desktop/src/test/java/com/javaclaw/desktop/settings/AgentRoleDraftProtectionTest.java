package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AgentRole;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRoleDraftProtectionTest {
    @Test
    void 普通刷新和错误态刷新都保留草稿及原保存版本() {
        RoleSettingsTestGateway gateway = new RoleSettingsTestGateway();
        AgentRoleSettingsPresenter presenter = loaded(gateway);
        AgentRoleDraft draft = edited(presenter.state().draft());
        presenter.updateDraft(draft);
        presenter.reload();
        assertEquals(1, gateway.roleReadCalls);
        gateway.writes.add(CompletableFuture.failedFuture(RoleSettingsTestGateway.conflict()));
        presenter.save();
        presenter.reload();

        assertEquals(1, gateway.roleReadCalls);
        assertEquals(draft, presenter.state().draft());
        assertEquals(1, presenter.state().selected().orElseThrow().revision());
        assertTrue(presenter.state().revisionConflict());
        assertTrue(presenter.state().dirty());
    }

    @Test
    void 导入其他角色及当前角色的新版本均不替换未保存草稿() {
        RoleSettingsTestGateway gateway = new RoleSettingsTestGateway();
        AgentRoleSettingsPresenter presenter = loaded(gateway);
        AgentRoleDraft baseline = presenter.state().baseline();
        AgentRoleDraft draft = edited(baseline);
        presenter.updateDraft(draft);
        presenter.acceptImported(RoleSettingsTestGateway.role("imported", 1));
        presenter.acceptImported(RoleSettingsTestGateway.role("reviewer", 2));

        assertEquals(2, presenter.state().roles().size());
        assertEquals(draft, presenter.state().draft());
        assertEquals(baseline, presenter.state().baseline());
        assertEquals(1, presenter.state().selected().orElseThrow().revision());
        assertEquals(1, gateway.roleReadCalls, "导入权威结果不应触发全量刷新");
        presenter.discardDraft();
        presenter.acceptImported(RoleSettingsTestGateway.role("reviewer", 3));
        assertEquals(3, presenter.state().selected().orElseThrow().revision());
        assertFalse(presenter.state().dirty());
    }

    @Test
    void 写入期间拒绝更换草稿且页面关闭后忽略旧写入响应() {
        RoleSettingsTestGateway gateway = new RoleSettingsTestGateway();
        AgentRoleSettingsPresenter presenter = loaded(gateway);
        AgentRoleDraft draft = edited(presenter.state().draft());
        presenter.updateDraft(draft);
        CompletableFuture<AgentRole> response = new CompletableFuture<>();
        gateway.writes.add(response);
        presenter.save();
        presenter.createDraft();
        presenter.select(RoleSettingsTestGateway.role("other", 1));
        presenter.discardDraft();
        presenter.updateDraft(AgentRoleDraft.empty());
        presenter.reload();
        assertEquals(draft, presenter.state().draft());
        assertTrue(presenter.state().pending());
        assertEquals(1, gateway.roleReadCalls);

        AgentRoleSettingsState beforeDispose = presenter.state();
        presenter.dispose();
        response.complete(RoleSettingsTestGateway.role("reviewer", 2));
        assertEquals(beforeDispose, presenter.state());
    }

    @Test
    void 比较只读取快照而不推进本地revision或替换草稿() {
        RoleSettingsTestGateway gateway = new RoleSettingsTestGateway();
        AgentRoleSettingsPresenter presenter = loaded(gateway);
        AgentRoleDraft draft = edited(presenter.state().draft());
        presenter.updateDraft(draft);
        CompletableFuture<List<AgentRole>> response = new CompletableFuture<>();
        gateway.roleReads.add(response);
        AtomicReference<AgentRoleComparison> comparison = new AtomicReference<>();
        presenter.compare(comparison::set);
        assertTrue(presenter.state().pending());
        assertNull(comparison.get());
        response.complete(List.of(RoleSettingsTestGateway.role("reviewer", 2)));

        assertEquals(1, comparison.get().expectedRevision());
        assertEquals(2, comparison.get().latest().orElseThrow().revision());
        assertTrue(comparison.get().text().contains(draft.developerInstructions()));
        assertEquals(draft, presenter.state().draft());
        assertEquals(1, presenter.state().selected().orElseThrow().revision());
        assertFalse(presenter.state().pending());
    }

    @Test
    void 外部操作期间拒绝角色刷新和编辑并丢弃关闭后的目录响应() {
        RoleSettingsTestGateway gateway = new RoleSettingsTestGateway();
        AgentRoleSettingsPresenter presenter = loaded(gateway);
        AgentRoleSettingsState before = presenter.state();
        presenter.externalPending(true);
        presenter.createDraft();
        presenter.updateDraft(edited(before.draft()));
        presenter.reload();
        assertEquals(before, presenter.state());
        presenter.externalPending(false);
        CompletableFuture<List<AgentRole>> response = new CompletableFuture<>();
        gateway.roleReads.add(response);
        presenter.reload();
        AgentRoleSettingsState reading = presenter.state();
        presenter.dispose();
        response.complete(List.of(RoleSettingsTestGateway.role("other", 7)));
        assertEquals(reading, presenter.state());
    }

    private static AgentRoleSettingsPresenter loaded(RoleSettingsTestGateway gateway) {
        AgentRoleSettingsPresenter presenter = new AgentRoleSettingsPresenter(gateway.core);
        presenter.reload();
        return presenter;
    }

    private static AgentRoleDraft edited(AgentRoleDraft source) {
        return new AgentRoleDraft(
                source.id(),
                source.name(),
                source.description(),
                "需要保留的本地指令",
                source.provider(),
                source.reasoning(),
                source.capabilities(),
                source.skills(),
                source.constraint(),
                source.lifecycle(),
                source.extensions());
    }
}
