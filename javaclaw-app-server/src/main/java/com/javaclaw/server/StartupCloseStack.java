package com.javaclaw.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** App Server 启动事务的资源栈；成功时移交所有权，失败时按逆序关闭。 */
final class StartupCloseStack implements AutoCloseable {
    private final List<AutoCloseable> resources = new ArrayList<>();

    <T extends AutoCloseable> T own(T resource) {
        resources.add(Objects.requireNonNull(resource, "resource"));
        return resource;
    }

    void release(AutoCloseable resource) {
        for (int index = resources.size() - 1; index >= 0; index--) {
            if (resources.get(index) == resource) {
                resources.remove(index);
                return;
            }
        }
    }

    void releaseAll() {
        resources.clear();
    }

    @Override
    public void close() {
        Exception failure = null;
        for (int index = resources.size() - 1; index >= 0; index--) {
            try {
                resources.get(index).close();
            } catch (Exception closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                } else {
                    failure.addSuppressed(closeFailure);
                }
            }
        }
        resources.clear();
        if (failure != null) {
            throw new IllegalStateException("App Server 启动失败后的资源清理也发生异常", failure);
        }
    }
}
