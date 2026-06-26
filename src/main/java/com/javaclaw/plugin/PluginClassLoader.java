package com.javaclaw.plugin;

import java.net.URL;
import java.net.URLClassLoader;

/**
 * 每插件独立的类加载器 —— 采用 <b>child-first（子优先）+ api 包 parent-first</b> 混合委派。
 *
 * <ul>
 *   <li><b>{@code com.javaclaw.plugin.api.*} 与 JDK 包 → 父优先</b>：保证 {@code JavaClawPlugin}/
 *       {@code PluginContext} 等契约类型在宿主与插件间是<b>同一个 Class</b>，否则插件返回的对象
 *       宿主 instanceof 不通过、强转 ClassCastException。</li>
 *   <li><b>其余（插件自身类 + 捆绑的三方 jar）→ 子优先</b>：插件可携带自己的依赖版本，
 *       与宿主及其他插件互不污染（依赖隔离）。</li>
 * </ul>
 *
 * @author JavaClaw
 */
public final class PluginClassLoader extends URLClassLoader {

    /** 必须父优先的包前缀（契约类型共享 + JDK 核心） */
    private static final String[] PARENT_FIRST_PREFIXES = {
            "com.javaclaw.plugin.api.",
            "java.", "javax.", "jdk.", "sun.", "com.sun."
    };

    static {
        registerAsParallelCapable();
    }

    /**
     * @param pluginId 插件 id（用于命名类加载器，便于诊断）
     * @param urls     插件 jar（及其捆绑依赖）的 URL
     * @param parent   宿主应用类加载器（提供 api 契约类型）
     */
    public PluginClassLoader(String pluginId, URL[] urls, ClassLoader parent) {
        super("plugin-" + pluginId, urls, parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c == null) {
                if (isParentFirst(name)) {
                    c = tryParent(name);
                    if (c == null) {
                        c = trySelf(name);
                    }
                } else {
                    c = trySelf(name);
                    if (c == null) {
                        c = tryParent(name);
                    }
                }
            }
            if (c == null) {
                throw new ClassNotFoundException(name);
            }
            if (resolve) {
                resolveClass(c);
            }
            return c;
        }
    }

    private boolean isParentFirst(String name) {
        for (String prefix : PARENT_FIRST_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** 从本加载器（插件 jar）加载，找不到返回 null。 */
    private Class<?> trySelf(String name) {
        try {
            return findClass(name);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    /** 委派父加载器（宿主），找不到返回 null。 */
    private Class<?> tryParent(String name) {
        ClassLoader parent = getParent();
        if (parent == null) {
            return null;
        }
        try {
            return parent.loadClass(name);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }
}
