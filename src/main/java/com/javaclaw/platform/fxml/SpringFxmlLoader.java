package com.javaclaw.platform.fxml;

import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 由 Spring 构造 Controller、由 FXMLLoader 注入 {@code @FXML} 成员的页面加载器。
 *
 * <p>每次加载都会记录主 FXML 及 {@code fx:include} 创建的全部 Controller。加载失败时
 * 立即反序销毁已创建对象；成功时销毁责任转交 {@link ViewHandle}。本类无页面状态，
 * 可由多个 FX 页面顺序复用，但单次 {@link FXMLLoader} 仍只在调用线程内使用。</p>
 */
public final class SpringFxmlLoader {

    private final AutowireCapableBeanFactory beanFactory;

    public SpringFxmlLoader(AutowireCapableBeanFactory beanFactory) {
        this.beanFactory = Objects.requireNonNull(beanFactory, "beanFactory");
    }

    public <N extends Node> ViewHandle<N> load(URL resource) throws IOException {
        Objects.requireNonNull(resource, "resource");
        List<Object> controllers = new ArrayList<>();
        FXMLLoader loader = new FXMLLoader(resource);
        loader.setControllerFactory(type -> {
            Object controller = beanFactory.createBean(type);
            controllers.add(controller);
            return controller;
        });
        try {
            N root = loader.load();
            return new ViewHandle<>(root, loader.getController(), controllers, beanFactory);
        } catch (IOException | RuntimeException | Error failure) {
            ViewHandle.destroyControllers(controllers, beanFactory, failure);
            throw failure;
        }
    }
}
