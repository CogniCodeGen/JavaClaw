package com.javaclaw.desktop;

import com.javaclaw.desktop.nativebridge.generated.jc_desktop_action;
import com.javaclaw.desktop.nativebridge.generated.jc_desktop_element;
import com.javaclaw.desktop.nativebridge.generated.jc_desktop_frame;
import com.javaclaw.desktop.nativebridge.generated.jc_desktop_window;
import java.lang.foreign.MemoryLayout;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The C bridge is shared by macOS arm64/x64 and Windows x64. */
class DesktopBridgeLayoutTest {
    @Test
    void generatedLayoutsMatchTheVersionedNativeAbi() {
        assertEquals(448, jc_desktop_window.layout().byteSize());
        assertEquals(64, jc_desktop_frame.layout().byteSize());
        assertEquals(64, jc_desktop_action.layout().byteSize());
        assertEquals(348, jc_desktop_element.layout().byteSize());
        assertEquals(16, jc_desktop_window.process_instance_id$offset());
        assertEquals(44, jc_desktop_window.layout().byteOffset(
                MemoryLayout.PathElement.groupElement("app_utf8")));
        assertEquals(32, jc_desktop_frame.layout().byteOffset(
                MemoryLayout.PathElement.groupElement("timestamp_millis")));
        assertEquals(56, jc_desktop_frame.content_revision$offset());
        assertEquals(40, jc_desktop_action.content_revision$offset());
        assertEquals(48, jc_desktop_action.layout().byteOffset(
                MemoryLayout.PathElement.groupElement("text_utf8")));
        assertEquals(28, jc_desktop_element.role_utf8$offset());
        assertEquals(92, jc_desktop_element.label_utf8$offset());
    }
}
