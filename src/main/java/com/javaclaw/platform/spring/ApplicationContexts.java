package com.javaclaw.platform.spring;

import com.javaclaw.browser.PlaywrightBrowserManager;
import com.javaclaw.config.DataManager;
import com.javaclaw.config.WorkspaceManager;
import com.javaclaw.platform.data.DataRoot;
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
}
