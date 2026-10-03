#include "status_item_window.h"

#include <cassert>

int main() {
    assert(jc_is_foreign_control_center_item("com.apple.controlcenter", "org.example.reader"));
    assert(jc_is_foreign_control_center_item("com.apple.controlcenter", "org.example.status_item"));
    assert(!jc_is_foreign_control_center_item("com.apple.controlcenter", "Control Center"));
    assert(!jc_is_foreign_control_center_item("com.apple.controlcenter", "com.apple.controlcenter"));
    assert(!jc_is_foreign_control_center_item("org.example.reader", "org.example.reader"));
    assert(!jc_is_foreign_control_center_item("com.apple.controlcenter", "Wi-Fi"));
    assert(!jc_is_foreign_control_center_item("com.apple.controlcenter", "com..example"));
    return 0;
}
