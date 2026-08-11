package com.javaclaw.platform.fxml;

import javafx.scene.Node;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一次 FXML 加载结果及其 Controller 生命周期。
 *
 * <p>关闭会按创建顺序的反序先调用 {@link AutoCloseable#close()}，再交给 Spring 执行
 * 销毁回调。关闭幂等；所有 Controller 都会尝试释放，首个失败在最后抛出并附带其余失败。</p>
 */
public final class ViewHandle<N extends Node> implements AutoCloseable {

    private final N root;
    private final Object primaryController;
    private final List<Object> controllers;
    private final AutowireCapableBeanFactory beanFactory;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    ViewHandle(N root, Object primaryController, List<Object> controllers,
               AutowireCapableBeanFactory beanFactory) {
        this.root = Objects.requireNonNull(root, "root");
        this.primaryController = primaryController;
        this.controllers = List.copyOf(controllers);
        this.beanFactory = Objects.requireNonNull(beanFactory, "beanFactory");
    }

    public N root() {
        return root;
    }

    public Object primaryController() {
        return primaryController;
    }

    public List<Object> controllers() {
        return controllers;
    }

    public <C> C controller(Class<C> type) {
        Objects.requireNonNull(type, "type");
        if (type.isInstance(primaryController)) {
            return type.cast(primaryController);
        }
        return controllers.stream()
                .filter(type::isInstance)
                .map(type::cast)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "本次 FXML 未创建 Controller: " + type.getName()));
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        Throwable failure = destroyControllers(controllers, beanFactory, null);
        if (failure != null) {
            throw new IllegalStateException("销毁 FXML Controller 失败", failure);
        }
    }

    static Throwable destroyControllers(List<Object> created,
                                        AutowireCapableBeanFactory beanFactory,
                                        Throwable originalFailure) {
        List<Object> reverse = new ArrayList<>(created);
        Collections.reverse(reverse);
        Set<Object> unique = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable failure = originalFailure;
        for (Object controller : reverse) {
            if (!unique.add(controller)) {
                continue;
            }
            if (controller instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Throwable closeFailure) {
                    failure = append(failure, closeFailure);
                }
            }
            try {
                beanFactory.destroyBean(controller);
            } catch (Throwable destroyFailure) {
                failure = append(failure, destroyFailure);
            }
        }
        return failure;
    }

    private static Throwable append(Throwable current, Throwable addition) {
        if (current == null) {
            return addition;
        }
        if (current != addition) {
            current.addSuppressed(addition);
        }
        return current;
    }
}
