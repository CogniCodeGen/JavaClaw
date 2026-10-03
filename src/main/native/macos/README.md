# macOS desktop bridge

`build.sh` builds the C ABI in `../desktop_bridge.h` as a universal macOS 14+
library. Its default output is `target/native/macos/libjavaclaw_desktop.dylib`.
Pass a destination to place it in a portable distribution's
`runtime/native/macos/` directory. The distribution build must sign this
library together with the application bundle.

The bridge lists currently on-screen shareable windows, captures a selected window with
ScreenCaptureKit, and follows the topmost window of the same process as it
changes. It keeps one BGRA frame in memory and returns copied frames through
the C ABI; callers release them with `jc_desktop_release_frame`. A window or
size change increments the generation and invalidates old action coordinates.
ABI v4 also increments a separate content revision when captured pixels change.
`jc_desktop_list_elements` exposes a bounded, value-free Accessibility summary
for the selected frame. Java binds the returned elements and visual coordinates
to an observation ID before allowing an action.
The optional `jc_desktop_launch_application` entry point resolves an exact
installed application display name or bundle ID to a `.app` bundle, checks
the bundle and launched process identity, and asks `NSWorkspace` to activate
it. If Launch Services misses an installed app, it checks only `/Applications`
and `~/Applications`, with an immediate exact-name path check followed by a
shallow scan capped at 1024 entries per directory. Bundle metadata must match
the requested name or bundle ID. It rejects paths and commands. Success means
the application process was activated; the caller must rediscover a visible
window before capture.
The launch callback has a 12-second bound. A timeout leaves the launch result
unknown, so the caller should discover windows before considering a retry.
Control Center windows whose titles identify another application's bundle are
menu-bar proxies, so discovery omits them while keeping ordinary Control Center
panels. Opening a session succeeds only after ScreenCaptureKit delivers its
first usable BGRA frame. A blank, suspended, stopped, or frame-less stream is
closed and returns a reason that asks the user to show the application's main
window. This avoids reporting a live preview for a stream with no image.
Window discovery records the process start time; opening compares it with the
approved target and rejects a restarted process even if its PID and window ID
were reused. Processes with unreadable start times are omitted. Minimized and
other off-screen windows are omitted because an initial session cannot capture
them. If a window leaves the screen between discovery and opening, the bridge
reports that state and asks the caller to discover again.

Observation requires Screen Recording permission. Background input requires
Accessibility and uses `AXPress`, selected-text insertion, or confirm/cancel actions.
These work only for controls that expose the corresponding AX action or
attribute. A successful `AXPress` reports `ACCEPTED`; an AX error after dispatch
reports `UNKNOWN`. Text insertion reports `VERIFIED` only when the value can be
read back, otherwise a successful insertion reports `ACCEPTED`. These statuses
describe platform admission or control readback; a new screen observation must
still establish the requested application outcome.
Foreground CoreGraphics input requires both Accessibility and Post Event access.
The settings action checks all three permissions and reports every missing one.
Requests advance one at a time: Screen Recording, Accessibility, then Post Event.
The Accessibility step opens its privacy pane directly, without also showing the
system Accessibility prompt; the Post Event step calls `CGRequestPostEventAccess`.
When a permission is still missing, the relevant pane is opened if possible;
Post Event falls back to the Accessibility pane because there is no documented
deep link for a separate Post Event pane. The first Post Event request leaves
the system prompt visible; an explicit retry opens Accessibility settings.
The user must enable the entry for the app running JavaClaw and then check again;
a restart may be needed. Pane deep links may vary by macOS release, so the
generic System Settings application is the final fallback.
Unconfirmed input reports `UNKNOWN` and must not be retried automatically.
Consented foreground input prepares the exact target window for a fresh
observation, checks the window and click point again, and posts CoreGraphics
events. It restores the previous focus unless the user has switched away.
The event API has no application acknowledgement. Complete event posting reports
`ACCEPTED` for platform submission, with business effect still `UNKNOWN`;
partial posting or lost focus during a sequence remains `UNKNOWN`.
Process-directed `CGEventPostToPid` is not advertised yet because
the bridge has no per-application evidence that those events are handled while
the application is in the background.

The reusable snapshot policy has a standalone test covering stale tokens,
window generations, changes inside/outside the selected pixel region, and
accessibility initialization once per process instance:

```bash
mkdir -p target/native-tests
xcrun clang++ -std=c++17 -Wall -Wextra -Werror \
  -I src/main/native/macos src/test/native/macos/ax_snapshot_policy_test.cpp \
  -o target/native-tests/ax_snapshot_policy_test
target/native-tests/ax_snapshot_policy_test
```

ScreenCaptureKit may stop producing frames for minimized, protected, or
otherwise unavailable windows. A capture error produces a negative poll
result, and old frames are discarded. The caller is responsible for showing
the paused state and for serializing close with all other session calls.
