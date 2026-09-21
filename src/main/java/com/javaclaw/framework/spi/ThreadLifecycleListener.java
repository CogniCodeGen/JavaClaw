package com.javaclaw.framework.spi;

import com.javaclaw.framework.api.*;
import java.util.List;

/** Workspace resource projections participate without coupling the kernel to products. */
public interface ThreadLifecycleListener {
    boolean accepts(RunScope scope);
    default void forked(ThreadSnapshot target, List<ThreadEvent> sourceHistory) { }
    default void deleting(RunScope scope) { }
}
