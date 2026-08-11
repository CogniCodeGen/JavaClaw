package com.javaclaw.platform.spring;

import com.javaclaw.config.DatabaseAccess;
import com.javaclaw.config.CredentialCipher;
import com.javaclaw.platform.data.DataRoot;
import com.javaclaw.platform.data.SchemaInitializer;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.platform.http.HttpGateway;
import com.javaclaw.platform.json.JsonCodec;
import com.javaclaw.platform.process.ProcessRunner;
import com.javaclaw.platform.storage.AtomicContentStore;
import com.javaclaw.platform.dialog.DialogService;
import com.javaclaw.application.tool.ToolInvocationPipeline;
import com.javaclaw.application.chat.ToolReviewSettingsPort;
import com.javaclaw.application.diagnostics.DiagnosticsApplicationService;
import com.javaclaw.application.diagnostics.DiagnosticsArchivePort;
import com.javaclaw.application.plugin.PluginManagementApplicationService;
import com.javaclaw.application.plugin.PluginManagementPort;
import com.javaclaw.application.onboarding.OnboardingApplicationService;
import com.javaclaw.application.settings.TestDataMaintenanceApplicationService;
import com.javaclaw.application.settings.TestDataMaintenancePort;
import com.javaclaw.plugin.PluginManager;
import com.javaclaw.ui.javafx.diagnostics.DiagnosticsViewFactory;
import com.javaclaw.ui.javafx.plugin.PluginCenterViewFactory;
import com.javaclaw.ui.javafx.onboarding.OnboardingViewFactory;
import com.javaclaw.ui.javafx.onboarding.ProviderCardFactory;
import com.javaclaw.ui.javafx.image.ImageViewerFactory;
import com.javaclaw.ui.javafx.interaction.InteractionDialogFactory;
import com.javaclaw.ui.javafx.control.WindowToastFactory;
import com.javaclaw.ui.javafx.theme.FontSelectionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RootApplicationContextTest {

    @TempDir
    Path tempDirectory;

    @Test
    void rootContextProvidesDataExecutionFxAndFxmlInfrastructure() {
        DataRoot dataRoot = new DataRoot(tempDirectory.resolve("root"));

        try (var context = ApplicationContexts.createRoot(dataRoot)) {
            assertSame(dataRoot, context.getBean(DataRoot.class));
            assertTrue(context.getBean(SchemaInitializer.class).isInitialized());
            assertNotNull(context.getBean(JdbcTemplate.class));
            assertNotNull(context.getBean(DatabaseAccess.class));
            assertNotNull(context.getBean(CredentialCipher.class));
            assertNotNull(context.getBean(ManagedTaskExecutor.class));
            assertNotNull(context.getBean(FxDispatcher.class));
            assertNotNull(context.getBean(SpringFxmlLoader.class));
            assertNotNull(context.getBean(ProcessRunner.class));
            assertNotNull(context.getBean(HttpGateway.class));
            assertNotNull(context.getBean(JsonCodec.class));
            assertNotNull(context.getBean(AtomicContentStore.class));
            assertNotNull(context.getBean(DialogService.class));
            assertNotNull(context.getBean(ToolInvocationPipeline.class));
            assertNotNull(context.getBean(ToolReviewSettingsPort.class));
            assertNotNull(context.getBean(DiagnosticsArchivePort.class));
            assertNotNull(context.getBean(DiagnosticsApplicationService.class));
            assertNotNull(context.getBean(TestDataMaintenancePort.class));
            assertNotNull(context.getBean(TestDataMaintenanceApplicationService.class));
            PluginManager plugins = context.getBean(PluginManager.class);
            assertSame(plugins, context.getBean(PluginManager.class));
            assertNotNull(context.getBean(PluginManagementPort.class));
            assertNotNull(context.getBean(PluginManagementApplicationService.class));
            assertNotNull(context.getBean(DiagnosticsViewFactory.class));
            assertNotNull(context.getBean(PluginCenterViewFactory.class));
            assertNotNull(context.getBean(OnboardingApplicationService.class));
            assertNotNull(context.getBean(OnboardingViewFactory.class));
            assertNotNull(context.getBean(ProviderCardFactory.class));
            assertNotNull(context.getBean(ImageViewerFactory.class));
            assertNotNull(context.getBean(InteractionDialogFactory.class));
            assertNotNull(context.getBean(WindowToastFactory.class));
            assertNotNull(context.getBean(FontSelectionService.class));
            assertNotNull(context.getBean(WorkspaceSpringContextFactory.class));

            SchemaInitializer initializer = context.getBean(SchemaInitializer.class);
            initializer.initialize();
            assertTrue(initializer.isInitialized());
        }
    }

    @Test
    void jdbcTransactionRollsBackAgainstInitializedSchema() {
        try (var context = ApplicationContexts.createRoot(
                new DataRoot(tempDirectory.resolve("transaction")))) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            TransactionTemplate transaction = new TransactionTemplate(
                    context.getBean(org.springframework.transaction.PlatformTransactionManager.class));

            transaction.executeWithoutResult(status -> {
                jdbc.update("INSERT INTO app_state(state_key, state_value) VALUES (?, ?)",
                        "rollback", "value");
                status.setRollbackOnly();
            });

            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM app_state WHERE state_key = ?", Integer.class, "rollback");
            assertEquals(0, count);
        }
    }

    @Test
    void rootContextsOwnIndependentPluginHosts() {
        try (var first = ApplicationContexts.createRoot(
                     new DataRoot(tempDirectory.resolve("plugins-first")));
             var second = ApplicationContexts.createRoot(
                     new DataRoot(tempDirectory.resolve("plugins-second")))) {
            assertNotSame(first.getBean(PluginManager.class), second.getBean(PluginManager.class),
                    "插件宿主必须由各自根 Context 管理，不能退回静态单例");
            assertNotSame(first.getBean(CredentialCipher.class), second.getBean(CredentialCipher.class),
                    "凭据密钥缓存必须属于根 Context，不能跨数据目录共享静态状态");
        }
    }
}
