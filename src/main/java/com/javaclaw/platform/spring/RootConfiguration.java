package com.javaclaw.platform.spring;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.application.event.DomainEventPublisher;
import com.javaclaw.application.tool.ToolAuthorization;
import com.javaclaw.application.tool.ToolAuthorizer;
import com.javaclaw.application.tool.ToolAuditSink;
import com.javaclaw.application.tool.ToolInvocationPipeline;
import com.javaclaw.config.DatabaseAccess;
import com.javaclaw.platform.data.DataRoot;
import com.javaclaw.platform.data.DataSourceDatabaseAccess;
import com.javaclaw.platform.data.H2DataSource;
import com.javaclaw.platform.data.SchemaInitializer;
import com.javaclaw.platform.dialog.DialogService;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.platform.http.HttpGateway;
import com.javaclaw.platform.json.JsonCodec;
import com.javaclaw.platform.process.ProcessRunner;
import com.javaclaw.platform.storage.AtomicContentStore;
import com.javaclaw.api.interaction.UserInteractionPort;
import com.javaclaw.ui.javafx.JfxUserInteractionPort;
import com.javaclaw.infrastructure.tool.LoggingToolAuditSink;
import com.javaclaw.agent.ToolConfirmationManager;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.net.http.HttpClient;
import java.time.Duration;

/** 进程级基础设施的显式 Spring 装配。 */
@Configuration(proxyBeanMethods = false)
public class RootConfiguration {

    @Bean
    DataSource dataSource(DataRoot dataRoot) {
        return new H2DataSource(dataRoot);
    }

    @Bean
    SchemaInitializer schemaInitializer(DataSource dataSource) {
        SchemaInitializer initializer = new SchemaInitializer(dataSource);
        initializer.initialize();
        return initializer;
    }

    @Bean
    JdbcTemplate jdbcTemplate(DataSource dataSource, SchemaInitializer schemaInitializer) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    PlatformTransactionManager transactionManager(
            DataSource dataSource, SchemaInitializer schemaInitializer) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    DatabaseAccess databaseAccess(DataSource dataSource, SchemaInitializer schemaInitializer) {
        String description = dataSource instanceof H2DataSource h2
                ? h2.databaseFile().toString() : dataSource.toString();
        return new DataSourceDatabaseAccess(dataSource, description);
    }

    @Bean(destroyMethod = "close")
    ManagedTaskExecutor managedTaskExecutor() {
        return new ManagedTaskExecutor();
    }

    @Bean
    ProcessRunner processRunner(ManagedTaskExecutor executor) {
        return new ProcessRunner(executor);
    }

    @Bean
    ObjectMapper objectMapper() {
        return new ObjectMapper()
                .findAndRegisterModules()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    @Bean
    JsonCodec jsonCodec(ObjectMapper mapper) {
        return new JsonCodec(mapper);
    }

    @Bean
    HttpClient httpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Bean
    HttpGateway httpGateway(ManagedTaskExecutor executor, HttpClient client) {
        return new HttpGateway(executor, client);
    }

    @Bean
    AtomicContentStore atomicContentStore() {
        return new AtomicContentStore();
    }

    @Bean
    DomainEventPublisher domainEventPublisher(ApplicationEventPublisher publisher) {
        return publisher::publishEvent;
    }

    @Bean
    ToolAuthorizer toolAuthorizer() {
        return invocation -> {
            if (!ToolConfirmationManager.requiresConfirmation(invocation.toolName())) {
                return ToolAuthorization.allow();
            }
            return ToolConfirmationManager.requestConfirmation(
                    invocation.origin(), invocation.toolName(), invocation.description())
                    ? ToolAuthorization.allow()
                    : ToolAuthorization.reject("用户或安全策略未授权该操作");
        };
    }

    @Bean
    ToolAuditSink toolAuditSink() {
        return new LoggingToolAuditSink();
    }

    @Bean
    ToolInvocationPipeline toolInvocationPipeline(
            ToolAuthorizer authorizer, ToolAuditSink auditSink) {
        return new ToolInvocationPipeline(authorizer, auditSink);
    }

    @Bean
    FxDispatcher fxDispatcher() {
        return new FxDispatcher();
    }

    @Bean
    JfxUserInteractionPort jfxUserInteractionPort(FxDispatcher fxDispatcher) {
        return new JfxUserInteractionPort(fxDispatcher);
    }

    @Bean
    DialogService dialogService(UserInteractionPort interactionPort) {
        return new DialogService(interactionPort);
    }

    @Bean
    SpringFxmlLoader springFxmlLoader(AutowireCapableBeanFactory beanFactory) {
        return new SpringFxmlLoader(beanFactory);
    }

    @Bean
    WorkspaceSpringContextFactory workspaceSpringContextFactory(ApplicationContext rootContext) {
        return new WorkspaceSpringContextFactory(rootContext);
    }
}
