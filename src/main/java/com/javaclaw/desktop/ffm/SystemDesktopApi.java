package com.javaclaw.desktop.ffm;

import com.javaclaw.desktop.api.DesktopApplicationCatalog;
import com.javaclaw.desktop.api.DesktopApplicationLaunch;
import com.javaclaw.desktop.api.DesktopAvailability;
import com.javaclaw.desktop.api.DesktopInputPolicy;
import com.javaclaw.desktop.nativebridge.DesktopBridge.NativeWindow;
import java.util.List;
import java.util.Optional;

/** Java desktop implementation whose only native boundary is the operating system's public API. */
public interface SystemDesktopApi {
    DesktopAvailability availability(boolean request, DesktopInputPolicy policy);
    List<NativeWindow> windows();
    DesktopApplicationCatalog applications();
    DesktopApplicationLaunch launch(String application);
    Optional<Boolean> windowExists(long processId, long windowId, long processInstanceId);
    SystemDesktopSession open(NativeWindow window);
}
