package com.javaclaw.framework.spi;

import java.util.List;

/** A logical tool facade that exposes independently annotated Spring AI tool objects. */
public interface ToolObjectSource {
    List<Object> toolObjects();
}
