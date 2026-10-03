#ifndef JAVACLAW_DESKTOP_BRIDGE_H
#define JAVACLAW_DESKTOP_BRIDGE_H

#include <stdint.h>

#if defined(_WIN32)
#define JC_DESKTOP_EXPORT __declspec(dllexport)
#else
#define JC_DESKTOP_EXPORT __attribute__((visibility("default")))
#endif

#ifdef __cplusplus
extern "C" {
#endif

#define JC_DESKTOP_ABI_VERSION 6
#define JC_DESKTOP_APP_BYTES 128
#define JC_DESKTOP_TITLE_BYTES 256
#define JC_DESKTOP_ROLE_BYTES 64
#define JC_DESKTOP_LABEL_BYTES 256

enum jc_desktop_window_flag {
    JC_WINDOW_MINIMIZED = 1,
    JC_WINDOW_VISIBLE = 2,
    JC_WINDOW_POPUP = 4,
    JC_WINDOW_SYSTEM_SURFACE = 8
};

enum jc_desktop_capability {
    JC_CAP_CAPTURE = 1,
    JC_CAP_SEMANTIC_INPUT = 2,
    JC_CAP_DIRECTED_INPUT = 4,
    JC_CAP_FOREGROUND_INPUT = 8
};

enum jc_desktop_action_kind {
    JC_ACTION_CLICK = 1,
    JC_ACTION_TYPE = 2,
    JC_ACTION_KEY = 3,
    JC_ACTION_SCROLL = 4
};

enum jc_desktop_action_mode {
    JC_MODE_BACKGROUND = 1,
    JC_MODE_FOREGROUND = 2
};

enum jc_desktop_action_status {
    JC_ACTION_VERIFIED = 0,
    JC_ACTION_UNKNOWN = 1,
    JC_ACTION_UNSUPPORTED = 2,
    JC_ACTION_STALE_FRAME = 3,
    JC_ACTION_DENIED = 4,
    JC_ACTION_FAILED = 5,
    /* Platform accepted the complete input; application outcome is unverified. */
    JC_ACTION_ACCEPTED = 6
};

enum jc_desktop_element_action {
    JC_ELEMENT_PRESS = 1,
    JC_ELEMENT_WRITE = 2,
    JC_ELEMENT_SCROLL = 4
};

typedef struct jc_desktop_window {
    uint64_t process_id;
    uint64_t window_id;
    /* Platform process creation time; never zero for a discoverable target. */
    uint64_t process_instance_id;
    int32_t x;
    int32_t y;
    int32_t width;
    int32_t height;
    uint32_t flags;
    char app_utf8[JC_DESKTOP_APP_BYTES];
    char title_utf8[JC_DESKTOP_TITLE_BYTES];
} jc_desktop_window;

/* Pixels are BGRA premultiplied, owned by the caller after a successful poll. */
typedef struct jc_desktop_frame {
    uint8_t *pixels;
    uint64_t byte_count;
    int32_t width;
    int32_t height;
    int32_t stride;
    uint64_t timestamp_millis;
    uint64_t generation;
    uint64_t window_id;
    /* Incremented only when captured pixel content changes, not on idle callbacks. */
    uint64_t content_revision;
} jc_desktop_frame;

/* Bounded, snapshot-scoped accessibility summary. Never contains field values. */
typedef struct jc_desktop_element {
    uint32_t index;
    int32_t x;
    int32_t y;
    int32_t width;
    int32_t height;
    uint32_t actions;
    uint32_t flags;
    char role_utf8[JC_DESKTOP_ROLE_BYTES];
    char label_utf8[JC_DESKTOP_LABEL_BYTES];
} jc_desktop_element;

/* x/y are coordinates in the frame identified by generation. */
typedef struct jc_desktop_action {
    uint32_t kind;
    uint32_t mode;
    int32_t x;
    int32_t y;
    int32_t button;
    int32_t clicks;
    int32_t amount;
    uint64_t generation;
    uint64_t content_revision;
    const char *text_utf8;
    uint32_t text_bytes;
} jc_desktop_action;

JC_DESKTOP_EXPORT int32_t jc_desktop_api_version(void);
JC_DESKTOP_EXPORT int32_t jc_desktop_probe(uint32_t *capabilities, char *detail_utf8,
                                            uint32_t detail_capacity);
/* Explicit user action only. Requests OS permission where a platform prompt
 * exists, then returns the same capability bits as probe. This call may wait
 * for an OS dialog; callers must run it off the UI thread. Windows has no
 * general desktop-automation permission dialog and only probes capabilities. */
JC_DESKTOP_EXPORT int32_t jc_desktop_request_permissions(uint32_t *capabilities,
                                                          char *detail_utf8,
                                                          uint32_t detail_capacity);
/* count receives the total count. Up to capacity entries are copied to windows. */
JC_DESKTOP_EXPORT int32_t jc_desktop_list_windows(jc_desktop_window *windows,
                                                   uint32_t capacity, uint32_t *count);
/* Optional ABI v3 extension. Launch or activate an installed application by its
 * exact display name or bundle identifier, never by a caller-supplied path or
 * command. On success process_id is the actual running application's PID.
 * A nonzero result may still mean the OS started the app (for example a launch
 * timeout); callers must discover windows before attempting another launch. */
JC_DESKTOP_EXPORT int32_t jc_desktop_launch_application(const char *application_utf8,
                                                        uint64_t *process_id,
                                                        char *detail_utf8,
                                                        uint32_t detail_capacity);
/* Optional additive extension. Returns the OS-owned stable application ID for
 * a discovered process: bundle ID on macOS, executable name on Windows.
 * The output is empty on failure; no window title or user text is consulted. */
JC_DESKTOP_EXPORT int32_t jc_desktop_process_application_id(uint64_t process_id,
                                                            char *application_id_utf8,
                                                            uint32_t capacity);
/* Optional read-only preflight for a launch request. The returned ID comes
 * from the OS installation registry, never from the caller's display text. */
JC_DESKTOP_EXPORT int32_t jc_desktop_resolve_application_id(const char *application_utf8,
                                                            char *application_id_utf8,
                                                            uint32_t capacity);
/* Optional additive, read-only installed-application catalog. JSON schema 1:
 * applications[{name,displayName,applicationId,launchName,aliases}], count,
 * truncated. launchName is an exact OS name accepted by launch_application;
 * applicationId is the stable identity used to verify the launched process.
 * Identities come only from OS application bundles/registration/shortcuts;
 * executable paths, arguments, document names and file contents are excluded.
 * At most 256 entries, 8 aliases per entry and 32768 bytes including NUL.
 * required_bytes is mandatory and receives the complete size including NUL.
 * NULL + capacity 0 probes size; insufficient capacity returns -1 and an empty
 * buffer, never partial JSON. Probe and copy enumerate independently, so a
 * caller may use a fixed 32768-byte buffer or retry a changed size read-only.
 * This optional symbol does not change structure layouts or require input permissions. */
JC_DESKTOP_EXPORT int32_t jc_desktop_list_applications(char *catalog_utf8,
                                                       uint32_t capacity,
                                                       uint32_t *required_bytes);
/* Refuses a PID/window pair whose process instance changed after discovery. */
JC_DESKTOP_EXPORT void *jc_desktop_open(uint64_t process_id, uint64_t window_id,
                                        uint64_t process_instance_id,
                                        char *detail_utf8, uint32_t detail_capacity);
/* Returns 0 for a fresh frame, 1 for no new frame, and a negative error code. */
JC_DESKTOP_EXPORT int32_t jc_desktop_poll_frame(void *session, jc_desktop_frame *frame,
                                                 uint32_t timeout_millis);
JC_DESKTOP_EXPORT void jc_desktop_release_frame(jc_desktop_frame *frame);
/* Returns 0 on success, 1 if the requested frame is stale, negative on error.
 * Copies at most capacity visible elements. count is the copied count. */
JC_DESKTOP_EXPORT int32_t jc_desktop_list_elements(void *session,
    uint64_t expected_generation, uint64_t expected_content_revision,
    jc_desktop_element *elements, uint32_t capacity, uint32_t *count);
/* Optional additive extension. Diagnostic counters/errors from the last AX
 * catalog read; excludes field values and user text. No input is dispatched. */
JC_DESKTOP_EXPORT int32_t jc_desktop_element_diagnostics(void *session,
    char *detail_utf8, uint32_t detail_capacity);
JC_DESKTOP_EXPORT int32_t jc_desktop_current_window(void *session, jc_desktop_window *window);
/* Action-scoped foreground lease. Returns 0 only after the exact target window
 * is active. A later foreground action or restore ends the lease. */
JC_DESKTOP_EXPORT int32_t jc_desktop_prepare_foreground(void *session,
    char *detail_utf8, uint32_t detail_capacity);
JC_DESKTOP_EXPORT void jc_desktop_restore_foreground(void *session);
/* Returns a jc_desktop_action_status. UNKNOWN must never be retried blindly. */
JC_DESKTOP_EXPORT int32_t jc_desktop_perform(void *session, const jc_desktop_action *action,
                                              char *detail_utf8, uint32_t detail_capacity);
/* Optional additive extension for a background single-left-click AXPress.
 * element_index is an opaque token returned by
 * the latest list_elements call, bound to that frame and target window.
 * A stale token must be refused before dispatch, never reinterpreted as a
 * coordinate click. UNKNOWN must be observed before any further input. */
JC_DESKTOP_EXPORT int32_t jc_desktop_perform_element(void *session,
    const jc_desktop_action *action, uint32_t element_index,
    char *detail_utf8, uint32_t detail_capacity);
JC_DESKTOP_EXPORT void jc_desktop_close(void *session);

#ifdef __cplusplus
}
#endif

#endif
