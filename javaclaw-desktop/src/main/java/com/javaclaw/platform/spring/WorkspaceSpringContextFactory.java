package com.javaclaw.platform.spring;

import com.javaclaw.runtime.WorkspaceContext;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.Objects;

/** 创建彼此隔离、以根 Context 为父级的工作区 Spring Context。 */
public final class WorkspaceSpringContextFactory {

    private final ApplicationContext rootContext;

    public WorkspaceSpringContextFactory(ApplicationContext rootContext) {
        this.rootContext = Objects.requireNonNull(rootContext, "rootContext");
    }

    public WorkspaceContextHandle create(WorkspaceContext workspace,
                                         WorkspaceRuntimeOptions options) {
        return create(workspace, options, WorkspaceSpringConfiguration.class);
    }

    /** 可插拔子 Context 配置入口，供无头宿主与生命周期测试复用同一创建协议。 */
    public WorkspaceContextHandle create(WorkspaceContext workspace,
                                         WorkspaceRuntimeOptions options,
                                         Class<?> configuration) {
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(configuration, "configuration");
        AnnotationConfigApplicationContext child = new AnnotationConfigApplicationContext();
        child.setDisplayName("JavaClaw workspace " + workspace.workspaceId());
        child.setParent(rootContext);
        child.registerBean(WorkspaceContext.class, () -> workspace);
        child.registerBean(WorkspaceRuntimeOptions.class, () -> options);
        child.register(configuration);
        try {
            child.refresh();
            return new WorkspaceContextHandle(child);
        } catch (RuntimeException | Error failure) {
            child.close();
            throw failure;
        }
    }
}
