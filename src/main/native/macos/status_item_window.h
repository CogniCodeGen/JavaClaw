#ifndef JAVACLAW_MACOS_STATUS_ITEM_WINDOW_H
#define JAVACLAW_MACOS_STATUS_ITEM_WINDOW_H

#include <cctype>
#include <string_view>

// ScreenCaptureKit exposes third-party menu-bar items as windows owned by
// Control Center. Their title is the other application's bundle identifier,
// but opening the window cannot observe or control that application's UI.
// Keep ordinary Control Center windows (such as its visible panels).
inline bool jc_is_foreign_control_center_item(std::string_view ownerBundle,
                                               std::string_view title) {
    if (ownerBundle != "com.apple.controlcenter" || title == ownerBundle) return false;
    unsigned segments = 0;
    bool hasCharacter = false;
    for (unsigned char character : title) {
        if (character == '.') {
            if (!hasCharacter) return false;
            ++segments;
            hasCharacter = false;
        } else if (std::isalnum(character) || character == '-' || character == '_') {
            hasCharacter = true;
        } else {
            return false;
        }
    }
    return hasCharacter && segments >= 2;
}

#endif
