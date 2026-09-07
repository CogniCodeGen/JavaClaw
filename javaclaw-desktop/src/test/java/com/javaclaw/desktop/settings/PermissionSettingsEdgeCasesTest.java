package com.javaclaw.desktop.settings;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.ApprovalRequirement;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ToolRisk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionSettingsEdgeCasesTest {

    @Test
    void permission只读模板必须显式复制且脏草稿阻止切换() {
        TestCoreSettingsGateway gateway = new TestCoreSettingsGateway();
        PermissionProfileSettingsPresenter presenter = new PermissionProfileSettingsPresenter(gateway);
        assertThrows(IllegalStateException.class, presenter::cloneSelected);
        presenter.reload();
        assertTrue(presenter.state().standardReadOnly());
        PermissionProfileDraft standard = presenter.state().draft();
        presenter.updateDraft(copy(standard, "ignored", standard.openFiles()));
        assertEquals("standard", presenter.state().draft().id());
        presenter.save();
        assertEquals(SettingsLoadState.ERROR, presenter.state().phase());

        presenter.cloneSelected();
        presenter.save();
        assertTrue(presenter.state().message().contains("标识"));
        presenter.updateDraft(copy(presenter.state().draft(), "workspace-safe", standard.openFiles()));
        presenter.save();
        PermissionProfile custom = presenter.state().selected().orElseThrow();
        assertEquals("workspace-safe", custom.id());

        presenter.updateDraft(
                copy(presenter.state().draft(), custom.id(), custom.resources().openFiles() + 1));
        presenter.select(gateway.permissions.getFirst());
        presenter.cloneSelected();
        assertTrue(presenter.state().message().contains("请先保存或放弃"));
        presenter.discardDraft();
        presenter.select(custom);
        assertFalse(presenter.state().standardReadOnly());
        presenter.updateDraft(
                copy(presenter.state().draft(), custom.id(), custom.resources().openFiles() + 1));
        presenter.save();
        assertEquals(2, presenter.state().selected().orElseThrow().version());
    }

    @Test
    void permission目录覆盖自定义回退空目录和读取写入失败() {
        TestCoreSettingsGateway customGateway = new TestCoreSettingsGateway();
        PermissionProfile custom = customGateway
                .clonePermissionProfile(
                        new PermissionProfileRef("standard", 1),
                        "custom-only",
                        com.javaclaw.client.CommandOptions.create(0))
                .toCompletableFuture()
                .join();
        customGateway.permissions.removeIf(profile -> profile.id().equals("standard"));
        PermissionProfileSettingsPresenter fallback = new PermissionProfileSettingsPresenter(customGateway);
        fallback.reload();
        assertEquals(custom, fallback.state().selected().orElseThrow());
        assertEquals("", fallback.state().message());

        TestCoreSettingsGateway emptyGateway = new TestCoreSettingsGateway();
        emptyGateway.permissions.clear();
        PermissionProfileSettingsPresenter empty = new PermissionProfileSettingsPresenter(emptyGateway);
        empty.reload();
        assertTrue(empty.state().selected().isEmpty());
        assertTrue(empty.state().message().contains("没有可用权限方案"));

        PermissionProfileSettingsPresenter readFailure = new PermissionProfileSettingsPresenter(failingGateway(
                new TestCoreSettingsGateway(), "permissionProfiles", new IllegalStateException("read down")));
        readFailure.reload();
        assertEquals(SettingsLoadState.ERROR, readFailure.state().phase());

        TestCoreSettingsGateway writeDelegate = new TestCoreSettingsGateway();
        PermissionProfileSettingsPresenter writeFailure = new PermissionProfileSettingsPresenter(
                failingGateway(writeDelegate, "clonePermissionProfile", new IllegalStateException("clone down")));
        writeFailure.reload();
        writeFailure.cloneSelected();
        writeFailure.updateDraft(copy(writeFailure.state().draft(), "clone-failure", 32));
        writeFailure.save();
        assertEquals(SettingsLoadState.ERROR, writeFailure.state().phase());
        assertTrue(writeFailure.state().message().contains("clone down"));
    }

    @Test
    void permission草稿精确报告各分区并规范化列表数值() {
        PermissionProfile standard = new TestCoreSettingsGateway().permissions.getFirst();
        PermissionProfileDraft baseline = PermissionProfileDraft.from(standard).cloneDraft();
        assertTrue(baseline.changedSections(baseline).isEmpty());
        assertEquals(
                List.of("身份"), copy(baseline, "custom", baseline.openFiles()).changedSections(baseline));
        assertEquals(List.of("文件"), withFileTail(baseline).changedSections(baseline));
        assertEquals(List.of("网络"), withNetworkTail(baseline).changedSections(baseline));
        assertEquals(List.of("进程/PTY"), withProcessTail(baseline).changedSections(baseline));
        assertEquals(List.of("工具/审批"), withToolTail(baseline).changedSections(baseline));
        assertEquals(
                List.of("资源"),
                copy(baseline, baseline.id(), baseline.openFiles() + 1).changedSections(baseline));
        assertThrows(NullPointerException.class, () -> baseline.changedSections(null));

        PermissionProfileDraft parsed = new PermissionProfileDraft(
                "parsed",
                "/tmp/a\n\n/tmp/a, /tmp/b",
                "",
                false,
                false,
                " api.example.test, api.example.test ",
                "443,\n8443",
                true,
                "java\njava",
                false,
                30,
                Set.of("core/tool/search"),
                ToolRisk.READ_ONLY,
                ApprovalRequirement.RISKY,
                256,
                16,
                1,
                32);
        PermissionProfile profile = parsed.toProfile(1);
        assertEquals(
                List.of(Path.of("/tmp/a"), Path.of("/tmp/b")), profile.files().readRoots());
        assertEquals(Set.of(443, 8443), profile.network().ports());
        assertEquals(Set.of("java"), profile.processes().executables());

        PermissionProfileDraft overflow = new PermissionProfileDraft(
                "overflow",
                "",
                "",
                false,
                false,
                "",
                "",
                true,
                "",
                false,
                30,
                Set.of(),
                ToolRisk.READ_ONLY,
                ApprovalRequirement.RISKY,
                Long.MAX_VALUE,
                1,
                1,
                1);
        assertThrows(ArithmeticException.class, () -> overflow.toProfile(1));
    }

    private static PermissionProfileDraft copy(PermissionProfileDraft source, String id, int openFiles) {
        return new PermissionProfileDraft(
                id,
                source.readRoots(),
                source.writeRoots(),
                source.allowDelete(),
                source.followSymbolicLinks(),
                source.networkHosts(),
                source.networkPorts(),
                source.tlsOnly(),
                source.executables(),
                source.allowPty(),
                source.processSeconds(),
                source.allowedTools(),
                source.maximumRisk(),
                source.approvalRequirement(),
                source.memoryMiB(),
                source.outputMiB(),
                source.childProcesses(),
                openFiles);
    }

    private static PermissionProfileDraft withFileTail(PermissionProfileDraft source) {
        return new PermissionProfileDraft(
                source.id(),
                source.readRoots(),
                source.writeRoots(),
                source.allowDelete(),
                !source.followSymbolicLinks(),
                source.networkHosts(),
                source.networkPorts(),
                source.tlsOnly(),
                source.executables(),
                source.allowPty(),
                source.processSeconds(),
                source.allowedTools(),
                source.maximumRisk(),
                source.approvalRequirement(),
                source.memoryMiB(),
                source.outputMiB(),
                source.childProcesses(),
                source.openFiles());
    }

    private static PermissionProfileDraft withNetworkTail(PermissionProfileDraft source) {
        return new PermissionProfileDraft(
                source.id(),
                source.readRoots(),
                source.writeRoots(),
                source.allowDelete(),
                source.followSymbolicLinks(),
                source.networkHosts(),
                source.networkPorts(),
                !source.tlsOnly(),
                source.executables(),
                source.allowPty(),
                source.processSeconds(),
                source.allowedTools(),
                source.maximumRisk(),
                source.approvalRequirement(),
                source.memoryMiB(),
                source.outputMiB(),
                source.childProcesses(),
                source.openFiles());
    }

    private static PermissionProfileDraft withProcessTail(PermissionProfileDraft source) {
        return new PermissionProfileDraft(
                source.id(),
                source.readRoots(),
                source.writeRoots(),
                source.allowDelete(),
                source.followSymbolicLinks(),
                source.networkHosts(),
                source.networkPorts(),
                source.tlsOnly(),
                source.executables(),
                source.allowPty(),
                source.processSeconds() + 1,
                source.allowedTools(),
                source.maximumRisk(),
                source.approvalRequirement(),
                source.memoryMiB(),
                source.outputMiB(),
                source.childProcesses(),
                source.openFiles());
    }

    private static PermissionProfileDraft withToolTail(PermissionProfileDraft source) {
        return new PermissionProfileDraft(
                source.id(),
                source.readRoots(),
                source.writeRoots(),
                source.allowDelete(),
                source.followSymbolicLinks(),
                source.networkHosts(),
                source.networkPorts(),
                source.tlsOnly(),
                source.executables(),
                source.allowPty(),
                source.processSeconds(),
                source.allowedTools(),
                source.maximumRisk(),
                ApprovalRequirement.EVERY_CALL,
                source.memoryMiB(),
                source.outputMiB(),
                source.childProcesses(),
                source.openFiles());
    }

    private static CoreSettingsGateway failingGateway(
            TestCoreSettingsGateway delegate, String methodName, RuntimeException failure) {
        return (CoreSettingsGateway) Proxy.newProxyInstance(
                CoreSettingsGateway.class.getClassLoader(),
                new Class<?>[] {CoreSettingsGateway.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals(methodName)) {
                        return java.util.concurrent.CompletableFuture.failedFuture(failure);
                    }
                    try {
                        return method.invoke(delegate, arguments);
                    } catch (InvocationTargetException invocation) {
                        throw invocation.getCause();
                    }
                });
    }
}
