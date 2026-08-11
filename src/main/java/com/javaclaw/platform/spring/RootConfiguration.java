package com.javaclaw.platform.spring;

import com.javaclaw.config.DatabaseAccess;
import com.javaclaw.platform.data.DataRoot;
import com.javaclaw.platform.data.DataSourceDatabaseAccess;
import com.javaclaw.platform.data.H2DataSource;
import com.javaclaw.platform.data.SchemaInitializer;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fx.FxDispatcher;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

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
    FxDispatcher fxDispatcher() {
        return new FxDispatcher();
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
