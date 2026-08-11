package com.javaclaw.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.Executor;

import static com.javaclaw.config.AgentConfigSchema.CONFIG_NAMESPACE;

/**
 * Persists one workspace's agent settings as an atomic property snapshot.
 *
 * <p>The workspace identifier is captured by {@link SqlPropertyStore} at each operation boundary.
 * Asynchronous single-key writes capture it before dispatch so a workspace switch cannot redirect
 * an already accepted write.</p>
 */
final class AgentConfigPersistence {

    private static final Logger log = LoggerFactory.getLogger(AgentConfigPersistence.class);

    private final SqlPropertyStore store;
    private final String databaseDescription;

    AgentConfigPersistence(SqlPropertyStore store, DatabaseAccess database) {
        this.store = Objects.requireNonNull(store, "store");
        this.databaseDescription = Objects.requireNonNull(database, "database").description();
    }

    void loadInto(Properties target) {
        target.clear();
        target.putAll(store.load(CONFIG_NAMESPACE));
        boolean removedObsoletePlanConfig = removeObsoletePlanModeProperties(target);
        if (target.isEmpty()) {
            log.info("智能体配置数据库为空，使用默认值: {}", databaseDescription);
            AgentConfigSchema.applyInitialDefaults(target);
        } else {
            log.info("智能体配置已从 H2 加载: {}", databaseDescription);
        }
        if (removedObsoletePlanConfig && !store.save(CONFIG_NAMESPACE, target)) {
            log.warn("已忽略废弃的规划模式配置，但未能从 H2 中清理");
        }
    }

    void save(Properties properties) {
        if (store.save(CONFIG_NAMESPACE, properties)) {
            log.info("智能体配置已保存到 H2: {}", databaseDescription);
        }
    }

    void savePropertyAsync(Executor executor, String key, String value) {
        String workspaceId = store.currentWorkspaceId();
        executor.execute(() -> {
            if (store.saveProperty(CONFIG_NAMESPACE, key, value, workspaceId)) {
                log.info("智能体配置项已异步保存到 H2: workspace={}, key={}", workspaceId, key);
            }
        });
    }

    String description() {
        return databaseDescription;
    }

    static boolean removeObsoletePlanModeProperties(Properties target) {
        boolean removedRounds = target.remove("plan.mode.max.rounds") != null;
        boolean removedExperts = target.remove("plan.mode.max.experts") != null;
        return removedRounds || removedExperts;
    }
}
