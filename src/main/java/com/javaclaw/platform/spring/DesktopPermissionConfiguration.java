package com.javaclaw.platform.spring;

import com.javaclaw.desktop.api.DesktopSystemPermissionService;
import com.javaclaw.desktop.nativebridge.NativeDesktopSystemPermissionService;
import com.javaclaw.platform.data.ApplicationHome;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Desktop permission checks are available to settings without opening a session. */
@Configuration(proxyBeanMethods = false)
public class DesktopPermissionConfiguration {
    @Bean
    DesktopSystemPermissionService desktopSystemPermissionService(ApplicationHome home) {
        return new NativeDesktopSystemPermissionService(home);
    }
}
