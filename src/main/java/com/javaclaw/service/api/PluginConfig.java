package com.javaclaw.service.api;

import java.util.Map;

public interface PluginConfig {
    String get(String key);
    Map<String, String> asMap();
}
