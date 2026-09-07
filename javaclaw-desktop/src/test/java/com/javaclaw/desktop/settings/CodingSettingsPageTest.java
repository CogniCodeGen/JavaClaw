package com.javaclaw.desktop.settings;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.desktop.DesktopTestFixtures;
import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.protocol.InputJobRpcContracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodingSettingsPageTest {
    @Test
    void 环境草稿保存绑定原Workspace和revision且刷新不覆盖草稿() {
        Gateway gateway = new Gateway();
        FxTestSupport.run(() -> {
            CodingSettingsPage page = new CodingSettingsPage(gateway);
            VBox root = mount(page);
            page.workspaceChanged(Optional.of(DesktopTestFixtures.workspace()));
            page.activate();
            TextField name = (TextField) root.lookup("#codingEnvironmentName");
            assertEquals("dev", name.getText());
            name.setText("review");
            assertTrue(page.dirty());
            page.activate();
            assertEquals("review", name.getText());
            ((Button) root.lookup("#codingSave")).fire();
            assertEquals(DesktopTestFixtures.workspace().id(), gateway.savedWorkspace);
            assertEquals(3, gateway.savedOptions.expectedRevision());
            assertEquals("review", gateway.spec.name());
            assertFalse(page.dirty());
            page.dispose();
        });
    }

    @Test
    void 工具链安装仅提交目录引用且页面离开会停止观察() {
        Gateway gateway = new Gateway();
        FxTestSupport.run(() -> {
            CodingSettingsPage page = new CodingSettingsPage(gateway);
            VBox root = mount(page);
            page.workspaceChanged(Optional.of(DesktopTestFixtures.workspace()));
            ComboBox<?> selector = (ComboBox<?>) root.lookup("#codingInstallArtifact");
            selector.getSelectionModel().selectFirst();
            ((Button) root.lookup("#codingInstall")).fire();
            assertEquals(gateway.reference, gateway.installedReference);
            assertEquals(0, gateway.installOptions.expectedRevision());
            // 未激活的页面不得后台轮询；任务所有权留在服务端，离页不取消下载。
            assertEquals(0, gateway.jobReads);
            page.dispose();
        });
    }

    @Test
    void 异步旧Workspace读取不能覆盖新作用域() {
        Gateway gateway = new Gateway();
        gateway.pendingLoad = new CompletableFuture<>();
        FxTestSupport.run(() -> {
            CodingSettingsPage page = new CodingSettingsPage(gateway);
            VBox root = mount(page);
            page.workspaceChanged(Optional.of(DesktopTestFixtures.workspace()));
            page.workspaceChanged(Optional.empty());
            gateway.pendingLoad.complete(gateway.snapshot());
            assertEquals("", ((TextField) root.lookup("#codingEnvironmentName")).getText());
            assertTrue(((Button) root.lookup("#codingSave")).isDisabled());
            page.dispose();
        });
    }

    private static VBox mount(CodingSettingsPage page) {
        VBox root = new VBox(page.content());
        page.actionContent().ifPresent(root.getChildren()::add);
        new Scene(root, 1000, 720);
        root.applyCss();
        root.layout();
        return root;
    }

    private static final class Gateway implements CodingSettingsGateway {
        private final CodingEnvironmentContracts.ToolchainRef reference = new CodingEnvironmentContracts.ToolchainRef(
                CodingEnvironmentContracts.ToolchainKind.JDK, "25", "a".repeat(64));
        private CodingEnvironmentContracts.EnvironmentSpec spec = new CodingEnvironmentContracts.EnvironmentSpec(
                "dev", List.of(reference), Set.of("repo.maven.apache.org"), true);
        private WorkspaceId savedWorkspace;
        private CommandOptions savedOptions;
        private CommandOptions installOptions;
        private CodingEnvironmentContracts.ToolchainRef installedReference;
        private CompletableFuture<Snapshot> pendingLoad;
        private int jobReads;

        private Snapshot snapshot() {
            return new Snapshot(
                    new CodingEnvironmentContracts.Environment(3, spec),
                    new CodingEnvironmentContracts.Catalog(List.of(new CodingEnvironmentContracts.ToolchainArtifact(
                            reference,
                            "macos",
                            "aarch64",
                            URI.create("https://example.com/jdk.zip"),
                            "zip",
                            Map.of("java", "bin/java"),
                            100,
                            "GPL-2.0"))),
                    new CodingEnvironmentContracts.InstalledList(List.of()));
        }

        @Override
        public CompletionStage<com.javaclaw.builtin.contracts.CodingResults.ExecutionList> executions(WorkspaceId id) {
            return CompletableFuture.completedFuture(
                    new com.javaclaw.builtin.contracts.CodingResults.ExecutionList(List.of()));
        }

        @Override
        public CompletionStage<Snapshot> load(WorkspaceId workspaceId) {
            return pendingLoad == null ? CompletableFuture.completedFuture(snapshot()) : pendingLoad;
        }

        @Override
        public CompletionStage<CodingEnvironmentContracts.Environment> save(
                WorkspaceId workspaceId, CodingEnvironmentContracts.EnvironmentSpec value, CommandOptions options) {
            savedWorkspace = workspaceId;
            savedOptions = options;
            spec = value;
            return CompletableFuture.completedFuture(new CodingEnvironmentContracts.Environment(4, value));
        }

        @Override
        public CompletionStage<CodingEnvironmentContracts.InstallAccepted> install(
                WorkspaceId workspaceId, CodingEnvironmentContracts.ToolchainRef value, CommandOptions options) {
            installedReference = value;
            installOptions = options;
            return CompletableFuture.completedFuture(
                    new CodingEnvironmentContracts.InstallAccepted("job-1", value.artifactSha256()));
        }

        @Override
        public CompletionStage<InputJobRpcContracts.JobReadResult> job(String jobId) {
            jobReads++;
            return CompletableFuture.failedFuture(new IllegalStateException("测试不运行工具链 Job"));
        }

        @Override
        public CompletionStage<Void> cancel(String jobId, CommandOptions options) {
            return CompletableFuture.completedFuture(null);
        }
    }
}
