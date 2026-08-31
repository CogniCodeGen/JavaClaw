package com.javaclaw.server.persistence;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Objects;

import com.javaclaw.agent.runtime.persistence.RuntimePersistence;

/** Owns the single H2 connection factory and exposes only narrow repository adapters. */
public final class H2Persistence implements AutoCloseable {
    private final H2PersistenceEngine engine;
    private final H2WorkspaceRepository workspaces;
    private final H2ThreadJournal journal;
    private final H2EventOutbox outbox;
    private final H2InteractionRepository interactions;
    private final H2AttachmentRepository attachments;
    private final H2ServerConfigurationRepository configuration;

    /** 使用 UTC 时钟打开受格式标记保护的 v4 数据根；非空旧格式目录拒绝启动，不迁移或删除。 */
    public H2Persistence(Path dataRoot) {
        this(dataRoot, Clock.systemUTC());
    }

    /** 使用指定时钟打开 v4 数据根并装配窄仓库；close 统一释放共享持久引擎。 */
    public H2Persistence(Path dataRoot, Clock clock) {
        this(new H2PersistenceEngine(dataRoot, clock), clock);
    }

    private H2Persistence(H2PersistenceEngine engine, Clock clock) {
        this.engine = Objects.requireNonNull(engine, "engine");
        workspaces = new H2WorkspaceRepository(engine.database(), clock);
        journal = new H2ThreadJournal(engine);
        outbox = new H2EventOutbox(engine.database(), clock);
        interactions = new H2InteractionRepository(engine.database(), clock);
        attachments = new H2AttachmentRepository(engine);
        configuration = new H2ServerConfigurationRepository(engine.database(), clock);
    }

    /** 返回共享数据库的工作区仓库，不新建写连接持有者。 */
    public H2WorkspaceRepository workspaces() {
        return workspaces;
    }

    /** 返回 Thread/Turn/Item 原子日志适配器，事件与 Outbox 和投影共同提交。 */
    public H2ThreadJournal journal() {
        return journal;
    }

    /** 返回未发布事件读取/确认适配器，不将其当作第二套事件存储。 */
    public H2EventOutbox outbox() {
        return outbox;
    }

    /** 返回审批与用户输入决议仓库。 */
    public H2InteractionRepository interactions() {
        return interactions;
    }

    /** 返回受控内容寻址附件仓库，文件协调与数据库状态由同一持久引擎管理。 */
    public H2AttachmentRepository attachments() {
        return attachments;
    }

    /** 返回非敏感服务配置仓库，不存放明文凭据。 */
    public H2ServerConfigurationRepository configuration() {
        return configuration;
    }

    /** 组合 Runtime 所需的窄持久端口；新建聚合对象不会打开第二套数据库。 */
    public RuntimePersistence runtime() {
        return new RuntimePersistence(workspaces, journal, outbox, interactions);
    }

    /** 返回唯一共享 H2Database，仅用于服务端进程级装配。 */
    public H2Database database() {
        return engine.database();
    }

    /** 在 H2 外的 owner-only 平台配置目录建立主密钥边界；无法保证独占权限时拒绝持久凭据。 */
    public H2SecretStore secretStore(Path platformConfigurationDirectory) {
        return engine.secretStore(platformConfigurationDirectory);
    }

    /** 返回已经格式验证的 v4 数据根，不代表允许 Agent 访问该目录。 */
    public Path dataRoot() {
        return engine.dataRoot();
    }

    @Override
    public void close() {
        engine.close();
    }
}
