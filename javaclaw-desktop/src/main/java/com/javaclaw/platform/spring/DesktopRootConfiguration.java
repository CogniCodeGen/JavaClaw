package com.javaclaw.platform.spring;

import com.javaclaw.config.AgentConfig;
import com.javaclaw.desktop.agent.ConfiguredDesktopAccess;
import com.javaclaw.desktop.api.DesktopConsentPort;
import com.javaclaw.desktop.api.DesktopSessionService;
import com.javaclaw.desktop.api.DesktopSystemPermissionService;
import com.javaclaw.desktop.nativebridge.NativeDesktopProvider;
import com.javaclaw.desktop.service.DefaultDesktopSessionService;
import com.javaclaw.desktop.spi.DesktopPlatformProvider;
import com.javaclaw.platform.data.ApplicationHome;
import com.javaclaw.ui.javafx.desktop.DesktopPreviewWindow;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.List;

/** Process-scoped desktop providers and session service. */
@Configuration(proxyBeanMethods = false)
class DesktopRootConfiguration {

    @Bean
    DesktopPlatformProvider macDesktopProvider(ApplicationHome home) {
        return NativeDesktopProvider.macos(home);
    }

    @Bean
    DesktopPlatformProvider windowsDesktopProvider(ApplicationHome home) {
        return NativeDesktopProvider.windows(home);
    }

    @Bean
    DesktopConsentPort desktopConsentPort(AgentConfig settings,
                                          DesktopSystemPermissionService permissions) {
        return new ConfiguredDesktopAccess(settings, permissions);
    }

    @Bean(destroyMethod = "close")
    DesktopSessionService desktopSessionService(List<DesktopPlatformProvider> providers,
                                                DesktopConsentPort consent,
                                                DesktopPreviewWindow preview,
                                                AgentConfig settings) {
        return new DefaultDesktopSessionService(providers, consent, Clock.systemUTC(), preview,
                settings::getDesktopInputPolicy);
    }
}
