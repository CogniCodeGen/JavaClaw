package com.javaclaw.platform.fxml;

import javafx.fxml.FXMLLoader;

import java.io.IOException;
import java.net.URL;
import java.util.Objects;

/**
 * 加载由既有对象充当 Controller 的轻量 FXML 片段。
 *
 * <p>用于自定义控件的内部静态结构：每个控件实例在构造时加载一次，之后只更新状态。
 * Controller 不由 Spring 管理，也不得持有应用服务；需要依赖注入和显式销毁的页面仍须使用
 * {@link SpringFxmlLoader}。本方法必须在创建 JavaFX Node 合法的线程调用。</p>
 */
public final class EmbeddedFxmlLoader {

    private EmbeddedFxmlLoader() {}

    public static <T> T load(URL resource, Object controller, Class<T> rootType) {
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(controller, "controller");
        Objects.requireNonNull(rootType, "rootType");
        FXMLLoader loader = new FXMLLoader(resource);
        loader.setController(controller);
        try {
            return rootType.cast(loader.load());
        } catch (IOException | RuntimeException failure) {
            throw new IllegalStateException("加载嵌入式 FXML 失败: " + resource, failure);
        }
    }
}
