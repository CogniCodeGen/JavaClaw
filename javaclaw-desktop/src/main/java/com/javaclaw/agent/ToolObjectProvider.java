package com.javaclaw.agent;

import java.util.List;

/** Exposes a logical tool set as independently scannable {@code @Tool} components. */
public interface ToolObjectProvider extends com.javaclaw.framework.spi.ToolObjectSource {

    @Override
    List<Object> toolObjects();
}
