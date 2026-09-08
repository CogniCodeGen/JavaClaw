package com.javaclaw.desktop.settings;

import java.util.Objects;

/** 页面失效事件的去重维度；同资源不同操作保留独立 revision 水位。 */
record ViewEventKey(String scope, String resourceId, String operation) {
    ViewEventKey {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(resourceId, "resourceId");
        Objects.requireNonNull(operation, "operation");
    }
}
