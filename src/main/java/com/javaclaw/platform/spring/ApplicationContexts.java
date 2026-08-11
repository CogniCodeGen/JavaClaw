package com.javaclaw.platform.spring;

import com.javaclaw.browser.PlaywrightBrowserManager;
import com.javaclaw.config.DataManager;
import com.javaclaw.config.WorkspaceManager;
import com.javaclaw.platform.data.DataRoot;
import com.javaclaw.runtime.ApplicationKernel;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;

/** 创建 JavaClaw 显式根 Context 的唯一入口。 */
public final class ApplicationContexts {

    private ApplicationContexts() {
    }

    public static AnnotationConfigApplicationContext createRoot(
            DataRoot dataRoot, Class<?>... additionalConfigurations) {
        Objects.requireNonNull(dataRoot, "dataRoot");
        try {
            dataRoot.prepare();
        } catch (IOException failure) {
            throw new UncheckedIOException("准备 JavaClaw 3 数据目录失败", failure);
        }

        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.setDisplayName("JavaClaw root");
        context.registerBean(DataRoot.class, () -> dataRoot);
        context.register(RootConfiguration.class);
        if (additionalConfigurations != null && additionalConfigurations.length > 0) {
            context.register(additionalConfigurations);
        }
        try {
            context.refresh();
            return context;
        } catch (RuntimeException | Error failure) {
            context.close();
            throw failure;
        }
    }

    /**
     * 在工作区路径初始化后登记桌面专用根 Bean。GenericApplicationContext 支持刷新后
     * 注册 Bean definition；对象仍由同一个根 Context 创建和销毁。
     */
    public static void registerDesktopInfrastructure(AnnotationConfigApplicationContext context) {
        Objects.requireNonNull(context, "context");
        context.registerBean(PlaywrightBrowserManager.class,
                () -> new PlaywrightBrowserManager(true,
                        WorkspaceManager.getInstance().getCurrentBrowserDir(),
                        DataManager.getInstance().getScreenshotsDir()),
                definition -> definition.setDestroyMethodName("shutdown"));
    }

    /**
     * 将依赖主窗口回调、因而只能在 JavaFX {@code start} 阶段创建的组合根登记到 Spring。
     * 登记完成后 FXML Controller 可以使用构造注入取得同一实例；实例随根 Context 关闭。
     */
    public static void registerApplicationKernel(
            AnnotationConfigApplicationContext context, ApplicationKernel kernel) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(kernel, "kernel");
        if (!context.isActive()) {
            throw new IllegalStateException("Spring 根 Context 尚未启动");
        }
        if (context.getBeanFactory().getBeanNamesForType(ApplicationKernel.class).length > 0) {
            throw new IllegalStateException("ApplicationKernel 已登记");
        }
        context.registerBean(ApplicationKernel.class, () -> kernel);
    }
}
