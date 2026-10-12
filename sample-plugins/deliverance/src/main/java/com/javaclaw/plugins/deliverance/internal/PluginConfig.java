package com.javaclaw.plugins.deliverance.internal;

import java.util.Map;

public interface PluginConfig {
    String get(String key);
    Map<String, String> asMap();
}
