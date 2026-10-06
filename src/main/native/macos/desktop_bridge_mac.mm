#import <AppKit/AppKit.h>
#import <ApplicationServices/ApplicationServices.h>
#import <CoreMedia/CoreMedia.h>
#import <CoreVideo/CoreVideo.h>
#import <ScreenCaptureKit/ScreenCaptureKit.h>

#include "../desktop_bridge.h"
#include "../include/application_catalog.h"
#include "ax_snapshot_policy.h"
#include "status_item_window.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstddef>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>
#include <unordered_set>
#include <utility>
#include <vector>
#include <libproc.h>
#include <unistd.h>

static_assert(sizeof(jc_desktop_window) == 432, "desktop ABI window layout changed");
static_assert(sizeof(jc_desktop_frame) == 64, "desktop ABI frame layout changed");
static_assert(sizeof(jc_desktop_action) == 64, "desktop ABI action layout changed");
static_assert(sizeof(jc_desktop_element) == 348, "desktop ABI element layout changed");
static_assert(offsetof(jc_desktop_window, app_utf8) == 44, "desktop ABI window offset changed");
static_assert(offsetof(jc_desktop_frame, timestamp_millis) == 32, "desktop ABI frame offset changed");
static_assert(offsetof(jc_desktop_action, text_utf8) == 48, "desktop ABI action offset changed");

struct MacSession;
namespace { void restoreForegroundLease(MacSession *session, bool allowTargetAppOnly = false); }

@interface MacStreamSink : NSObject <SCStreamOutput, SCStreamDelegate> {
@public
    std::atomic<MacSession *> owner;
    uint64_t generation;
}
@end

namespace {

constexpr auto kContentTimeout = std::chrono::seconds(5);
constexpr auto kFirstFrameTimeout = std::chrono::seconds(5);
constexpr auto kFrameFreshness = std::chrono::milliseconds(2500);
constexpr auto kApplicationLaunchTimeout = std::chrono::seconds(12);
std::atomic_bool postEventRequestIssued{false};

uint64_t nowMillis() {
    return static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::system_clock::now().time_since_epoch()).count());
}

uint64_t monotonicMillis() {
    return static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count());
}

struct ProcessInstance {
    uint64_t startedSeconds = 0;
    uint64_t startedMicroseconds = 0;
};

bool processInstance(pid_t pid, ProcessInstance *instance) {
    if (pid <= 0 || !instance) return false;
    proc_bsdinfo info{};
    if (proc_pidinfo(pid, PROC_PIDTBSDINFO, 0, &info, sizeof(info)) != sizeof(info)
            || info.pbi_pid != static_cast<uint32_t>(pid) || info.pbi_start_tvsec == 0
            || info.pbi_start_tvusec >= 1000000) return false;
    instance->startedSeconds = info.pbi_start_tvsec;
    instance->startedMicroseconds = info.pbi_start_tvusec;
    return true;
}

uint64_t processInstanceId(const ProcessInstance &instance) {
    constexpr uint64_t microsPerSecond = 1000000;
    if (instance.startedSeconds == 0 || instance.startedMicroseconds >= microsPerSecond
            || instance.startedSeconds >
                (std::numeric_limits<uint64_t>::max() - instance.startedMicroseconds)
                    / microsPerSecond) return 0;
    return instance.startedSeconds * microsPerSecond + instance.startedMicroseconds;
}

bool sameProcessInstance(const ProcessInstance &first, const ProcessInstance &second) {
    return first.startedSeconds == second.startedSeconds
        && first.startedMicroseconds == second.startedMicroseconds;
}

void writeDetail(char *buffer, uint32_t capacity, NSString *value) {
    if (!buffer || capacity == 0) return;
    const char *utf8 = value ? value.UTF8String : "";
    std::snprintf(buffer, capacity, "%s", utf8 ? utf8 : "");
}

bool matchesApplicationName(NSString *requested, NSBundle *bundle, NSURL *url) {
    NSArray<NSString *> *names = @[
        bundle.bundleIdentifier ?: @"",
        [bundle objectForInfoDictionaryKey:@"CFBundleDisplayName"] ?: @"",
        [bundle objectForInfoDictionaryKey:@"CFBundleName"] ?: @"",
        url.URLByDeletingPathExtension.lastPathComponent ?: @""
    ];
    for (NSString *name in names) {
        if (name.length && [requested caseInsensitiveCompare:name] == NSOrderedSame) return true;
    }
    return false;
}

NSURL *matchingBundleUnderRoot(NSURL *candidate, NSURL *root, NSString *requested) {
    NSURL *resolvedRoot = root.URLByResolvingSymlinksInPath;
    NSURL *resolved = candidate.URLByResolvingSymlinksInPath;
    NSString *prefix = [resolvedRoot.path stringByAppendingString:@"/"];
    if (!resolved.isFileURL || ![resolved.path hasPrefix:prefix]
            || [resolved.pathExtension caseInsensitiveCompare:@"app"] != NSOrderedSame) return nil;
    BOOL directory = NO;
    if (![NSFileManager.defaultManager fileExistsAtPath:resolved.path isDirectory:&directory]
            || !directory) return nil;
    NSBundle *bundle = [NSBundle bundleWithURL:resolved];
    if (!bundle.bundleIdentifier.length || !matchesApplicationName(requested, bundle, resolved))
        return nil;
    return resolved;
}

NSURL *standardApplicationBundle(NSString *requested) {
    NSArray<NSString *> *locations = @[
        @"/Applications",
        [NSHomeDirectory() stringByAppendingPathComponent:@"Applications"]
    ];
    for (NSString *location in locations) {
        NSURL *root = [NSURL fileURLWithPath:location isDirectory:YES];
        NSURL *direct = [root URLByAppendingPathComponent:
                [requested stringByAppendingPathExtension:@"app"] isDirectory:YES];
        NSURL *matched = matchingBundleUnderRoot(direct, root, requested);
        if (matched) return matched;

        NSDirectoryEnumerator<NSURL *> *entries = [NSFileManager.defaultManager
                enumeratorAtURL:root includingPropertiesForKeys:nil
                options:NSDirectoryEnumerationSkipsSubdirectoryDescendants
                errorHandler:nil];
        NSUInteger inspected = 0;
        for (NSURL *candidate in entries) {
            if (++inspected > 1024) break;
            matched = matchingBundleUnderRoot(candidate, root, requested);
            if (matched) return matched;
        }
    }
    return nil;
}

NSURL *resolvedApplicationBundle(NSString *requested) {
    NSWorkspace *workspace = NSWorkspace.sharedWorkspace;
    NSURL *url = [workspace URLForApplicationWithBundleIdentifier:requested];
    if (!url) {
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
        NSString *installedPath = [workspace fullPathForApplication:requested];
#pragma clang diagnostic pop
        if (installedPath) url = [NSURL fileURLWithPath:installedPath];
    }
    if (!url) url = standardApplicationBundle(requested);
    if (!url) return nil;
    url = url.URLByResolvingSymlinksInPath;
    BOOL directory = NO;
    if (!url.isFileURL
            || [url.pathExtension caseInsensitiveCompare:@"app"] != NSOrderedSame
            || ![NSFileManager.defaultManager fileExistsAtPath:url.path isDirectory:&directory]
            || !directory) return nil;
    NSBundle *bundle = [NSBundle bundleWithURL:url];
    return bundle.bundleIdentifier.length && matchesApplicationName(requested, bundle, url)
            ? url : nil;
}

NSString *validRequestedApplication(const char *input) {
    if (!input) return nil;
    size_t length = strnlen(input, 257);
    if (length == 0 || length > 256 || std::strstr(input, "..")) return nil;
    for (size_t index = 0; index < length; ++index) {
        unsigned char byte = static_cast<unsigned char>(input[index]);
        if (byte < 0x20 || byte == 0x7f || byte == '/' || byte == '\\' || byte == ':')
            return nil;
    }
    NSString *requested = [NSString stringWithUTF8String:input];
    return requested && [requested stringByTrimmingCharactersInSet:
            NSCharacterSet.whitespaceAndNewlineCharacterSet].length == requested.length
            ? requested : nil;
}

#include "application_catalog_mac.inc"

bool openPrivacySettings(NSString *anchor) {
    NSWorkspace *workspace = [NSWorkspace sharedWorkspace];
    // Privacy pane URLs can change between macOS releases. Always leave a way
    // to reach System Settings even when a specific anchor stops working.
    NSArray<NSString *> *urls = @[
        [@"x-apple.systempreferences:com.apple.settings.PrivacySecurity.extension?"
            stringByAppendingString:anchor],
        [@"x-apple.systempreferences:com.apple.preference.security?"
            stringByAppendingString:anchor],
        @"x-apple.systempreferences:com.apple.settings.PrivacySecurity.extension?Privacy",
        @"x-apple.systempreferences:com.apple.preference.security?Privacy"
    ];
    for (NSString *value in urls) {
        NSURL *url = [NSURL URLWithString:value];
        if (url && [workspace openURL:url]) return true;
    }
    NSURL *settings = [workspace URLForApplicationWithBundleIdentifier:@"com.apple.systempreferences"];
    return settings && [workspace openURL:settings];
}

void copyUtf8(char *destination, size_t capacity, NSString *value) {
    if (!destination || capacity == 0) return;
    const char *utf8 = value ? value.UTF8String : "";
    if (!utf8) utf8 = "";
    size_t length = std::strlen(utf8);
    size_t copied = std::min(length, capacity - 1);
    if (copied < length) {
        while (copied > 0 && (static_cast<unsigned char>(utf8[copied]) & 0xc0) == 0x80) --copied;
    }
    std::memcpy(destination, utf8, copied);
    destination[copied] = '\0';
}

bool supportedOS() {
    NSOperatingSystemVersion version = NSProcessInfo.processInfo.operatingSystemVersion;
    return version.majorVersion >= 14;
}

SCShareableContent *shareableContent(NSString **errorText) {
    dispatch_semaphore_t semaphore = dispatch_semaphore_create(0);
    __block SCShareableContent *found = nil;
    __block NSError *failure = nil;
    [SCShareableContent getShareableContentWithCompletionHandler:^(SCShareableContent *content, NSError *error) {
        found = content;
        failure = error;
        dispatch_semaphore_signal(semaphore);
    }];
    if (dispatch_semaphore_wait(semaphore, dispatch_time(DISPATCH_TIME_NOW,
             static_cast<int64_t>(std::chrono::duration_cast<std::chrono::nanoseconds>(kContentTimeout).count()))) != 0) {
        if (errorText) *errorText = @"ScreenCaptureKit timed out while listing windows";
        return nil;
    }
    if (!found && errorText) *errorText = failure.localizedDescription ?: @"ScreenCaptureKit could not list windows";
    return found;
}

std::unordered_map<uint32_t, size_t> windowOrder() {
    std::unordered_map<uint32_t, size_t> order;
    CFArrayRef raw = CGWindowListCopyWindowInfo(kCGWindowListOptionOnScreenOnly, kCGNullWindowID);
    if (!raw) return order;
    NSArray *entries = CFBridgingRelease(raw);
    size_t index = 0;
    for (NSDictionary *entry in entries) {
        NSNumber *number = entry[(id)kCGWindowNumber];
        if (number) order[number.unsignedIntValue] = index++;
    }
    return order;
}

bool windowForProcess(SCWindow *window, pid_t pid) {
    return window && window.owningApplication && window.owningApplication.processID == pid;
}

bool validWindow(SCWindow *window) {
    CGRect rect = window.frame;
    return rect.size.width >= 24 && rect.size.height >= 24 && window.windowLayer >= 0;
}

bool foreignControlCenterItem(SCWindow *window) {
    if (!window || !window.owningApplication) return false;
    NSString *owner = window.owningApplication.bundleIdentifier;
    NSString *title = window.title;
    if (!owner || !title) return false;
    return jc_is_foreign_control_center_item(owner.UTF8String ?: "", title.UTF8String ?: "");
}

bool confirmedMinimized(SCWindow *window) {
    if (window.isOnScreen || !AXIsProcessTrusted()) return false;
    AXUIElementRef app = AXUIElementCreateApplication(window.owningApplication.processID);
    if (!app) return false;
    AXUIElementSetMessagingTimeout(app, 0.5);
    CFTypeRef rawWindows = nullptr;
    AXError error = AXUIElementCopyAttributeValue(app, kAXWindowsAttribute, &rawWindows);
    CFRelease(app);
    if (error != kAXErrorSuccess || !rawWindows || CFGetTypeID(rawWindows) != CFArrayGetTypeID()) {
        if (rawWindows) CFRelease(rawWindows);
        return false;
    }
    bool minimized = false;
    CFArrayRef windows = static_cast<CFArrayRef>(rawWindows);
    for (CFIndex index = 0; index < CFArrayGetCount(windows); ++index) {
        AXUIElementRef candidate = static_cast<AXUIElementRef>(const_cast<void *>(CFArrayGetValueAtIndex(windows, index)));
        CFTypeRef positionRaw = nullptr;
        CFTypeRef sizeRaw = nullptr;
        CFTypeRef minRaw = nullptr;
        AXUIElementCopyAttributeValue(candidate, kAXPositionAttribute, &positionRaw);
        AXUIElementCopyAttributeValue(candidate, kAXSizeAttribute, &sizeRaw);
        AXUIElementCopyAttributeValue(candidate, kAXMinimizedAttribute, &minRaw);
        CGPoint position{};
        CGSize size{};
        bool sameBounds = positionRaw && sizeRaw
            && CFGetTypeID(positionRaw) == AXValueGetTypeID()
            && CFGetTypeID(sizeRaw) == AXValueGetTypeID()
            && AXValueGetType(static_cast<AXValueRef>(positionRaw)) == kAXValueTypeCGPoint
            && AXValueGetType(static_cast<AXValueRef>(sizeRaw)) == kAXValueTypeCGSize
            && AXValueGetValue(static_cast<AXValueRef>(positionRaw), kAXValueTypeCGPoint, &position)
            && AXValueGetValue(static_cast<AXValueRef>(sizeRaw), kAXValueTypeCGSize, &size)
            && std::abs(position.x - window.frame.origin.x) <= 2
            && std::abs(position.y - window.frame.origin.y) <= 2
            && std::abs(size.width - window.frame.size.width) <= 2
            && std::abs(size.height - window.frame.size.height) <= 2;
        if (sameBounds && minRaw && CFGetTypeID(minRaw) == CFBooleanGetTypeID()
                && CFBooleanGetValue(static_cast<CFBooleanRef>(minRaw))) minimized = true;
        if (positionRaw) CFRelease(positionRaw);
        if (sizeRaw) CFRelease(sizeRaw);
        if (minRaw) CFRelease(minRaw);
        if (minimized) break;
    }
    CFRelease(rawWindows);
    return minimized;
}

void fillWindow(jc_desktop_window *out, SCWindow *window, uint64_t initialId,
                uint64_t processInstanceId,
                bool inspectMinimized = false) {
    std::memset(out, 0, sizeof(*out));
    out->process_id = static_cast<uint64_t>(window.owningApplication.processID);
    out->window_id = window.windowID;
    out->process_instance_id = processInstanceId;
    CGRect frame = window.frame;
    out->x = static_cast<int32_t>(std::lround(frame.origin.x));
    out->y = static_cast<int32_t>(std::lround(frame.origin.y));
    out->width = static_cast<int32_t>(std::lround(frame.size.width));
    out->height = static_cast<int32_t>(std::lround(frame.size.height));
    out->flags = window.isOnScreen ? JC_WINDOW_VISIBLE
        : inspectMinimized && confirmedMinimized(window) ? JC_WINDOW_MINIMIZED : 0;
    if (window.windowLayer > 0 || (initialId && initialId != window.windowID)) out->flags |= JC_WINDOW_POPUP;
    NSString *ownerBundle = window.owningApplication.bundleIdentifier;
    if ([ownerBundle isEqualToString:@"com.apple.controlcenter"]
            || [ownerBundle isEqualToString:@"com.apple.systemuiserver"])
        out->flags |= JC_WINDOW_SYSTEM_SURFACE;
    copyUtf8(out->app_utf8, sizeof(out->app_utf8), window.owningApplication.applicationName);
    copyUtf8(out->title_utf8, sizeof(out->title_utf8), window.title);
}

SCWindow *preferredWindow(SCShareableContent *content, pid_t pid, uint64_t initialId, bool initialOnly) {
    auto order = windowOrder();
    SCWindow *fallback = nil;
    SCWindow *preferred = nil;
    size_t best = SIZE_MAX;
    for (SCWindow *window in content.windows) {
        if (!windowForProcess(window, pid) || !validWindow(window)
                || foreignControlCenterItem(window)) continue;
        if (window.windowID == initialId) fallback = window;
        if (initialOnly && window.windowID != initialId) continue;
        if (!window.isOnScreen) continue;
        auto position = order.find(window.windowID);
        size_t rank = position == order.end() ? SIZE_MAX - 1 : position->second;
        if (!preferred || rank < best) {
            preferred = window;
            best = rank;
        }
    }
    return preferred ?: fallback;
}

} // namespace

struct AxSnapshotElement {
    uint32_t token;
    AXUIElementRef element;
    AXUIElementRef window;
    uint64_t windowId;
    uint64_t generation;
    uint64_t contentRevision;
    CGRect bounds;
    std::string role;
    std::string label;
    uint32_t actions;
    int32_t frameX;
    int32_t frameY;
    int32_t frameWidth;
    int32_t frameHeight;
    std::vector<uint8_t> roiPixels;
};

struct MacSession {
    pid_t pid;
    uint64_t initialId;
    ProcessInstance process;
    std::atomic<bool> stopping{false};
    std::atomic<bool> processGone{false};
    std::atomic<uint32_t> activeCalls{0};
    std::mutex callDrainMutex;
    std::condition_variable callsDrained;
    std::mutex control;
    std::mutex state;
    std::condition_variable frameReady;
    std::thread monitor;
    SCStream *stream = nil;
    MacStreamSink *sink = nil;
    dispatch_queue_t outputQueue = nil;
    jc_desktop_window current{};
    uint64_t generation = 0;
    uint64_t timestamp = 0;
    uint64_t frameSequence = 0;
    uint64_t deliveredSequence = 0;
    uint64_t contentRevision = 0;
    int32_t frameWidth = 0;
    int32_t frameHeight = 0;
    int32_t frameStride = 0;
    std::vector<uint8_t> latest;
    bool available = false;
    bool captureFailed = false;
    std::string captureFailureDetail;
    NSRunningApplication * __strong previousFrontmost = nil;
    uint64_t previousWindowId = 0;
    uint64_t leasedWindowId = 0;
    jc_desktop_window leasedWindow{};
    bool foregroundLease = false;
    bool leaseSentInput = false;
    // Protected by control. The token is never reused during this session.
    std::vector<AxSnapshotElement> axElements;
    uint32_t nextAxToken = 1;
    NSString * __strong axDiagnostics = @"AX elements have not been inspected";

    MacSession(pid_t pid, uint64_t window, ProcessInstance instance)
        : pid(pid), initialId(window), process(instance) {}
    ~MacSession() { stop(); }

    void onFrame(uint64_t sourceGeneration, CMSampleBufferRef sample);
    void onFailure(uint64_t sourceGeneration, const char *detail = "ScreenCaptureKit stream stopped");
    bool start(SCWindow *window, NSString **errorText);
    void stopStream();
    void refresh();
    void stop();
    void clearAxElements() {
        for (const auto &entry : axElements) {
            CFRelease(entry.element);
            CFRelease(entry.window);
        }
        axElements.clear();
    }
    bool isOriginalProcess() {
        if (processGone) return false;
        ProcessInstance current;
        if (!processInstance(pid, &current) || !sameProcessInstance(process, current)) {
            processGone = true;
            frameReady.notify_all();
            return false;
        }
        return true;
    }
};

struct SessionCall {
    MacSession *session;
    explicit SessionCall(MacSession *value) : session(value) {
        session->activeCalls.fetch_add(1, std::memory_order_acq_rel);
    }
    ~SessionCall() {
        if (session->activeCalls.fetch_sub(1, std::memory_order_acq_rel) == 1) {
            session->callsDrained.notify_all();
        }
    }
};

@implementation MacStreamSink
- (void)stream:(SCStream *)stream didStopWithError:(NSError *)error {
    (void)stream;
    MacSession *session = owner.load(std::memory_order_acquire);
    if (session) session->onFailure(generation,
        error.localizedDescription.UTF8String ?: "ScreenCaptureKit stream stopped");
}
- (void)stream:(SCStream *)stream didOutputSampleBuffer:(CMSampleBufferRef)sample
        ofType:(SCStreamOutputType)type {
    (void)stream;
    if (type != SCStreamOutputTypeScreen) return;
    MacSession *session = owner.load(std::memory_order_acquire);
    if (session) session->onFrame(generation, sample);
}
@end

void MacSession::onFrame(uint64_t sourceGeneration, CMSampleBufferRef sample) {
    if (!CMSampleBufferIsValid(sample)) return;
    CFArrayRef attachments = CMSampleBufferGetSampleAttachmentsArray(sample, false);
    if (attachments && CFArrayGetCount(attachments) > 0) {
        CFDictionaryRef metadata = static_cast<CFDictionaryRef>(CFArrayGetValueAtIndex(attachments, 0));
        CFNumberRef status = static_cast<CFNumberRef>(CFDictionaryGetValue(metadata, (__bridge const void *)SCStreamFrameInfoStatus));
        NSInteger rawStatus = SCFrameStatusComplete;
        if (status) CFNumberGetValue(status, kCFNumberNSIntegerType, &rawStatus);
        if (rawStatus == SCFrameStatusIdle) {
            // An idle callback confirms that the previous pixels are still current.
            std::lock_guard guard(state);
            if (!stopping && !processGone && sourceGeneration == generation && available && !latest.empty()) {
                timestamp = nowMillis();
                ++frameSequence;
                frameReady.notify_all();
            }
            return;
        }
        if (rawStatus == SCFrameStatusBlank || rawStatus == SCFrameStatusSuspended
                || rawStatus == SCFrameStatusStopped) {
            onFailure(sourceGeneration, rawStatus == SCFrameStatusBlank
                ? "ScreenCaptureKit returned a blank frame for this window"
                : rawStatus == SCFrameStatusSuspended
                    ? "ScreenCaptureKit suspended capture for this window"
                    : "ScreenCaptureKit stopped capturing this window");
            return;
        }
        if (rawStatus != SCFrameStatusComplete) return;
    }
    CVImageBufferRef image = CMSampleBufferGetImageBuffer(sample);
    if (!image || CVPixelBufferGetPixelFormatType(image) != kCVPixelFormatType_32BGRA) return;
    if (CVPixelBufferLockBaseAddress(image, kCVPixelBufferLock_ReadOnly) != kCVReturnSuccess) return;
    const auto *bytes = static_cast<const uint8_t *>(CVPixelBufferGetBaseAddress(image));
    size_t width = CVPixelBufferGetWidth(image);
    size_t height = CVPixelBufferGetHeight(image);
    size_t stride = CVPixelBufferGetBytesPerRow(image);
    if (bytes && width && height && stride >= width * 4 && height <= SIZE_MAX / stride
            && stride * height <= 256ULL * 1024 * 1024
            && width <= INT32_MAX && height <= INT32_MAX && stride <= INT32_MAX) {
        std::vector<uint8_t> copy(bytes, bytes + stride * height);
        {
            std::lock_guard guard(state);
            if (!stopping && !processGone && sourceGeneration == generation && available) {
                bool changed = frameWidth != static_cast<int32_t>(width)
                    || frameHeight != static_cast<int32_t>(height)
                    || frameStride != static_cast<int32_t>(stride)
                    || latest != copy;
                if (changed) {
                    latest.swap(copy);
                    ++contentRevision;
                }
                frameWidth = static_cast<int32_t>(width);
                frameHeight = static_cast<int32_t>(height);
                frameStride = static_cast<int32_t>(stride);
                timestamp = nowMillis();
                ++frameSequence;
                captureFailed = false;
            }
        }
        frameReady.notify_all();
    }
    CVPixelBufferUnlockBaseAddress(image, kCVPixelBufferLock_ReadOnly);
}

void MacSession::onFailure(uint64_t sourceGeneration, const char *detail) {
    {
        std::lock_guard guard(state);
        if (sourceGeneration == generation) {
            captureFailed = true;
            captureFailureDetail = detail ? detail : "ScreenCaptureKit stream stopped";
            latest.clear();
        }
    }
    frameReady.notify_all();
}

bool MacSession::start(SCWindow *window, NSString **errorText) {
    // The caller holds control. A new sink prevents callbacks from an older stream
    // from ever publishing a frame for the new window generation.
    SCContentFilter *filter = [[SCContentFilter alloc] initWithDesktopIndependentWindow:window];
    SCStreamConfiguration *configuration = [[SCStreamConfiguration alloc] init];
    CGFloat scale = filter.pointPixelScale;
    if (!(scale >= 1 && scale <= 4)) scale = 2;
    double pixelWidth = window.frame.size.width * scale;
    double pixelHeight = window.frame.size.height * scale;
    double reduction = std::min({1.0, 4096.0 / pixelWidth, 4096.0 / pixelHeight});
    configuration.width = std::max<size_t>(1, static_cast<size_t>(std::ceil(pixelWidth * reduction)));
    configuration.height = std::max<size_t>(1, static_cast<size_t>(std::ceil(pixelHeight * reduction)));
    configuration.pixelFormat = kCVPixelFormatType_32BGRA;
    configuration.minimumFrameInterval = CMTimeMake(1, 15);
    configuration.queueDepth = 3;
    configuration.showsCursor = NO;
    configuration.scalesToFit = YES;
    configuration.capturesAudio = NO;

    MacStreamSink *newSink = [[MacStreamSink alloc] init];
    newSink->generation = generation;
    newSink->owner.store(this, std::memory_order_release);
    dispatch_queue_t queue = dispatch_queue_create("com.javaclaw.desktop.mac.capture", DISPATCH_QUEUE_SERIAL);
    SCStream *newStream = [[SCStream alloc] initWithFilter:filter configuration:configuration delegate:newSink];
    NSError *addError = nil;
    if (![newStream addStreamOutput:newSink type:SCStreamOutputTypeScreen sampleHandlerQueue:queue error:&addError]) {
        newSink->owner.store(nullptr, std::memory_order_release);
        if (errorText) *errorText = addError.localizedDescription ?: @"Could not add ScreenCaptureKit output";
        return false;
    }
    dispatch_semaphore_t semaphore = dispatch_semaphore_create(0);
    __block NSError *startError = nil;
    [newStream startCaptureWithCompletionHandler:^(NSError *error) {
        startError = error;
        dispatch_semaphore_signal(semaphore);
    }];
    if (dispatch_semaphore_wait(semaphore, dispatch_time(DISPATCH_TIME_NOW, 5 * NSEC_PER_SEC)) != 0) {
        newSink->owner.store(nullptr, std::memory_order_release);
        [newStream stopCaptureWithCompletionHandler:nil];
        if (errorText) *errorText = @"ScreenCaptureKit stream start timed out";
        return false;
    }
    if (startError) {
        newSink->owner.store(nullptr, std::memory_order_release);
        [newStream stopCaptureWithCompletionHandler:nil];
        if (errorText) *errorText = startError.localizedDescription;
        return false;
    }
    stream = newStream;
    sink = newSink;
    outputQueue = queue;
    // startCapture only acknowledges that the stream was accepted. Some
    // shareable windows never yield usable pixels (notably menu-bar proxies).
    // Do not announce a usable session before the first complete BGRA frame.
    std::string failure;
    bool hasFrame;
    {
        std::unique_lock guard(state);
        frameReady.wait_for(guard, kFirstFrameTimeout, [&] {
            return stopping || processGone || !available || captureFailed || !latest.empty();
        });
        hasFrame = !stopping && !processGone && available && !captureFailed && !latest.empty();
        if (!hasFrame && captureFailed) failure = captureFailureDetail;
    }
    if (!hasFrame) {
        stopStream();
        if (errorText) *errorText = failure.empty()
            ? @"ScreenCaptureKit produced no usable frame for this window; show the application's main window and try again"
            : [NSString stringWithFormat:@"%s; show the application's main window and try again", failure.c_str()];
        return false;
    }
    return true;
}

void MacSession::stopStream() {
    if (!stream) return;
    SCStream *old = stream;
    MacStreamSink *oldSink = sink;
    dispatch_queue_t oldQueue = outputQueue;
    stream = nil;
    sink = nil;
    outputQueue = nil;
    dispatch_semaphore_t semaphore = dispatch_semaphore_create(0);
    [old stopCaptureWithCompletionHandler:^(NSError *error) {
        (void)error;
        dispatch_semaphore_signal(semaphore);
    }];
    dispatch_semaphore_wait(semaphore, dispatch_time(DISPATCH_TIME_NOW, 5 * NSEC_PER_SEC));
    oldSink->owner.store(nullptr, std::memory_order_release);
    if (oldQueue) dispatch_sync(oldQueue, ^{});
}

void MacSession::refresh() {
    if (stopping) return;
    std::lock_guard operation(control);
    if (stopping) return;
    if (!isOriginalProcess()) {
        stopStream();
        {
            std::lock_guard guard(state);
            available = false;
            latest.clear();
            std::memset(&current, 0, sizeof(current));
        }
        frameReady.notify_all();
        return;
    }
    if (!CGPreflightScreenCaptureAccess()) {
        stopStream();
        {
            std::lock_guard guard(state);
            available = false;
            captureFailed = true;
            latest.clear();
        }
        frameReady.notify_all();
        return;
    }
    NSString *error = nil;
    SCShareableContent *content = shareableContent(&error);
    if (!content) {
        onFailure(generation);
        return;
    }
    if (!isOriginalProcess()) {
        stopStream();
        std::lock_guard guard(state);
        available = false;
        latest.clear();
        std::memset(&current, 0, sizeof(current));
        frameReady.notify_all();
        return;
    }
    SCWindow *selected = preferredWindow(content, pid, initialId, false);
    if (!selected || !selected.isOnScreen) {
        stopStream();
        {
            std::lock_guard guard(state);
            available = false;
            captureFailed = false;
            latest.clear();
            if (selected) fillWindow(&current, selected, initialId, processInstanceId(process), true);
            else std::memset(&current, 0, sizeof(current));
        }
        frameReady.notify_all();
        return;
    }
    jc_desktop_window updated;
    fillWindow(&updated, selected, initialId, processInstanceId(process));
    bool changed;
    {
        std::lock_guard guard(state);
        changed = !stream || updated.window_id != current.window_id
            || updated.x != current.x || updated.y != current.y
            || updated.width != current.width || updated.height != current.height;
        current = updated;
        available = true;
        if (changed) {
            ++generation;
            latest.clear();
            contentRevision = 0;
            captureFailed = false;
            captureFailureDetail.clear();
        }
    }
    if (changed) {
        stopStream();
        if (!start(selected, &error)) onFailure(generation);
        frameReady.notify_all();
    }
}

void MacSession::stop() {
    if (stopping.exchange(true)) return;
    frameReady.notify_all();
    if (monitor.joinable()) monitor.join();
    {
        std::lock_guard operation(control);
        restoreForegroundLease(this);
        clearAxElements();
        stopStream();
        std::lock_guard guard(state);
        latest.clear();
        available = false;
    }
    std::unique_lock callLock(callDrainMutex);
    callsDrained.wait(callLock, [&] { return activeCalls.load(std::memory_order_acquire) == 0; });
}

namespace {

bool sameBounds(CGRect bounds, const jc_desktop_window &expected);

struct AxRefHash {
    size_t operator()(AXUIElementRef value) const { return CFHash(value); }
};

struct AxRefEqual {
    bool operator()(AXUIElementRef first, AXUIElementRef second) const {
        return CFEqual(first, second);
    }
};

struct AxCandidate {
    AXUIElementRef element = nullptr;
    CGRect bounds{};
    std::string role;
    std::string label;
    uint32_t actions = 0;
    size_t order = 0;
};

struct AxTraversal {
    std::vector<AxCandidate> candidates;
    size_t visited = 0;
    size_t queued = 0;
    size_t duplicates = 0;
    size_t childErrors = 0;
    size_t childTimeouts = 0;
    bool nodeLimit = false;
    bool queueLimit = false;
    bool timeLimit = false;
    ~AxTraversal() {
        for (const auto &candidate : candidates) CFRelease(candidate.element);
    }
};

struct ScopedAxRef {
    AXUIElementRef value = nullptr;
    explicit ScopedAxRef(AXUIElementRef initial = nullptr) : value(initial) {}
    ~ScopedAxRef() { if (value) CFRelease(value); }
    ScopedAxRef(const ScopedAxRef &) = delete;
    ScopedAxRef &operator=(const ScopedAxRef &) = delete;
    AXUIElementRef release() {
        AXUIElementRef result = value;
        value = nullptr;
        return result;
    }
    void reset(AXUIElementRef next) {
        if (value) CFRelease(value);
        value = next;
    }
};

size_t axTreeQuality(const AxTraversal &tree) {
    size_t score = tree.candidates.size();
    for (const auto &candidate : tree.candidates) {
        if (candidate.actions & JC_ELEMENT_PRESS) score += 4;
        if (!candidate.label.empty()) score += 1;
    }
    return score;
}

bool axTreeLooksSparse(const AxTraversal &tree) {
    if (tree.candidates.size() < 24) return true;
    bool hasWebArea = false;
    for (const auto &candidate : tree.candidates) {
        if (candidate.role == "AXWebArea") hasWebArea = true;
    }
    // A populated native tree normally has no AXWebArea. Probe the optional
    // manual-accessibility capability once for these trees too: some web shells
    // expose many chrome/sidebar nodes while omitting their actual web content.
    return !hasWebArea;
}

struct ManualAccessibilityOutcome {
    bool supported = false;
    bool setterAttempted = false;
    AXError error = kAXErrorAttributeUnsupported;
};

bool transientAxProbeError(AXError error) {
    return error == kAXErrorCannotComplete || error == kAXErrorAPIDisabled
        || error == kAXErrorFailure || error == kAXErrorInvalidUIElement;
}

jc_ax_snapshot::OncePerProcess<ManualAccessibilityOutcome> manualAccessibilityByProcess;

ManualAccessibilityOutcome enableManualAccessibilityOnce(MacSession *session,
                                                          bool *enabledNow) {
    if (enabledNow) *enabledNow = false;
    auto result = manualAccessibilityByProcess.getOrCreate(session->pid,
        processInstanceId(session->process), monotonicMillis(), [&]() {
            ManualAccessibilityOutcome outcome;
            AXUIElementRef app = AXUIElementCreateApplication(session->pid);
            if (app) {
                AXUIElementSetMessagingTimeout(app, 0.3);
                Boolean settable = false;
                outcome.error = AXUIElementIsAttributeSettable(
                    app, CFSTR("AXManualAccessibility"), &settable);
                outcome.supported = outcome.error == kAXErrorSuccess && settable;
                if (outcome.supported) {
                    outcome.setterAttempted = true;
                    outcome.error = AXUIElementSetAttributeValue(
                        app, CFSTR("AXManualAccessibility"), kCFBooleanTrue);
                }
                CFRelease(app);
            } else {
                outcome.error = kAXErrorInvalidUIElement;
            }
            return outcome;
        }, [](const ManualAccessibilityOutcome &outcome) {
            return outcome.setterAttempted || !transientAxProbeError(outcome.error);
        }, 3, 1000);
    if (enabledNow) *enabledNow = result.second && result.first.setterAttempted
        && result.first.error == kAXErrorSuccess;
    return result.first;
}

bool axBounds(AXUIElementRef element, CGRect *bounds, AXError *result = nullptr) {
    if (!bounds) return false;
    CFTypeRef rawPosition = nullptr;
    CFTypeRef rawSize = nullptr;
    AXError positionError = AXUIElementCopyAttributeValue(
        element, kAXPositionAttribute, &rawPosition);
    AXError sizeError = AXUIElementCopyAttributeValue(element, kAXSizeAttribute, &rawSize);
    if (result) *result = positionError != kAXErrorSuccess ? positionError : sizeError;
    CGPoint position{};
    CGSize size{};
    bool readable = positionError == kAXErrorSuccess && sizeError == kAXErrorSuccess
        && rawPosition && rawSize
        && CFGetTypeID(rawPosition) == AXValueGetTypeID()
        && CFGetTypeID(rawSize) == AXValueGetTypeID()
        && AXValueGetType(static_cast<AXValueRef>(rawPosition)) == kAXValueTypeCGPoint
        && AXValueGetType(static_cast<AXValueRef>(rawSize)) == kAXValueTypeCGSize
        && AXValueGetValue(static_cast<AXValueRef>(rawPosition), kAXValueTypeCGPoint, &position)
        && AXValueGetValue(static_cast<AXValueRef>(rawSize), kAXValueTypeCGSize, &size)
        && std::isfinite(position.x) && std::isfinite(position.y)
        && std::isfinite(size.width) && std::isfinite(size.height)
        && size.width > 0 && size.height > 0;
    if (rawPosition) CFRelease(rawPosition);
    if (rawSize) CFRelease(rawSize);
    if (readable) *bounds = CGRectMake(position.x, position.y, size.width, size.height);
    return readable;
}

int32_t preDispatchAxStatus(AXError error) {
    if (error == kAXErrorAPIDisabled) return JC_ACTION_DENIED;
    if (error == kAXErrorInvalidUIElement) return JC_ACTION_STALE_FRAME;
    if (error == kAXErrorAttributeUnsupported || error == kAXErrorActionUnsupported
            || error == kAXErrorNotImplemented || error == kAXErrorNoValue)
        return JC_ACTION_UNSUPPORTED;
    return JC_ACTION_FAILED;
}

NSString *axStringAttribute(AXUIElementRef element, CFStringRef attribute) {
    CFTypeRef raw = nullptr;
    AXError error = AXUIElementCopyAttributeValue(element, attribute, &raw);
    NSString *value = error == kAXErrorSuccess && raw
        && CFGetTypeID(raw) == CFStringGetTypeID() ? [(__bridge NSString *)raw copy] : @"";
    if (raw) CFRelease(raw);
    return value;
}

bool axLabelForDispatch(AXUIElementRef element, const std::string &role,
                        std::string *label, AXError *result) {
    if (!label || !result) return false;
    label->clear();
    *result = kAXErrorSuccess;
    const CFStringRef attributes[] = {kAXTitleAttribute, kAXDescriptionAttribute};
    for (CFStringRef attribute : attributes) {
        CFTypeRef raw = nullptr;
        AXError error = AXUIElementCopyAttributeValue(element, attribute, &raw);
        if (error != kAXErrorSuccess && error != kAXErrorAttributeUnsupported
                && error != kAXErrorNoValue) {
            if (raw) CFRelease(raw);
            *result = error;
            return false;
        }
        if (error == kAXErrorSuccess && raw && CFGetTypeID(raw) == CFStringGetTypeID()) {
            NSString *value = (__bridge NSString *)raw;
            if (value.length) *label = value.UTF8String ?: "";
        }
        if (raw) CFRelease(raw);
        if (!label->empty()) break;
    }
    if (role.find("Text") != std::string::npos
            || role.find("Search") != std::string::npos) label->clear();
    return true;
}

uint32_t axActions(AXUIElementRef element, AXError *result = nullptr) {
    CFArrayRef actions = nullptr;
    AXError error = AXUIElementCopyActionNames(element, &actions);
    if (result) *result = error;
    uint32_t bits = 0;
    if (error == kAXErrorSuccess && actions) {
        CFRange all = CFRangeMake(0, CFArrayGetCount(actions));
        if (CFArrayContainsValue(actions, all, kAXPressAction)) bits |= JC_ELEMENT_PRESS;
        if (CFArrayContainsValue(actions, all, kAXIncrementAction)
                || CFArrayContainsValue(actions, all, kAXDecrementAction))
            bits |= JC_ELEMENT_SCROLL;
    }
    if (actions) CFRelease(actions);
    Boolean canWrite = false;
    if (AXUIElementIsAttributeSettable(element, kAXSelectedTextAttribute, &canWrite)
            == kAXErrorSuccess && canWrite) bits |= JC_ELEMENT_WRITE;
    return bits;
}

AXUIElementRef uniqueAxWindow(pid_t pid, const jc_desktop_window &target,
                              AXError *result) {
    if (result) *result = kAXErrorInvalidUIElement;
    AXUIElementRef app = AXUIElementCreateApplication(pid);
    if (!app) return nullptr;
    AXUIElementSetMessagingTimeout(app, 0.35);
    CFTypeRef rawWindows = nullptr;
    AXError error = AXUIElementCopyAttributeValue(app, kAXWindowsAttribute, &rawWindows);
    CFRelease(app);
    if (result) *result = error;
    if (error != kAXErrorSuccess || !rawWindows
            || CFGetTypeID(rawWindows) != CFArrayGetTypeID()) {
        if (rawWindows) CFRelease(rawWindows);
        return nullptr;
    }
    AXUIElementRef selected = nullptr;
    int matches = 0;
    CFArrayRef windows = static_cast<CFArrayRef>(rawWindows);
    CFIndex count = CFArrayGetCount(windows);
    if (count > 64) {
        CFRelease(rawWindows);
        return nullptr;
    }
    auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(1500);
    CFIndex index = 0;
    for (; index < count && std::chrono::steady_clock::now() < deadline; ++index) {
        CFTypeRef candidate = CFArrayGetValueAtIndex(windows, index);
        if (!candidate || CFGetTypeID(candidate) != AXUIElementGetTypeID()) continue;
        AXUIElementSetMessagingTimeout(
            static_cast<AXUIElementRef>(const_cast<void *>(candidate)), 0.15);
        CGRect bounds{};
        if (axBounds(static_cast<AXUIElementRef>(const_cast<void *>(candidate)), &bounds)
                && sameBounds(bounds, target)) {
            selected = static_cast<AXUIElementRef>(const_cast<void *>(candidate));
            ++matches;
        }
    }
    AXUIElementRef retained = index == count && matches == 1
        ? static_cast<AXUIElementRef>(CFRetain(selected)) : nullptr;
    CFRelease(rawWindows);
    if (!retained && result) *result = kAXErrorInvalidUIElement;
    return retained;
}

int axCandidatePriority(const AxCandidate &candidate) {
    int priority = 0;
    if (candidate.actions & JC_ELEMENT_PRESS) priority += 100;
    if (candidate.actions & JC_ELEMENT_WRITE) priority += 80;
    if (candidate.actions & JC_ELEMENT_SCROLL) priority += 60;
    if (!candidate.label.empty()) priority += 20;
    if (candidate.role == "AXWindow" || candidate.role == "AXGroup") priority -= 10;
    return priority;
}

void collectAxTree(AXUIElementRef selected, const jc_desktop_window &target,
                   AxTraversal *out,
                   std::chrono::milliseconds budget = std::chrono::milliseconds(2200)) {
    constexpr size_t kMaxVisited = 768;
    constexpr size_t kMaxQueued = 1536;
    auto deadline = std::chrono::steady_clock::now() + budget;
    std::vector<AXUIElementRef> queue;
    queue.reserve(kMaxQueued);
    std::unordered_set<AXUIElementRef, AxRefHash, AxRefEqual> seen;
    queue.push_back(static_cast<AXUIElementRef>(CFRetain(selected)));
    seen.insert(selected);
    CGRect targetBounds = CGRectMake(target.x, target.y, target.width, target.height);
    for (size_t cursor = 0; cursor < queue.size(); ++cursor) {
        if (cursor == kMaxVisited) { out->nodeLimit = true; break; }
        if (std::chrono::steady_clock::now() >= deadline) { out->timeLimit = true; break; }
        AXUIElementRef element = queue[cursor];
        ++out->visited;
        AXUIElementSetMessagingTimeout(element, 0.15);
        NSString *role = axStringAttribute(element, kAXRoleAttribute);
        CGRect bounds{};
        if (axBounds(element, &bounds)) {
            CGRect clipped = CGRectIntersection(bounds, targetBounds);
            if (!CGRectIsNull(clipped) && clipped.size.width >= 1 && clipped.size.height >= 1) {
                NSString *title = axStringAttribute(element, kAXTitleAttribute);
                NSString *description = title.length ? @""
                    : axStringAttribute(element, kAXDescriptionAttribute);
                bool textRole = [role localizedCaseInsensitiveContainsString:@"text"]
                    || [role localizedCaseInsensitiveContainsString:@"search"];
                NSString *label = textRole ? @"" : title.length ? title : description;
                uint32_t actions = axActions(element);
                if (actions || role.length || label.length) {
                    AxCandidate candidate;
                    candidate.element = static_cast<AXUIElementRef>(CFRetain(element));
                    candidate.bounds = bounds;
                    candidate.role = role.UTF8String ?: "";
                    candidate.label = label.UTF8String ?: "";
                    candidate.actions = actions;
                    candidate.order = out->visited;
                    out->candidates.push_back(std::move(candidate));
                }
            }
        }
        const CFStringRef childAttributes[] = {
            kAXChildrenAttribute, CFSTR("AXVisibleChildren"),
            CFSTR("AXChildrenInNavigationOrder")
        };
        for (CFStringRef attribute : childAttributes) {
            if (std::chrono::steady_clock::now() >= deadline) {
                out->timeLimit = true;
                break;
            }
            CFTypeRef rawChildren = nullptr;
            AXError error = AXUIElementCopyAttributeValue(element, attribute, &rawChildren);
            if (error == kAXErrorCannotComplete) ++out->childTimeouts;
            else if (error != kAXErrorSuccess && error != kAXErrorAttributeUnsupported
                    && error != kAXErrorNoValue) ++out->childErrors;
            if (error == kAXErrorSuccess && rawChildren
                    && CFGetTypeID(rawChildren) == CFArrayGetTypeID()) {
                CFArrayRef children = static_cast<CFArrayRef>(rawChildren);
                for (CFIndex child = 0; child < CFArrayGetCount(children); ++child) {
                    CFTypeRef value = CFArrayGetValueAtIndex(children, child);
                    if (!value || CFGetTypeID(value) != AXUIElementGetTypeID()) continue;
                    AXUIElementRef candidate = static_cast<AXUIElementRef>(const_cast<void *>(value));
                    if (!seen.insert(candidate).second) { ++out->duplicates; continue; }
                    if (queue.size() == kMaxQueued) {
                        out->queueLimit = true;
                        break;
                    }
                    queue.push_back(static_cast<AXUIElementRef>(CFRetain(candidate)));
                }
            }
            if (rawChildren) CFRelease(rawChildren);
            if (out->queueLimit) break;
        }
        if (out->queueLimit || out->timeLimit) break;
    }
    out->queued = queue.size();
    for (AXUIElementRef element : queue) CFRelease(element);
    std::stable_sort(out->candidates.begin(), out->candidates.end(),
        [](const AxCandidate &first, const AxCandidate &second) {
            return axCandidatePriority(first) > axCandidatePriority(second);
        });
}

bool sameAxBounds(CGRect first, CGRect second) {
    return std::abs(first.origin.x - second.origin.x) <= 2
        && std::abs(first.origin.y - second.origin.y) <= 2
        && std::abs(first.size.width - second.size.width) <= 2
        && std::abs(first.size.height - second.size.height) <= 2;
}

bool axRoiMatches(const AxSnapshotElement &snapshot,
                  const std::vector<uint8_t> &latest, int32_t stride,
                  int32_t frameWidth, int32_t frameHeight) {
    return jc_ax_snapshot::regionEquals(
        snapshot.roiPixels.data(), snapshot.roiPixels.size(),
        latest.data(), latest.size(), frameWidth, frameHeight, stride,
        {snapshot.frameX, snapshot.frameY, snapshot.frameWidth, snapshot.frameHeight});
}

bool axElementWindowMatches(AXUIElementRef element, pid_t pid,
                            const jc_desktop_window &target,
                            AXError *result = nullptr) {
    if (result) *result = kAXErrorSuccess;
    CFTypeRef rawWindow = nullptr;
    AXError error = AXUIElementCopyAttributeValue(element, kAXWindowAttribute, &rawWindow);
    if (error != kAXErrorSuccess || !rawWindow
            || CFGetTypeID(rawWindow) != AXUIElementGetTypeID()) {
        if (rawWindow) CFRelease(rawWindow);
        if (result) *result = error == kAXErrorSuccess ? kAXErrorInvalidUIElement : error;
        return false;
    }
    AXUIElementRef window = static_cast<AXUIElementRef>(const_cast<void *>(rawWindow));
    AXUIElementSetMessagingTimeout(window, 0.25);
    CGRect bounds{};
    AXError boundsError = kAXErrorSuccess;
    bool readable = axBounds(window, &bounds, &boundsError);
    AXError selectedError = kAXErrorSuccess;
    ScopedAxRef selected(uniqueAxWindow(pid, target, &selectedError));
    bool sameHandle = selected.value && CFEqual(window, selected.value);
    CFRelease(rawWindow);
    if (!readable) {
        if (result) *result = boundsError == kAXErrorSuccess
            ? kAXErrorInvalidUIElement : boundsError;
        return false;
    }
    if (!selected.value) {
        if (result) *result = selectedError;
        return false;
    }
    if (!sameHandle) {
        if (result) *result = kAXErrorInvalidUIElement;
        return false;
    }
    return sameBounds(bounds, target);
}

// AX hit testing commonly returns a label inside a button. Walk only the
// target application's ancestor chain and require an advertised action before
// dispatching a press. A failed AXPress cannot prove that no click happened.
AXUIElementRef pressableAncestor(AXUIElementRef hit, pid_t pid,
                                 AXError *result = nullptr) {
    if (result) *result = kAXErrorActionUnsupported;
    AXUIElementRef current = hit;
    for (int depth = 0; current && depth < 8; ++depth) {
        AXUIElementSetMessagingTimeout(current, 0.25);
        pid_t owner = 0;
        AXError ownerError = AXUIElementGetPid(current, &owner);
        if (ownerError != kAXErrorSuccess || owner != pid) {
            if (result) *result = ownerError != kAXErrorSuccess
                ? ownerError : kAXErrorInvalidUIElement;
            CFRelease(current);
            return nullptr;
        }
        CFArrayRef actions = nullptr;
        AXError listed = AXUIElementCopyActionNames(current, &actions);
        if (listed == kAXErrorAPIDisabled || listed == kAXErrorInvalidUIElement
                || listed == kAXErrorCannotComplete || listed == kAXErrorFailure) {
            if (result) *result = listed;
            if (actions) CFRelease(actions);
            CFRelease(current);
            return nullptr;
        }
        bool pressable = listed == kAXErrorSuccess && actions
            && CFArrayContainsValue(actions, CFRangeMake(0, CFArrayGetCount(actions)), kAXPressAction);
        if (actions) CFRelease(actions);
        if (pressable) {
            if (result) *result = kAXErrorSuccess;
            return current;
        }
        CFTypeRef parent = nullptr;
        AXError traversed = AXUIElementCopyAttributeValue(current, kAXParentAttribute, &parent);
        CFRelease(current);
        if (traversed == kAXErrorAPIDisabled || traversed == kAXErrorInvalidUIElement
                || traversed == kAXErrorCannotComplete || traversed == kAXErrorFailure) {
            if (result) *result = traversed;
            if (parent) CFRelease(parent);
            return nullptr;
        }
        current = traversed == kAXErrorSuccess && parent
            && CFGetTypeID(parent) == AXUIElementGetTypeID()
                ? static_cast<AXUIElementRef>(const_cast<void *>(parent)) : nullptr;
        if (!current && parent) CFRelease(parent);
    }
    if (current) CFRelease(current);
    return nullptr;
}

int32_t backgroundAction(MacSession *session, const jc_desktop_window &target,
                         const jc_desktop_action *action,
                         uint64_t expectedRevision, CGPoint point,
                         char *detail, uint32_t detailCapacity) {
    pid_t pid = session->pid;
    auto frameStillUsable = [&]() {
        if (!session->isOriginalProcess()) return false;
        std::lock_guard guard(session->state);
        return !session->stopping && session->available
            && jc_ax_snapshot::frameUsable(
                session->captureFailed, session->latest.size(), session->frameWidth,
                session->frameHeight, session->frameStride)
            && session->generation == action->generation
            && session->contentRevision == expectedRevision
            && session->current.window_id == target.window_id
            && session->current.x == target.x && session->current.y == target.y
            && session->current.width == target.width
            && session->current.height == target.height
            && session->timestamp != 0
            && nowMillis() <= session->timestamp + kFrameFreshness.count();
    };
    if (!AXIsProcessTrusted()) {
        writeDetail(detail, detailCapacity, @"Accessibility permission is required for background input");
        return JC_ACTION_DENIED;
    }
    AXUIElementRef app = AXUIElementCreateApplication(pid);
    if (!app) return JC_ACTION_FAILED;
    AXUIElementSetMessagingTimeout(app, 2);
    AXUIElementRef element = nullptr;
    AXError error = kAXErrorFailure;
    if (action->kind == JC_ACTION_CLICK) {
        if (action->button != 1 || action->clicks != 1) {
            CFRelease(app);
            writeDetail(detail, detailCapacity,
                @"Background Accessibility press supports one left click only; foreground input is required");
            return JC_ACTION_UNSUPPORTED;
        }
        error = AXUIElementCopyElementAtPosition(app, point.x, point.y, &element);
        CFRelease(app);
        if (error != kAXErrorSuccess || !element) {
            if (element) CFRelease(element);
            writeDetail(detail, detailCapacity,
                [NSString stringWithFormat:@"Accessibility hit test found no usable target (AX error %d); no input was sent", error]);
            return error == kAXErrorSuccess ? JC_ACTION_UNSUPPORTED : preDispatchAxStatus(error);
        }
        AXUIElementSetMessagingTimeout(element, 0.25);
        AXError windowError = kAXErrorSuccess;
        if (!axElementWindowMatches(element, pid, target, &windowError)) {
            CFRelease(element);
            writeDetail(detail, detailCapacity,
                [NSString stringWithFormat:@"Accessibility hit belongs to a different or unidentifiable window (AX error %d); no input was sent", windowError]);
            return windowError == kAXErrorSuccess
                ? JC_ACTION_STALE_FRAME : preDispatchAxStatus(windowError);
        }
        AXError ancestorError = kAXErrorSuccess;
        AXUIElementRef pressable = pressableAncestor(element, pid, &ancestorError);
        if (!pressable) {
            writeDetail(detail, detailCapacity,
                [NSString stringWithFormat:@"Target and its nearby Accessibility ancestors do not advertise AXPress (AX error %d); no input was sent", ancestorError]);
            return preDispatchAxStatus(ancestorError);
        }
        if (!frameStillUsable()) {
            CFRelease(pressable);
            writeDetail(detail, detailCapacity,
                @"Capture frame or target changed before AXPress; observe again");
            return JC_ACTION_STALE_FRAME;
        }
        error = AXUIElementPerformAction(pressable, kAXPressAction);
        CFRelease(pressable);
        writeDetail(detail, detailCapacity, error == kAXErrorSuccess
            ? @"Accessibility press was accepted; target effect is not confirmed"
            : [NSString stringWithFormat:@"Accessibility press returned AX error %d after dispatch; target effect is uncertain", error]);
        return error == kAXErrorSuccess ? JC_ACTION_ACCEPTED : JC_ACTION_UNKNOWN;
    }
    CFTypeRef rawFocused = nullptr;
    if (action->kind == JC_ACTION_TYPE) {
        // A frame-relative TYPE must never write into an unrelated focused field.
        AXUIElementRef atPoint = nullptr;
        error = AXUIElementCopyElementAtPosition(app, point.x, point.y, &atPoint);
        rawFocused = atPoint;
    } else {
        error = AXUIElementCopyAttributeValue(app, kAXFocusedUIElementAttribute, &rawFocused);
    }
    CFRelease(app);
    if (error != kAXErrorSuccess || !rawFocused) {
        if (rawFocused) CFRelease(rawFocused);
        writeDetail(detail, detailCapacity, action->kind == JC_ACTION_TYPE
            ? @"No Accessibility text element exists at the frame point"
            : @"Target has no focused Accessibility element");
        return error == kAXErrorSuccess ? JC_ACTION_UNSUPPORTED : preDispatchAxStatus(error);
    }
    element = static_cast<AXUIElementRef>(const_cast<void *>(rawFocused));
    AXUIElementSetMessagingTimeout(element, 0.25);
    AXError windowError = kAXErrorSuccess;
    if (!axElementWindowMatches(element, pid, target, &windowError)) {
        CFRelease(element);
        writeDetail(detail, detailCapacity,
            [NSString stringWithFormat:@"Accessibility input target is not proven to be in the selected window (AX error %d); no input was sent", windowError]);
        return windowError == kAXErrorSuccess
            ? JC_ACTION_STALE_FRAME : preDispatchAxStatus(windowError);
    }
    if (action->kind == JC_ACTION_TYPE) {
        pid_t elementPid = 0;
        Boolean canInsert = false;
        AXError pidError = AXUIElementGetPid(element, &elementPid);
        AXError writeError = pidError == kAXErrorSuccess && elementPid == pid
            ? AXUIElementIsAttributeSettable(element, kAXSelectedTextAttribute, &canInsert)
            : kAXErrorInvalidUIElement;
        if (pidError != kAXErrorSuccess || elementPid != pid
                || writeError != kAXErrorSuccess || !canInsert) {
            CFRelease(element);
            writeDetail(detail, detailCapacity,
                [NSString stringWithFormat:@"The frame point is not a writable text control in the target process (AX PID error %d, write error %d)", pidError, writeError]);
            if (pidError != kAXErrorSuccess) return preDispatchAxStatus(pidError);
            if (elementPid != pid) return JC_ACTION_STALE_FRAME;
            return writeError == kAXErrorSuccess
                ? JC_ACTION_UNSUPPORTED : preDispatchAxStatus(writeError);
        }
        if (!action->text_utf8 || action->text_bytes == 0) {
            CFRelease(element);
            return JC_ACTION_VERIFIED;
        }
        NSString *value = [[NSString alloc] initWithBytes:action->text_utf8
            length:action->text_bytes encoding:NSUTF8StringEncoding];
        if (!value) {
            CFRelease(element);
            writeDetail(detail, detailCapacity, @"Input is not valid UTF-8");
            return JC_ACTION_FAILED;
        }
        CFTypeRef before = nullptr;
        AXUIElementCopyAttributeValue(element, kAXValueAttribute, &before);
        if (!frameStillUsable()) {
            if (before) CFRelease(before);
            CFRelease(element);
            writeDetail(detail, detailCapacity,
                @"Capture frame or target changed before Accessibility text insertion");
            return JC_ACTION_STALE_FRAME;
        }
        error = AXUIElementSetAttributeValue(element, kAXSelectedTextAttribute, (__bridge CFStringRef)value);
        CFTypeRef after = nullptr;
        if (error == kAXErrorSuccess) AXUIElementCopyAttributeValue(element, kAXValueAttribute, &after);
        CFRelease(element);
        if (error == kAXErrorSuccess) {
            bool verified = before && after && CFGetTypeID(before) == CFStringGetTypeID()
                && CFGetTypeID(after) == CFStringGetTypeID()
                && !CFEqual(before, after)
                && [(__bridge NSString *)after containsString:value];
            if (before) CFRelease(before);
            if (after) CFRelease(after);
            if (verified) {
                writeDetail(detail, detailCapacity, @"Accessibility text insertion verified by value readback");
                return JC_ACTION_VERIFIED;
            }
            writeDetail(detail, detailCapacity, @"Accessibility insertion accepted; resulting text is not confirmed");
            return JC_ACTION_ACCEPTED;
        }
        if (before) CFRelease(before);
        if (after) CFRelease(after);
        writeDetail(detail, detailCapacity,
            [NSString stringWithFormat:@"Accessibility insertion returned AX error %d after dispatch; target effect is uncertain", error]);
        return JC_ACTION_UNKNOWN;
    }
    if (action->kind == JC_ACTION_KEY) {
        NSString *key = action->text_utf8 && action->text_bytes
            ? [[NSString alloc] initWithBytes:action->text_utf8 length:action->text_bytes encoding:NSUTF8StringEncoding] : @"";
        NSString *upper = key.uppercaseString;
        CFStringRef axAction = nil;
        if ([upper isEqualToString:@"ENTER"] || [upper isEqualToString:@"RETURN"]) axAction = kAXConfirmAction;
        if ([upper isEqualToString:@"ESCAPE"] || [upper isEqualToString:@"ESC"]) axAction = kAXCancelAction;
        if (axAction && !frameStillUsable()) {
            CFRelease(element);
            writeDetail(detail, detailCapacity,
                @"Capture frame or target changed before Accessibility key action");
            return JC_ACTION_STALE_FRAME;
        }
        error = axAction ? AXUIElementPerformAction(element, axAction) : kAXErrorActionUnsupported;
        CFRelease(element);
        if (!axAction) {
            writeDetail(detail, detailCapacity, @"This key has no supported background Accessibility action");
            return JC_ACTION_UNSUPPORTED;
        }
        writeDetail(detail, detailCapacity, error == kAXErrorSuccess
            ? @"Accessibility key action was accepted; target effect is not confirmed"
            : [NSString stringWithFormat:@"Accessibility key action returned AX error %d after dispatch; target effect is uncertain", error]);
        return error == kAXErrorSuccess ? JC_ACTION_ACCEPTED : JC_ACTION_UNKNOWN;
    }
    CFRelease(element);
    writeDetail(detail, detailCapacity, @"Background scrolling is unavailable for this target");
    return JC_ACTION_UNSUPPORTED;
}

CGKeyCode keyCodeFor(NSString *key) {
    NSString *upper = key.uppercaseString;
    static NSDictionary<NSString *, NSNumber *> *letters;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        letters = @{@"A": @0, @"B": @11, @"C": @8, @"D": @2, @"E": @14,
                    @"F": @3, @"G": @5, @"H": @4, @"I": @34, @"J": @38,
                    @"K": @40, @"L": @37, @"M": @46, @"N": @45, @"O": @31,
                    @"P": @35, @"Q": @12, @"R": @15, @"S": @1, @"T": @17,
                    @"U": @32, @"V": @9, @"W": @13, @"X": @7, @"Y": @16,
                    @"Z": @6, @"0": @29, @"1": @18, @"2": @19, @"3": @20,
                    @"4": @21, @"5": @23, @"6": @22, @"7": @26, @"8": @28,
                    @"9": @25};
    });
    NSNumber *mapped = letters[upper];
    if (mapped) return mapped.unsignedShortValue;
    if ([upper isEqualToString:@"ENTER"] || [upper isEqualToString:@"RETURN"]) return 36;
    if ([upper isEqualToString:@"TAB"]) return 48;
    if ([upper isEqualToString:@"SPACE"]) return 49;
    if ([upper isEqualToString:@"BACKSPACE"] || [upper isEqualToString:@"DELETE"]) return 51;
    if ([upper isEqualToString:@"ESCAPE"] || [upper isEqualToString:@"ESC"]) return 53;
    if ([upper isEqualToString:@"LEFT"]) return 123;
    if ([upper isEqualToString:@"RIGHT"]) return 124;
    if ([upper isEqualToString:@"DOWN"]) return 125;
    if ([upper isEqualToString:@"UP"]) return 126;
    return UINT16_MAX;
}

bool parseKey(NSString *input, CGKeyCode *code, CGEventFlags *flags) {
    NSArray<NSString *> *parts = [input componentsSeparatedByString:@"+"];
    if (parts.count == 0) return false;
    *flags = 0;
    for (NSUInteger index = 0; index + 1 < parts.count; ++index) {
        NSString *part = [parts[index] stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceCharacterSet].uppercaseString;
        if ([part isEqualToString:@"CTRL"] || [part isEqualToString:@"CONTROL"]) *flags |= kCGEventFlagMaskControl;
        else if ([part isEqualToString:@"ALT"] || [part isEqualToString:@"OPTION"]) *flags |= kCGEventFlagMaskAlternate;
        else if ([part isEqualToString:@"SHIFT"]) *flags |= kCGEventFlagMaskShift;
        else if ([part isEqualToString:@"CMD"] || [part isEqualToString:@"COMMAND"]
                 || [part isEqualToString:@"META"]) *flags |= kCGEventFlagMaskCommand;
        else return false;
    }
    NSString *name = [parts.lastObject stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceCharacterSet];
    *code = keyCodeFor(name);
    return *code != UINT16_MAX;
}

bool sameBounds(CGRect bounds, const jc_desktop_window &expected) {
    return std::abs(bounds.origin.x - expected.x) <= 2
        && std::abs(bounds.origin.y - expected.y) <= 2
        && std::abs(bounds.size.width - expected.width) <= 2
        && std::abs(bounds.size.height - expected.height) <= 2;
}

bool isForegroundApplicationWindowBounds(CGRect bounds) {
    return bounds.size.width >= 24 && bounds.size.height >= 24;
}

uint64_t frontmostWindowIdFor(pid_t pid) {
    CFArrayRef raw = CGWindowListCopyWindowInfo(kCGWindowListOptionOnScreenOnly, kCGNullWindowID);
    if (!raw) return 0;
    NSArray<NSDictionary *> *windows = CFBridgingRelease(raw);
    for (NSDictionary *entry in windows) {
        NSNumber *owner = entry[(id)kCGWindowOwnerPID];
        NSNumber *number = entry[(id)kCGWindowNumber];
        NSNumber *layer = entry[(id)kCGWindowLayer];
        NSNumber *alpha = entry[(id)kCGWindowAlpha];
        NSDictionary *boundsValue = entry[(id)kCGWindowBounds];
        CGRect bounds{};
        if (!boundsValue || !CGRectMakeWithDictionaryRepresentation(
                (__bridge CFDictionaryRef)boundsValue, &bounds)
                || !isForegroundApplicationWindowBounds(bounds)) continue;
        if (owner.intValue == pid && number && (!layer || layer.intValue >= 0)
                && (!alpha || alpha.doubleValue > 0.01)) return number.unsignedLongLongValue;
    }
    return 0;
}

// There is no public AX attribute containing a CGWindowID. Only raise a window
// when its CG bounds uniquely identify one AX window in the same process.
bool raiseWindowByCgId(pid_t pid, uint64_t windowId) {
    if (!AXIsProcessTrusted() || !windowId) return false;
    CFArrayRef raw = CGWindowListCopyWindowInfo(kCGWindowListOptionIncludingWindow,
                                                static_cast<CGWindowID>(windowId));
    if (!raw) return false;
    NSArray<NSDictionary *> *infos = CFBridgingRelease(raw);
    NSDictionary *info = infos.firstObject;
    NSNumber *owner = info[(id)kCGWindowOwnerPID];
    NSDictionary *boundsValue = info[(id)kCGWindowBounds];
    CGRect expected{};
    if (!owner || owner.intValue != pid || !boundsValue
            || !CGRectMakeWithDictionaryRepresentation((__bridge CFDictionaryRef)boundsValue, &expected))
        return false;
    AXUIElementRef app = AXUIElementCreateApplication(pid);
    if (!app) return false;
    AXUIElementSetMessagingTimeout(app, 0.3);
    CFTypeRef rawWindows = nullptr;
    AXError listed = AXUIElementCopyAttributeValue(app, kAXWindowsAttribute, &rawWindows);
    CFRelease(app);
    if (listed != kAXErrorSuccess || !rawWindows
            || CFGetTypeID(rawWindows) != CFArrayGetTypeID()) {
        if (rawWindows) CFRelease(rawWindows);
        return false;
    }
    AXUIElementRef match = nullptr;
    int matches = 0;
    CFArrayRef windows = static_cast<CFArrayRef>(rawWindows);
    CFIndex count = CFArrayGetCount(windows);
    if (count > 64) {
        CFRelease(rawWindows);
        return false;
    }
    auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(1500);
    CFIndex index = 0;
    for (; index < count && std::chrono::steady_clock::now() < deadline; ++index) {
        CFTypeRef candidate = CFArrayGetValueAtIndex(windows, index);
        if (!candidate || CFGetTypeID(candidate) != AXUIElementGetTypeID()) continue;
        AXUIElementRef window = static_cast<AXUIElementRef>(const_cast<void *>(candidate));
        AXUIElementSetMessagingTimeout(window, 0.15);
        CGRect bounds{};
        bool same = axBounds(window, &bounds)
            && std::abs(bounds.origin.x - expected.origin.x) <= 2
            && std::abs(bounds.origin.y - expected.origin.y) <= 2
            && std::abs(bounds.size.width - expected.size.width) <= 2
            && std::abs(bounds.size.height - expected.size.height) <= 2;
        if (same) {
            match = window;
            ++matches;
        }
    }
    bool raised = index == count && matches == 1
        && AXUIElementPerformAction(match, kAXRaiseAction) == kAXErrorSuccess;
    CFRelease(rawWindows);
    return raised;
}

AXError setMainWindowIfSupported(MacSession *session,
                                 const jc_desktop_window &target,
                                 bool *attempted) {
    if (attempted) *attempted = false;
    AXError windowError = kAXErrorSuccess;
    ScopedAxRef window(uniqueAxWindow(session->pid, target, &windowError));
    if (!window.value) return windowError;
    AXUIElementSetMessagingTimeout(window.value, 0.25);
    Boolean settable = false;
    AXError error = AXUIElementIsAttributeSettable(
        window.value, kAXMainAttribute, &settable);
    if (error == kAXErrorAttributeUnsupported || (error == kAXErrorSuccess && !settable))
        return kAXErrorActionUnsupported;
    if (error != kAXErrorSuccess) return error;
    if (attempted) *attempted = true;
    return AXUIElementSetAttributeValue(window.value, kAXMainAttribute, kCFBooleanTrue);
}

// Report only a bounded public bundle identifier, never a window title or path.
NSString *publicBlockerBundleIdentifier(pid_t pid) {
    NSString *bundle = pid > 0
        ? [NSRunningApplication runningApplicationWithProcessIdentifier:pid].bundleIdentifier : nil;
    if (!bundle.length || bundle.length > 96) return @"unavailable";
    for (NSUInteger index = 0; index < bundle.length; ++index) {
        unichar character = [bundle characterAtIndex:index];
        if (!((character >= 'a' && character <= 'z')
                || (character >= 'A' && character <= 'Z')
                || (character >= '0' && character <= '9')
                || character == '.' || character == '_' || character == '-'))
            return @"unavailable";
    }
    return bundle;
}

struct PointerReceiverDiagnostic {
    bool collected = false;
    uint64_t windowId = 0;
    CGRect primaryBounds{};
};

struct PointerReceiverDiagnosticState {
    std::mutex mutex;
    std::condition_variable ready;
    bool completed = false;
    bool expired = false;
    PointerReceiverDiagnostic result;
};

// Read-only mouse-down hit testing; never sends input.
// The queued block owns its state and never refers to a session or caller stack.
PointerReceiverDiagnostic pointerReceiverDiagnostic(CGPoint point) {
    if (!std::isfinite(point.x) || !std::isfinite(point.y) || NSThread.isMainThread)
        return {};
    auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(300);
    auto state = std::make_shared<PointerReceiverDiagnosticState>();
    dispatch_async(dispatch_get_main_queue(), ^{
        {
            std::lock_guard<std::mutex> lock(state->mutex);
            if (state->expired || std::chrono::steady_clock::now() >= deadline) return;
        }
        PointerReceiverDiagnostic result;
        @autoreleasepool {
            @try {
                CGRect primary = CGDisplayBounds(CGMainDisplayID());
                if (std::isfinite(primary.origin.x) && std::isfinite(primary.origin.y)
                        && std::isfinite(primary.size.width) && std::isfinite(primary.size.height)
                        && primary.origin.x == 0 && primary.origin.y == 0
                        && primary.size.width > 0 && primary.size.height > 0) {
                    double cocoaY = primary.size.height - point.y;
                    if (std::isfinite(cocoaY)) {
                        NSInteger number = [NSWindow windowNumberAtPoint:NSMakePoint(point.x, cocoaY)
                            belowWindowWithWindowNumber:0];
                        if (number >= 0) {
                            result.collected = true;
                            result.windowId = static_cast<uint64_t>(number);
                            result.primaryBounds = primary;
                        }
                    }
                }
            } @catch (NSException *exception) {
                (void)exception;
                // An unavailable query is not evidence of a pointer receiver.
            }
        }
        {
            std::lock_guard<std::mutex> lock(state->mutex);
            if (state->expired || std::chrono::steady_clock::now() >= deadline) return;
            state->result = result;
            state->completed = true;
        }
        state->ready.notify_one();
    });
    std::unique_lock<std::mutex> lock(state->mutex);
    if (!state->ready.wait_until(lock, deadline, [&] { return state->completed; })
            || std::chrono::steady_clock::now() >= deadline) {
        state->expired = true;
        return {};
    }
    return state->result;
}

// CGWindowListCopyWindowInfo is ordered from front to back. Check both the
// selected window identity and the window that will actually receive a pointer
// event at the requested screen point. A PID match alone is insufficient when
// the application has multiple windows or a foreign overlay covers the point.
bool targetWindowReady(pid_t pid, const jc_desktop_window &expected,
                       CGPoint point, bool pointerAction, NSString **reason) {
    CFArrayRef raw = CGWindowListCopyWindowInfo(kCGWindowListOptionOnScreenOnly, kCGNullWindowID);
    if (!raw) {
        if (reason) *reason = @"Could not inspect the foreground window stack";
        return false;
    }
    NSArray<NSDictionary *> *windows = CFBridgingRelease(raw);
    bool found = false;
    bool correctBounds = false;
    bool firstAppWindowIsTarget = false;
    bool sawAppWindow = false;
    uint64_t topAtPoint = 0;
    pid_t topPid = 0;
    int topLayer = 0;
    CGRect topBounds{};
    int topSharingState = -1;
    bool topTitleEmpty = true;
    bool topDockSignature = false;
    uint64_t firstAppWindowId = 0;
    for (NSDictionary *entry in windows) {
        NSNumber *number = entry[(id)kCGWindowNumber];
        NSNumber *owner = entry[(id)kCGWindowOwnerPID];
        NSDictionary *boundsValue = entry[(id)kCGWindowBounds];
        if (!number || !owner || !boundsValue) continue;
        CGRect bounds{};
        if (!CGRectMakeWithDictionaryRepresentation((__bridge CFDictionaryRef)boundsValue, &bounds)
                || bounds.size.width < 1 || bounds.size.height < 1) continue;
        NSNumber *alpha = entry[(id)kCGWindowAlpha];
        if (alpha && alpha.doubleValue <= 0.01) continue;
        NSNumber *layer = entry[(id)kCGWindowLayer];
        if (layer && layer.intValue < 0) continue;
        uint64_t id = number.unsignedLongLongValue;
        if (pointerAction && !topAtPoint && CGRectContainsPoint(bounds, point)) {
            topAtPoint = id;
            topPid = owner.intValue;
            topLayer = layer ? layer.intValue : 0;
            topBounds = bounds;
            NSNumber *sharing = entry[(__bridge NSString *)kCGWindowSharingState];
            topSharingState = sharing && sharing.intValue >= 0 && sharing.intValue <= 2
                ? sharing.intValue : -1;
            NSObject *title = entry[(__bridge NSString *)kCGWindowName];
            topTitleEmpty = !title || ([title isKindOfClass:NSString.class]
                && [(NSString *)title length] == 0);
            topDockSignature = [layer isKindOfClass:NSNumber.class] && layer.doubleValue == 20
                && [sharing isKindOfClass:NSNumber.class] && sharing.doubleValue == 1
                && [title isKindOfClass:NSString.class] && [(NSString *)title length] > 0;
        }
        if (owner.intValue == pid && !sawAppWindow
                && isForegroundApplicationWindowBounds(bounds)) {
            sawAppWindow = true;
            firstAppWindowId = id;
            firstAppWindowIsTarget = id == expected.window_id;
        }
        if (id == expected.window_id && owner.intValue == pid) {
            found = true;
            correctBounds = sameBounds(bounds, expected);
        }
    }
    if (!found || !correctBounds) {
        if (reason) *reason = @"Target window moved, changed size, or disappeared; observe again";
        return false;
    }
    if ((pointerAction && topAtPoint != expected.window_id)
            || (!pointerAction && !firstAppWindowIsTarget)) {
        PointerReceiverDiagnostic receiver;
        NSString *blockerBundle = nil;
        if (pointerAction) {
            // Never skip a CG window: only a fresh unrestricted mouse-down hit
            // can resolve this exact observed Dock overlay against the target.
            receiver = pointerReceiverDiagnostic(point);
            blockerBundle = publicBlockerBundleIdentifier(topPid);
            if (firstAppWindowIsTarget && topDockSignature
                    && [blockerBundle isEqualToString:@"com.apple.dock"]
                    && receiver.collected && receiver.windowId != 0
                    && receiver.windowId == expected.window_id
                    && CGRectEqualToRect(topBounds, receiver.primaryBounds)
                    && CGRectContainsPoint(receiver.primaryBounds, point)
                    && NSWorkspace.sharedWorkspace.frontmostApplication.processIdentifier == pid)
                return true;
        }
        if (reason && pointerAction) {
            bool finiteBounds = std::isfinite(topBounds.origin.x)
                && std::isfinite(topBounds.origin.y)
                && std::isfinite(topBounds.size.width)
                && std::isfinite(topBounds.size.height);
            NSString *bounds = finiteBounds && topAtPoint
                ? [NSString stringWithFormat:@"%.9g,%.9g,%.9g,%.9g",
                    topBounds.origin.x, topBounds.origin.y,
                    topBounds.size.width, topBounds.size.height] : @"unavailable";
            *reason = [NSString stringWithFormat:
                @"Another window is in front of the selected target; no input was sent "
                 "(blockerPid=%d blockerWindowId=%llu blockerLayer=%d targetWindowId=%llu "
                 "blockerBundle=%@ blockerRect=%@ blockerSharingState=%d blockerTitleEmpty=%d "
                 "systemAxHitCollected=0 receiverWindowId=%llu receiverCollected=%d)",
                topPid, static_cast<unsigned long long>(topAtPoint), topLayer,
                static_cast<unsigned long long>(expected.window_id),
                blockerBundle, bounds, topSharingState, topTitleEmpty ? 1 : 0,
                static_cast<unsigned long long>(receiver.windowId), receiver.collected ? 1 : 0];
        } else if (reason) {
            *reason = [NSString stringWithFormat:
                @"A different window of the target application is first; no input was sent "
                 "(frontAppWindowId=%llu targetWindowId=%llu)",
                static_cast<unsigned long long>(firstAppWindowId),
                static_cast<unsigned long long>(expected.window_id)];
        }
        return false;
    }
    return true;
}

bool targetWindowExists(pid_t pid, const jc_desktop_window &expected,
                        NSString **reason) {
    CFArrayRef raw = CGWindowListCopyWindowInfo(kCGWindowListOptionOnScreenOnly, kCGNullWindowID);
    if (!raw) {
        if (reason) *reason = @"Could not inspect the selected window stack";
        return false;
    }
    NSArray<NSDictionary *> *windows = CFBridgingRelease(raw);
    for (NSDictionary *entry in windows) {
        NSNumber *number = entry[(id)kCGWindowNumber];
        NSNumber *owner = entry[(id)kCGWindowOwnerPID];
        if (!number || number.unsignedLongLongValue != expected.window_id
                || !owner || owner.intValue != pid) continue;
        NSDictionary *boundsValue = entry[(id)kCGWindowBounds];
        CGRect bounds{};
        NSNumber *alpha = entry[(id)kCGWindowAlpha];
        NSNumber *layer = entry[(id)kCGWindowLayer];
        if (boundsValue && CGRectMakeWithDictionaryRepresentation(
                (__bridge CFDictionaryRef)boundsValue, &bounds)
                && sameBounds(bounds, expected)
                && (!alpha || alpha.doubleValue > 0.01)
                && (!layer || layer.intValue >= 0)) return true;
        if (reason) *reason = @"Selected CG window changed bounds or visibility";
        return false;
    }
    if (reason) *reason = @"Selected CG window is no longer on screen";
    return false;
}

bool focusedWindowMatches(pid_t pid, const jc_desktop_window &expected,
                          bool *known = nullptr) {
    if (known) *known = false;
    AXUIElementRef app = AXUIElementCreateApplication(pid);
    if (!app) return false;
    AXUIElementSetMessagingTimeout(app, 0.5);
    CFTypeRef rawWindow = nullptr;
    AXError focused = AXUIElementCopyAttributeValue(app, kAXFocusedWindowAttribute, &rawWindow);
    CFRelease(app);
    if (focused == kAXErrorAttributeUnsupported || focused == kAXErrorNoValue
            || (focused == kAXErrorSuccess && !rawWindow)) {
        if (rawWindow) CFRelease(rawWindow);
        return true; // Mouse readiness can use the selected CG window and point.
    }
    if (focused != kAXErrorSuccess || !rawWindow
            || CFGetTypeID(rawWindow) != AXUIElementGetTypeID()) {
        if (rawWindow) CFRelease(rawWindow);
        return false;
    }
    ScopedAxRef focusedWindow(static_cast<AXUIElementRef>(const_cast<void *>(rawWindow)));
    AXUIElementSetMessagingTimeout(focusedWindow.value, 0.25);
    CGRect focusedBounds{};
    if (!axBounds(focusedWindow.value, &focusedBounds)) return false;
    ScopedAxRef selected(uniqueAxWindow(pid, expected, nullptr));
    if (!selected.value) return false;
    if (known) *known = true;
    return sameBounds(focusedBounds, expected)
        && CFEqual(focusedWindow.value, selected.value);
}

struct FocusRestore {
    NSRunningApplication * __strong previous;
    pid_t target;
    jc_desktop_window selectedWindow;
    bool sentInput = false;
    ~FocusRestore() {
        if (!previous || previous.processIdentifier == target || previous.isTerminated) return;
        if (sentInput) std::this_thread::sleep_for(std::chrono::milliseconds(80));
        NSRunningApplication *front = NSWorkspace.sharedWorkspace.frontmostApplication;
        // A user may have switched away while an input sequence was running.
        if (front && front.processIdentifier == target
                && (!sentInput
                    || targetWindowReady(target, selectedWindow, CGPointZero, false, nullptr)))
            [previous activateWithOptions:0];
    }
};

void restoreForegroundLease(MacSession *session, bool allowTargetAppOnly) {
    if (!session->foregroundLease) return;
    NSRunningApplication *previous = session->previousFrontmost;
    uint64_t priorWindow = session->previousWindowId;
    uint64_t selectedWindow = session->leasedWindowId;
    jc_desktop_window selected = session->leasedWindow;
    bool sentInput = session->leaseSentInput;
    session->previousFrontmost = nil;
    session->previousWindowId = 0;
    session->leasedWindowId = 0;
    std::memset(&session->leasedWindow, 0, sizeof(session->leasedWindow));
    session->foregroundLease = false;
    session->leaseSentInput = false;
    if (!previous || previous.isTerminated) return;
    if (sentInput) std::this_thread::sleep_for(std::chrono::milliseconds(80));
    NSRunningApplication *front = NSWorkspace.sharedWorkspace.frontmostApplication;
    if (!front || front.processIdentifier != session->pid
            || (!allowTargetAppOnly && (selected.window_id != selectedWindow
                || !targetWindowReady(session->pid, selected, CGPointZero, false, nullptr)))) return;
    if (previous.processIdentifier != session->pid) {
        [previous activateWithOptions:0];
        if (NSWorkspace.sharedWorkspace.frontmostApplication.processIdentifier
                == previous.processIdentifier && priorWindow
                && frontmostWindowIdFor(previous.processIdentifier) != priorWindow)
            raiseWindowByCgId(previous.processIdentifier, priorWindow);
    } else if (priorWindow && priorWindow != selectedWindow) {
        raiseWindowByCgId(session->pid, priorWindow);
    }
}

struct RestoreAfterForegroundAction {
    MacSession *session;
    bool active;
    ~RestoreAfterForegroundAction() {
        if (active) restoreForegroundLease(session);
    }
};

int32_t foregroundAction(MacSession *session, const jc_desktop_window &target,
                         const jc_desktop_action *action, CGPoint point,
                         char *detail, uint32_t detailCapacity) {
    pid_t pid = session->pid;
    if (!AXIsProcessTrusted()) {
        writeDetail(detail, detailCapacity, @"Accessibility permission is required for foreground input");
        return JC_ACTION_DENIED;
    }
    if (!CGPreflightPostEventAccess()) {
        writeDetail(detail, detailCapacity, @"Post Event permission is required for foreground input");
        return JC_ACTION_DENIED;
    }
    NSString *value = action->text_utf8 && action->text_bytes
        ? [[NSString alloc] initWithBytes:action->text_utf8 length:action->text_bytes encoding:NSUTF8StringEncoding]
        : @"";
    if ((action->kind == JC_ACTION_TYPE || action->kind == JC_ACTION_KEY) && !value) {
        writeDetail(detail, detailCapacity, @"Input is not valid UTF-8");
        return JC_ACTION_FAILED;
    }
    CGKeyCode keyCode = 0;
    CGEventFlags keyFlags = 0;
    if (action->kind == JC_ACTION_KEY && !parseKey(value, &keyCode, &keyFlags)) {
        writeDetail(detail, detailCapacity, @"The requested key is not supported");
        return JC_ACTION_UNSUPPORTED;
    }
    if (action->kind == JC_ACTION_TYPE && value.length == 0) return JC_ACTION_VERIFIED;
    if (action->kind != JC_ACTION_CLICK && action->kind != JC_ACTION_SCROLL
            && action->kind != JC_ACTION_KEY && action->kind != JC_ACTION_TYPE) {
        writeDetail(detail, detailCapacity, @"Unsupported foreground action kind");
        return JC_ACTION_UNSUPPORTED;
    }
    FocusRestore restore{session->foregroundLease ? nil
        : NSWorkspace.sharedWorkspace.frontmostApplication, pid, target};
    if (session->foregroundLease) {
        if (NSWorkspace.sharedWorkspace.frontmostApplication.processIdentifier != pid) {
            writeDetail(detail, detailCapacity,
                @"Foreground ownership changed after preparation; no input was sent");
            return JC_ACTION_FAILED;
        }
    } else {
        NSRunningApplication *app = [NSRunningApplication runningApplicationWithProcessIdentifier:pid];
        if (!app || ![app activateWithOptions:0]) {
            writeDetail(detail, detailCapacity, @"Target application could not be activated");
            return JC_ACTION_FAILED;
        }
        for (int index = 0; index < 20
                && NSWorkspace.sharedWorkspace.frontmostApplication.processIdentifier != pid; ++index) {
            std::this_thread::sleep_for(std::chrono::milliseconds(50));
        }
        if (NSWorkspace.sharedWorkspace.frontmostApplication.processIdentifier != pid) {
            writeDetail(detail, detailCapacity, @"Target application did not gain focus");
            return JC_ACTION_FAILED;
        }
    }
    uint64_t inputRevision;
    {
        std::lock_guard guard(session->state);
        if (session->stopping || !session->available
                || !jc_ax_snapshot::frameUsable(
                    session->captureFailed, session->latest.size(), session->frameWidth,
                    session->frameHeight, session->frameStride)
                || session->generation != action->generation
                || session->current.window_id != target.window_id
                || session->current.x != target.x || session->current.y != target.y
                || session->current.width != target.width
                || session->current.height != target.height
                || session->timestamp == 0
                || nowMillis() > session->timestamp + kFrameFreshness.count()
                || (action->content_revision != 0
                    && session->contentRevision != action->content_revision)) {
            writeDetail(detail, detailCapacity,
                @"Target frame changed while bringing the window forward; observe again before input");
            return JC_ACTION_STALE_FRAME;
        }
        inputRevision = session->contentRevision;
    }
    NSString *windowReason = nil;
    CGPoint center = CGPointMake(target.x + target.width / 2.0,
                                 target.y + target.height / 2.0);
    bool keyAction = action->kind == JC_ACTION_KEY;
    bool focusedWindowKnown = false;
    if (!targetWindowReady(pid, target, keyAction ? center : point, !keyAction, &windowReason)
            || (keyAction && (!focusedWindowMatches(pid, target, &focusedWindowKnown)
                || (!focusedWindowKnown && frontmostWindowIdFor(pid) != target.window_id)))) {
        writeDetail(detail, detailCapacity,
            windowReason ?: @"A different target window has keyboard focus; no input was sent");
        return JC_ACTION_FAILED;
    }

    auto stillReady = [&](bool pointer, bool sentInput) -> bool {
        if (NSWorkspace.sharedWorkspace.frontmostApplication.processIdentifier != pid) {
            writeDetail(detail, detailCapacity, sentInput
                ? @"Focus changed after input was sent; remaining events were not sent"
                : @"Target application lost focus before input; no event was sent");
            return false;
        }
        NSString *reason = nil;
        bool focusKnown = false;
        if (!targetWindowReady(pid, target, pointer ? point : center, pointer, &reason)
                || (!pointer && (!focusedWindowMatches(pid, target, &focusKnown)
                    || (!focusKnown && frontmostWindowIdFor(pid) != target.window_id)))) {
            writeDetail(detail, detailCapacity, sentInput
                ? @"Target window changed after input was sent; remaining events were not sent"
                : (reason ?: @"A different target window has keyboard focus; no event was sent"));
            return false;
        }
        {
            std::lock_guard guard(session->state);
            if (session->stopping || !session->available
                    || !jc_ax_snapshot::frameUsable(
                        session->captureFailed, session->latest.size(), session->frameWidth,
                        session->frameHeight, session->frameStride)
                    || session->generation != action->generation
                    || session->current.window_id != target.window_id
                    || session->current.x != target.x || session->current.y != target.y
                    || session->current.width != target.width
                    || session->current.height != target.height
                    || session->timestamp == 0
                    || nowMillis() > session->timestamp + kFrameFreshness.count()) {
                writeDetail(detail, detailCapacity, sentInput
                    ? @"Capture frame failed or target changed after input; remaining events were not sent"
                    : @"Capture frame failed or target changed before input; no event was sent");
                return false;
            }
        }
        return true;
    };

    {
        std::lock_guard guard(session->state);
        if (session->stopping || !session->available
                || !jc_ax_snapshot::frameUsable(
                    session->captureFailed, session->latest.size(), session->frameWidth,
                    session->frameHeight, session->frameStride)
                || session->generation != action->generation
                || session->current.window_id != target.window_id
                || session->current.x != target.x || session->current.y != target.y
                || session->current.width != target.width
                || session->current.height != target.height
                || session->timestamp == 0
                || nowMillis() > session->timestamp + kFrameFreshness.count()
                || session->contentRevision != inputRevision) {
            writeDetail(detail, detailCapacity,
                @"Target pixels changed before the first input event; observe again");
            return JC_ACTION_STALE_FRAME;
        }
    }

    if (action->kind == JC_ACTION_CLICK) {
        bool right = action->button == 3;
        bool middle = action->button == 2;
        CGMouseButton button = right ? kCGMouseButtonRight : middle ? kCGMouseButtonCenter : kCGMouseButtonLeft;
        CGEventType downType = right ? kCGEventRightMouseDown : middle ? kCGEventOtherMouseDown : kCGEventLeftMouseDown;
        CGEventType upType = right ? kCGEventRightMouseUp : middle ? kCGEventOtherMouseUp : kCGEventLeftMouseUp;
        bool posted = false;
        for (int index = 0; index < action->clicks; ++index) {
            if (posted) {
                std::this_thread::sleep_for(std::chrono::milliseconds(50));
                std::lock_guard guard(session->state);
                if (session->contentRevision != inputRevision) {
                    writeDetail(detail, detailCapacity,
                        @"The target frame changed after the first click; the remaining click was not sent");
                    return JC_ACTION_UNKNOWN;
                }
            }
            if (!stillReady(true, posted)) return posted ? JC_ACTION_UNKNOWN : JC_ACTION_FAILED;
            CGEventRef down = CGEventCreateMouseEvent(nullptr, downType, point, button);
            CGEventRef up = CGEventCreateMouseEvent(nullptr, upType, point, button);
            if (!down || !up) {
                if (down) CFRelease(down);
                if (up) CFRelease(up);
                writeDetail(detail, detailCapacity, posted
                    ? @"A later click event could not be created; prior input may have taken effect"
                    : @"Click event could not be created; no input was sent");
                return posted ? JC_ACTION_UNKNOWN : JC_ACTION_FAILED;
            }
            CGEventSetIntegerValueField(down, kCGMouseEventClickState, index + 1);
            CGEventSetIntegerValueField(up, kCGMouseEventClickState, index + 1);
            CGEventPost(kCGHIDEventTap, down);
            CGEventPost(kCGHIDEventTap, up);
            posted = true;
            restore.sentInput = true;
            if (session->foregroundLease) session->leaseSentInput = true;
            CFRelease(down);
            CFRelease(up);
        }
    } else if (action->kind == JC_ACTION_SCROLL) {
        if (!stillReady(true, false)) return JC_ACTION_FAILED;
        CGEventRef event = CGEventCreateScrollWheelEvent(nullptr, kCGScrollEventUnitLine, 1, -action->amount);
        if (!event) {
            writeDetail(detail, detailCapacity, @"Scroll event could not be created; no input was sent");
            return JC_ACTION_FAILED;
        }
        CGEventSetLocation(event, point);
        CGEventPost(kCGHIDEventTap, event);
        restore.sentInput = true;
        if (session->foregroundLease) session->leaseSentInput = true;
        CFRelease(event);
    } else if (action->kind == JC_ACTION_KEY) {
        if (!stillReady(false, false)) return JC_ACTION_FAILED;
        CGEventRef down = CGEventCreateKeyboardEvent(nullptr, keyCode, true);
        CGEventRef up = CGEventCreateKeyboardEvent(nullptr, keyCode, false);
        if (!down || !up) {
            if (down) CFRelease(down);
            if (up) CFRelease(up);
            writeDetail(detail, detailCapacity, @"Key event could not be created; no input was sent");
            return JC_ACTION_FAILED;
        }
        CGEventSetFlags(down, keyFlags);
        CGEventSetFlags(up, keyFlags);
        CGEventPost(kCGHIDEventTap, down);
        CGEventPost(kCGHIDEventTap, up);
        restore.sentInput = true;
        if (session->foregroundLease) session->leaseSentInput = true;
        CFRelease(down);
        CFRelease(up);
    } else if (action->kind == JC_ACTION_TYPE) {
        if (!stillReady(true, false)) return JC_ACTION_FAILED;
        CGEventRef focusDown = CGEventCreateMouseEvent(nullptr, kCGEventLeftMouseDown,
                                                       point, kCGMouseButtonLeft);
        CGEventRef focusUp = CGEventCreateMouseEvent(nullptr, kCGEventLeftMouseUp,
                                                     point, kCGMouseButtonLeft);
        if (!focusDown || !focusUp) {
            if (focusDown) CFRelease(focusDown);
            if (focusUp) CFRelease(focusUp);
            writeDetail(detail, detailCapacity, @"Focus click could not be created; no input was sent");
            return JC_ACTION_FAILED;
        }
        CGEventPost(kCGHIDEventTap, focusDown);
        CGEventPost(kCGHIDEventTap, focusUp);
        restore.sentInput = true;
        if (session->foregroundLease) session->leaseSentInput = true;
        CFRelease(focusDown);
        CFRelease(focusUp);
        std::this_thread::sleep_for(std::chrono::milliseconds(50));
        if (!stillReady(true, true) || !focusedWindowMatches(pid, target)) {
            writeDetail(detail, detailCapacity,
                @"Focus click was sent, but the target window could not be confirmed before typing");
            return JC_ACTION_UNKNOWN;
        }
        for (NSUInteger index = 0; index < value.length;) {
            if (!stillReady(true, true) || !focusedWindowMatches(pid, target))
                return JC_ACTION_UNKNOWN;
            NSRange range = [value rangeOfComposedCharacterSequenceAtIndex:index];
            NSString *part = [value substringWithRange:range];
            index = NSMaxRange(range);
            CGEventRef down = CGEventCreateKeyboardEvent(nullptr, 0, true);
            CGEventRef up = CGEventCreateKeyboardEvent(nullptr, 0, false);
            if (!down || !up) {
                if (down) CFRelease(down);
                if (up) CFRelease(up);
                writeDetail(detail, detailCapacity,
                    @"A later text event could not be created; prior input may have taken effect");
                return JC_ACTION_UNKNOWN;
            }
            std::vector<UniChar> characters(part.length);
            [part getCharacters:characters.data() range:NSMakeRange(0, part.length)];
            CGEventKeyboardSetUnicodeString(down, part.length, characters.data());
            CGEventKeyboardSetUnicodeString(up, part.length, characters.data());
            CGEventPost(kCGHIDEventTap, down);
            CGEventPost(kCGHIDEventTap, up);
            CFRelease(down);
            CFRelease(up);
        }
    }
    // Reaching this boundary means the entire requested sequence was posted to
    // the platform. Earlier partial-post or focus-loss branches remain UNKNOWN.
    writeDetail(detail, detailCapacity, @"All input events were posted; target effect is not confirmed");
    return JC_ACTION_ACCEPTED;
}

} // namespace

extern "C" {

int32_t jc_desktop_api_version(void) { return JC_DESKTOP_ABI_VERSION; }

int32_t jc_desktop_process_application_id(uint64_t process_id,
                                          char *application_id_utf8, uint32_t capacity) {
    @autoreleasepool {
        if (!application_id_utf8 || capacity == 0 || process_id == 0
                || process_id > static_cast<uint64_t>(std::numeric_limits<pid_t>::max())) return -1;
        application_id_utf8[0] = 0;
        NSRunningApplication *app = [NSRunningApplication
                runningApplicationWithProcessIdentifier:static_cast<pid_t>(process_id)];
        if (!app.bundleIdentifier.length) return -2;
        copyUtf8(application_id_utf8, capacity, app.bundleIdentifier);
        return application_id_utf8[0] ? 0 : -2;
    }
}

int32_t jc_desktop_resolve_application_id(const char *application_utf8,
                                          char *application_id_utf8, uint32_t capacity) {
    @autoreleasepool {
        if (!application_id_utf8 || capacity == 0) return -1;
        application_id_utf8[0] = 0;
        NSString *requested = validRequestedApplication(application_utf8);
        if (!requested) return -1;
        NSURL *url = resolvedApplicationBundle(requested);
        if (!url) return -3;
        NSBundle *bundle = [NSBundle bundleWithURL:url];
        copyUtf8(application_id_utf8, capacity, bundle.bundleIdentifier);
        return application_id_utf8[0] ? 0 : -3;
    }
}

int32_t jc_desktop_list_applications(char *catalog_utf8, uint32_t capacity,
                                      uint32_t *required_bytes) {
    if (!required_bytes || (!catalog_utf8 && capacity != 0)) return -1;
    *required_bytes = 0;
    if (catalog_utf8 && capacity) catalog_utf8[0] = '\0';
    @autoreleasepool {
        @try {
            try {
                return jc_application_catalog::copyJson(installedApplicationCatalog(),
                        catalog_utf8, capacity, required_bytes);
            } catch (...) {
                return -3;
            }
        } @catch (NSException *exception) {
            (void)exception;
            return -3;
        }
    }
}

int32_t jc_desktop_probe(uint32_t *capabilities, char *detail_utf8, uint32_t detail_capacity) {
    @autoreleasepool {
        if (!capabilities) return -1;
        *capabilities = 0;
        if (!supportedOS()) {
            writeDetail(detail_utf8, detail_capacity, @"macOS 14 or newer is required");
            return -4;
        }
        bool capture = CGPreflightScreenCaptureAccess();
        bool accessibility = AXIsProcessTrusted();
        bool postEvent = CGPreflightPostEventAccess();
        if (postEvent) postEventRequestIssued.store(false);
        if (capture) *capabilities |= JC_CAP_CAPTURE;
        if (accessibility) *capabilities |= JC_CAP_SEMANTIC_INPUT;
        if (accessibility && postEvent) *capabilities |= JC_CAP_FOREGROUND_INPUT;
        NSMutableArray<NSString *> *missing = [NSMutableArray array];
        if (!capture) [missing addObject:@"屏幕与系统音频录制"];
        if (!accessibility) [missing addObject:@"辅助功能"];
        if (!postEvent) [missing addObject:@"输入事件发送（Post Event）"];
        writeDetail(detail_utf8, detail_capacity, missing.count
            ? [NSString stringWithFormat:@"缺少系统权限：%@；点击开启或继续授权可逐项处理",
                [missing componentsJoinedByString:@"、"]]
            : @"屏幕采集、辅助功能和输入事件发送权限已就绪");
        return 0;
    }
}

int32_t jc_desktop_request_permissions(uint32_t *capabilities, char *detail_utf8,
                                        uint32_t detail_capacity) {
    @autoreleasepool {
        if (!capabilities) return -1;
        if (supportedOS() && !CGPreflightScreenCaptureAccess()) {
            CGRequestScreenCaptureAccess();
            if (!CGPreflightScreenCaptureAccess()) {
                bool opened = openPrivacySettings(@"Privacy_ScreenCapture");
                int32_t result = jc_desktop_probe(capabilities, detail_utf8, detail_capacity);
                writeDetail(detail_utf8, detail_capacity, opened
                    ? @"已尝试打开系统设置。请在“隐私与安全性 → 屏幕与系统音频录制”允许当前运行 JavaClaw 的应用；从 IDE 启动时可能显示 IDE 或 java。返回应用后会自动复查；若未继续可点击“检查并继续授权”，必要时重启应用。"
                    : @"无法自动打开系统设置。请前往“隐私与安全性 → 屏幕与系统音频录制”，允许当前运行 JavaClaw 的应用，然后重新检查。");
                return result;
            }
            // The settings UI advances to the next permission after observing
            // this grant. Keep each native request to one authorization step.
            return jc_desktop_probe(capabilities, detail_utf8, detail_capacity);
        }
        if (supportedOS() && !AXIsProcessTrusted()) {
            // Opening the pane directly avoids showing the AX system alert and
            // immediately replacing it with System Settings.
            bool opened = openPrivacySettings(@"Privacy_Accessibility");
            int32_t result = jc_desktop_probe(capabilities, detail_utf8, detail_capacity);
            writeDetail(detail_utf8, detail_capacity, opened
                ? @"已尝试打开系统设置。请在“隐私与安全性 → 辅助功能”允许当前运行 JavaClaw 的应用；从 IDE 启动时可能显示 IDE 或 java。若列表中没有该应用，请手动添加。返回应用后会自动复查。"
                : @"无法自动打开系统设置。请前往“隐私与安全性 → 辅助功能”，允许当前运行 JavaClaw 的应用，然后重新检查。");
            return result;
        }
        if (supportedOS() && !CGPreflightPostEventAccess()) {
            if (!postEventRequestIssued.exchange(true)) {
                // This API can show a system prompt. Do not immediately open
                // System Settings over it; a later explicit retry opens the pane.
                CGRequestPostEventAccess();
                int32_t result = jc_desktop_probe(capabilities, detail_utf8, detail_capacity);
                if (!CGPreflightPostEventAccess()) {
                    writeDetail(detail_utf8, detail_capacity,
                        @"已请求系统的发送输入事件（Post Event）授权。请处理系统提示；若未出现提示或仍待授权，请点击“检查并继续授权”打开辅助功能设置。");
                }
                return result;
            }
            // macOS exposes no stable, documented Post Event pane link.
            // Event posting is presented to users with Accessibility controls.
            bool opened = openPrivacySettings(@"Privacy_Accessibility");
            int32_t result = jc_desktop_probe(capabilities, detail_utf8, detail_capacity);
            writeDetail(detail_utf8, detail_capacity, opened
                ? @"已尝试打开“隐私与安全性 → 辅助功能”。请按系统提示允许当前运行 JavaClaw 的应用发送键盘与鼠标事件（Post Event）；从 IDE 启动时可能显示 IDE 或 java。若该应用已开启，仍请按系统提示处理并重启应用。返回后会自动复查。"
                : @"输入事件发送（Post Event）权限仍未就绪。请按系统提示在“隐私与安全性 → 辅助功能”检查当前运行 JavaClaw 的应用，然后重新检查。");
            return result;
        }
        return jc_desktop_probe(capabilities, detail_utf8, detail_capacity);
    }
}

int32_t jc_desktop_list_windows(jc_desktop_window *windows, uint32_t capacity, uint32_t *count) {
    @autoreleasepool {
        if (!count || (capacity && !windows)) return -1;
        *count = 0;
        if (!supportedOS() || !CGPreflightScreenCaptureAccess()) return -2;
        NSString *error = nil;
        SCShareableContent *content = shareableContent(&error);
        if (!content) return -3;
        auto order = windowOrder();
        std::vector<std::pair<SCWindow *, uint64_t>> sorted;
        for (SCWindow *window in content.windows) {
            if (!window.owningApplication || window.owningApplication.processID == getpid()
                    || !validWindow(window) || !window.isOnScreen
                    || foreignControlCenterItem(window)) continue;
            ProcessInstance instance;
            if (!processInstance(window.owningApplication.processID, &instance)) continue;
            uint64_t identity = processInstanceId(instance);
            if (identity != 0) sorted.emplace_back(window, identity);
        }
        std::stable_sort(sorted.begin(), sorted.end(), [&](const auto &first, const auto &second) {
            auto a = order.find(first.first.windowID);
            auto b = order.find(second.first.windowID);
            size_t left = a == order.end() ? SIZE_MAX : a->second;
            size_t right = b == order.end() ? SIZE_MAX : b->second;
            return left < right;
        });
        *count = static_cast<uint32_t>(sorted.size());
        for (uint32_t index = 0; index < std::min(capacity, *count); ++index) {
            fillWindow(&windows[index], sorted[index].first, 0, sorted[index].second);
        }
        return 0;
    }
}

int32_t jc_desktop_launch_application(const char *application_utf8, uint64_t *process_id,
                                      char *detail_utf8, uint32_t detail_capacity) {
    @autoreleasepool {
        if (process_id) *process_id = 0;
        if (!process_id || !application_utf8) {
            writeDetail(detail_utf8, detail_capacity, @"Application name and process ID output are required");
            return -1;
        }
        // The caller supplies only a registered application name or bundle ID.
        // Reject paths and command-like multiline input before asking Launch Services.
        NSString *requested = validRequestedApplication(application_utf8);
        if (!requested) {
            writeDetail(detail_utf8, detail_capacity, @"Invalid application name or bundle ID; paths and commands are not accepted");
            return -1;
        }
        if (!supportedOS()) {
            writeDetail(detail_utf8, detail_capacity, @"Launching applications requires macOS 14 or newer");
            return -2;
        }

        NSWorkspace *workspace = NSWorkspace.sharedWorkspace;
        NSURL *applicationURL = resolvedApplicationBundle(requested);
        if (!applicationURL) {
            writeDetail(detail_utf8, detail_capacity, @"Installed application was not found by exact name or bundle ID");
            return -3;
        }
        NSBundle *bundle = [NSBundle bundleWithURL:applicationURL];

        NSWorkspaceOpenConfiguration *configuration = NSWorkspaceOpenConfiguration.configuration;
        configuration.activates = YES;
        configuration.createsNewApplicationInstance = NO;
        configuration.allowsRunningApplicationSubstitution = NO;
        configuration.promptsUserIfNeeded = NO;
        dispatch_semaphore_t completed = dispatch_semaphore_create(0);
        __block NSRunningApplication *running = nil;
        __block NSError *launchError = nil;
        [workspace openApplicationAtURL:applicationURL configuration:configuration
                      completionHandler:^(NSRunningApplication *app, NSError *error) {
            running = app;
            launchError = error;
            dispatch_semaphore_signal(completed);
        }];
        if (dispatch_semaphore_wait(completed, dispatch_time(DISPATCH_TIME_NOW,
                static_cast<int64_t>(std::chrono::duration_cast<std::chrono::nanoseconds>(
                    kApplicationLaunchTimeout).count()))) != 0) {
            writeDetail(detail_utf8, detail_capacity,
                        @"Application launch did not finish within 12 seconds; it may still complete. Discover windows before retrying");
            return -5;
        }
        if (!running) {
            writeDetail(detail_utf8, detail_capacity, launchError.localizedDescription
                        ?: @"The application could not be launched");
            return -6;
        }
        if (running.isTerminated) {
            writeDetail(detail_utf8, detail_capacity,
                        @"Application started but its process exited before verification; discover windows before retrying");
            return -7;
        }
        if (!running.bundleIdentifier.length
                || [running.bundleIdentifier caseInsensitiveCompare:bundle.bundleIdentifier] != NSOrderedSame
                || running.processIdentifier <= 0) {
            writeDetail(detail_utf8, detail_capacity,
                        @"The launched process cannot be matched to the selected application bundle; discover windows before retrying");
            return -7;
        }
        pid_t pid = running.processIdentifier;
        *process_id = static_cast<uint64_t>(pid);
        ProcessInstance instance;
        auto identityDeadline = std::chrono::steady_clock::now() + std::chrono::seconds(2);
        while (!processInstance(pid, &instance)
                && std::chrono::steady_clock::now() < identityDeadline) {
            std::this_thread::sleep_for(std::chrono::milliseconds(50));
        }
        if (!processInstance(pid, &instance)) {
            writeDetail(detail_utf8, detail_capacity,
                        @"Application is running, but its process identity cannot be verified; discover windows before acting");
            return -7;
        }
        if (running.isHidden) [running unhide];
        if (![running activateWithOptions:NSApplicationActivateAllWindows]) {
            writeDetail(detail_utf8, detail_capacity,
                        @"Application is running, but foreground activation failed; discover windows before acting");
            return -8;
        }
        writeDetail(detail_utf8, detail_capacity,
                    @"Application launched or activated; discover its visible window before opening a desktop session");
        return 0;
    }
}

void *jc_desktop_open(uint64_t process_id, uint64_t window_id,
                      uint64_t process_instance_id_expected,
                      char *detail_utf8, uint32_t detail_capacity) {
    @autoreleasepool {
        if (!supportedOS() || !CGPreflightScreenCaptureAccess()) {
            writeDetail(detail_utf8, detail_capacity, @"Screen Recording permission is missing");
            return nullptr;
        }
        if (process_id == 0 || process_id > INT32_MAX || process_id == static_cast<uint64_t>(getpid())
                || window_id == 0 || process_instance_id_expected == 0) {
            writeDetail(detail_utf8, detail_capacity, @"Invalid target process or window");
            return nullptr;
        }
        ProcessInstance instance;
        if (!processInstance(static_cast<pid_t>(process_id), &instance)) {
            writeDetail(detail_utf8, detail_capacity, @"Target process identity is unavailable");
            return nullptr;
        }
        if (processInstanceId(instance) != process_instance_id_expected) {
            writeDetail(detail_utf8, detail_capacity, @"Target process changed after discovery; discover it again");
            return nullptr;
        }
        NSString *error = nil;
        SCShareableContent *content = shareableContent(&error);
        if (!content) {
            writeDetail(detail_utf8, detail_capacity, error);
            return nullptr;
        }
        for (SCWindow *candidate in content.windows) {
            if (windowForProcess(candidate, static_cast<pid_t>(process_id))
                    && candidate.windowID == window_id && foreignControlCenterItem(candidate)) {
                writeDetail(detail_utf8, detail_capacity,
                    @"This is a Control Center menu-bar item for another app, not that app's window; show the app's main window and discover targets again");
                return nullptr;
            }
        }
        SCWindow *window = preferredWindow(content, static_cast<pid_t>(process_id), window_id, true);
        if (!window || window.windowID != window_id) {
            writeDetail(detail_utf8, detail_capacity,
                @"Target window is no longer available or shareable; discover windows again");
            return nullptr;
        }
        if (!window.isOnScreen) {
            writeDetail(detail_utf8, detail_capacity, confirmedMinimized(window)
                ? @"Target window is minimized; restore it and discover windows again"
                : @"Target window is not currently on screen; show it and discover windows again");
            return nullptr;
        }
        ProcessInstance stillRunning;
        if (!processInstance(static_cast<pid_t>(process_id), &stillRunning)
                || !sameProcessInstance(instance, stillRunning)
                || processInstanceId(stillRunning) != process_instance_id_expected) {
            writeDetail(detail_utf8, detail_capacity, @"Target process restarted while opening");
            return nullptr;
        }
        auto *session = new MacSession(static_cast<pid_t>(process_id), window_id, instance);
        fillWindow(&session->current, window, window_id, process_instance_id_expected);
        session->generation = 1;
        session->available = true;
        if (!session->start(window, &error)) {
            writeDetail(detail_utf8, detail_capacity, error);
            delete session;
            return nullptr;
        }
        if (!session->isOriginalProcess()) {
            writeDetail(detail_utf8, detail_capacity, @"Target process restarted while opening");
            delete session;
            return nullptr;
        }
        session->monitor = std::thread([session] {
            while (!session->stopping) {
                std::this_thread::sleep_for(std::chrono::milliseconds(800));
                if (session->stopping) break;
                @autoreleasepool { session->refresh(); }
            }
        });
        writeDetail(detail_utf8, detail_capacity, @"ScreenCaptureKit window stream started");
        return session;
    }
}

int32_t jc_desktop_poll_frame(void *opaque, jc_desktop_frame *frame, uint32_t timeout_millis) {
    if (!opaque || !frame) return -1;
    auto *session = static_cast<MacSession *>(opaque);
    SessionCall call(session);
    if (!session->isOriginalProcess()) return -3;
    std::memset(frame, 0, sizeof(*frame));
    std::unique_lock guard(session->state);
    session->frameReady.wait_for(guard, std::chrono::milliseconds(std::min(timeout_millis, 5000u)), [&] {
        return session->stopping || !session->available || session->captureFailed
            || session->frameSequence > session->deliveredSequence;
    });
    if (session->stopping) return -1;
    if (!session->available) return -3;
    if (session->captureFailed) return -4;
    if (session->frameSequence <= session->deliveredSequence || session->latest.empty()) return 1;
    uint8_t *copy = static_cast<uint8_t *>(std::malloc(session->latest.size()));
    if (!copy) return -5;
    std::memcpy(copy, session->latest.data(), session->latest.size());
    frame->pixels = copy;
    frame->byte_count = session->latest.size();
    frame->width = session->frameWidth;
    frame->height = session->frameHeight;
    frame->stride = session->frameStride;
    frame->timestamp_millis = session->timestamp;
    frame->generation = session->generation;
    frame->window_id = session->current.window_id;
    frame->content_revision = session->contentRevision;
    session->deliveredSequence = session->frameSequence;
    return 0;
}

void jc_desktop_release_frame(jc_desktop_frame *frame) {
    if (!frame) return;
    std::free(frame->pixels);
    std::memset(frame, 0, sizeof(*frame));
}

int32_t jc_desktop_list_elements(void *opaque, uint64_t expected_generation,
                                 uint64_t expected_content_revision,
                                 jc_desktop_element *elements, uint32_t capacity,
                                 uint32_t *count) {
    @autoreleasepool {
        if (!opaque || !count || (capacity > 0 && !elements)) return -1;
        *count = 0;
        auto *session = static_cast<MacSession *>(opaque);
        SessionCall call(session);
        std::lock_guard operation(session->control);
        session->clearAxElements();
        session->axDiagnostics = @"AX listing did not complete";
        if (!session->isOriginalProcess() || !AXIsProcessTrusted()) {
            session->axDiagnostics = @"AX unavailable: process identity or permission changed";
            return -3;
        }
        jc_desktop_window target;
        int32_t frameWidth;
        int32_t frameHeight;
        {
            std::lock_guard guard(session->state);
            if (session->stopping || !session->available
                    || !jc_ax_snapshot::frameUsable(
                        session->captureFailed, session->latest.size(), session->frameWidth,
                        session->frameHeight, session->frameStride)) return -3;
            if (session->generation != expected_generation
                    || (expected_content_revision != 0
                        && session->contentRevision != expected_content_revision)) return 1;
            target = session->current;
            frameWidth = session->frameWidth;
            frameHeight = session->frameHeight;
        }
        if (capacity == 0 || frameWidth <= 0 || frameHeight <= 0
                || target.width <= 0 || target.height <= 0) return 0;
        AXError windowError = kAXErrorSuccess;
        ScopedAxRef selected(uniqueAxWindow(session->pid, target, &windowError));
        if (!selected.value) {
            session->axDiagnostics = [NSString stringWithFormat:
                @"AX selected window unavailable or ambiguous (error=%d)", windowError];
            return 0; // A visual observation is still usable.
        }
        AxTraversal initial;
        collectAxTree(selected.value, target, &initial);
        std::unique_ptr<AxTraversal> expanded;
        AxTraversal *observed = &initial;
        NSString *manual = @"not_needed";
        int manualPolls = 0;
        if (axTreeLooksSparse(initial)) {
            bool enabledNow = false;
            ManualAccessibilityOutcome outcome = enableManualAccessibilityOnce(session, &enabledNow);
            manual = outcome.supported
                ? outcome.error == kAXErrorSuccess ? (enabledNow ? @"enabled" : @"enabled_before")
                    : [NSString stringWithFormat:@"failed_%d", outcome.error]
                : [NSString stringWithFormat:transientAxProbeError(outcome.error)
                    ? @"probe_retryable_%d" : @"unsupported_%d", outcome.error];
            if (enabledNow) {
                auto started = std::chrono::steady_clock::now();
                auto deadline = started + std::chrono::milliseconds(2500);
                size_t originalQuality = axTreeQuality(initial);
                // Some AX providers publish their full tree about two seconds after
                // manual accessibility is enabled. Keep a final scheduled read
                // beyond that delay even when early polls show a partial expansion.
                for (int delayMillis : {150, 400, 800, 1200, 1650, 2050}) {
                    std::this_thread::sleep_until(
                        started + std::chrono::milliseconds(delayMillis));
                    if (std::chrono::steady_clock::now() >= deadline) break;
                    ScopedAxRef polled(uniqueAxWindow(session->pid, target, &windowError));
                    if (!polled.value) {
                        manual = [manual stringByAppendingString:@"_window_unavailable"];
                        break;
                    }
                    auto candidate = std::make_unique<AxTraversal>();
                    auto remaining = std::chrono::duration_cast<std::chrono::milliseconds>(
                        deadline - std::chrono::steady_clock::now());
                    if (remaining <= std::chrono::milliseconds::zero()) break;
                    collectAxTree(polled.value, target, candidate.get(),
                        std::min(remaining, std::chrono::milliseconds(300)));
                    ++manualPolls;
                    if (axTreeQuality(*candidate) >= axTreeQuality(*observed)) {
                        selected.reset(polled.release());
                        expanded = std::move(candidate);
                        observed = expanded.get();
                    }
                }
                if (axTreeQuality(*observed) <= originalQuality)
                    manual = [manual stringByAppendingString:@"_no_expansion_within_2s"];
            }
        }
        constexpr uint32_t kMaxElements = 128;
        constexpr uint64_t kMaxRoiBytes = 1024 * 1024;
        constexpr uint64_t kMaxTotalRoiBytes = 8 * 1024 * 1024;
        uint64_t totalRoiBytes = 0;
        size_t missingRoi = 0;
        size_t selectedCount = std::min({observed->candidates.size(),
                                         static_cast<size_t>(capacity),
                                         static_cast<size_t>(kMaxElements)});
        {
            std::lock_guard guard(session->state);
            if (session->generation != expected_generation
                    || (expected_content_revision != 0
                        && session->contentRevision != expected_content_revision)
                    || session->current.window_id != target.window_id
                    || session->current.x != target.x || session->current.y != target.y
                    || session->current.width != target.width
                    || session->current.height != target.height) {
                session->axDiagnostics = [NSString stringWithFormat:
                    @"AX frame changed during listing; manual=%@; visited=%zu", manual,
                    observed->visited];
                return 1;
            }
            if (session->frameWidth != frameWidth || session->frameHeight != frameHeight
                    || !jc_ax_snapshot::frameUsable(
                        session->captureFailed, session->latest.size(), session->frameWidth,
                        session->frameHeight, session->frameStride)) {
                session->axDiagnostics = @"AX frame buffer changed during listing";
                return 1;
            }
            CGRect targetBounds = CGRectMake(target.x, target.y, target.width, target.height);
            for (size_t index = 0; index < selectedCount && session->nextAxToken != 0; ++index) {
                const AxCandidate &candidate = observed->candidates[index];
                CGRect clipped = CGRectIntersection(candidate.bounds, targetBounds);
                if (CGRectIsNull(clipped) || clipped.size.width < 1 || clipped.size.height < 1)
                    continue;
                jc_desktop_element &out = elements[*count];
                std::memset(&out, 0, sizeof(out));
                out.index = session->nextAxToken++;
                out.x = static_cast<int32_t>(std::clamp(std::lround((clipped.origin.x - target.x)
                    * frameWidth / target.width), 0L, static_cast<long>(frameWidth - 1)));
                out.y = static_cast<int32_t>(std::clamp(std::lround((clipped.origin.y - target.y)
                    * frameHeight / target.height), 0L, static_cast<long>(frameHeight - 1)));
                out.width = static_cast<int32_t>(std::clamp(std::lround(clipped.size.width
                    * frameWidth / target.width), 1L, static_cast<long>(frameWidth - out.x)));
                out.height = static_cast<int32_t>(std::clamp(std::lround(clipped.size.height
                    * frameHeight / target.height), 1L, static_cast<long>(frameHeight - out.y)));
                out.actions = candidate.actions;
                copyUtf8(out.role_utf8, sizeof(out.role_utf8),
                         [NSString stringWithUTF8String:candidate.role.c_str()]);
                copyUtf8(out.label_utf8, sizeof(out.label_utf8),
                         [NSString stringWithUTF8String:candidate.label.c_str()]);
                AxSnapshotElement snapshot{};
                snapshot.token = out.index;
                snapshot.element = static_cast<AXUIElementRef>(CFRetain(candidate.element));
                snapshot.window = static_cast<AXUIElementRef>(CFRetain(selected.value));
                snapshot.windowId = target.window_id;
                snapshot.generation = session->generation;
                snapshot.contentRevision = session->contentRevision;
                snapshot.bounds = candidate.bounds;
                snapshot.role = candidate.role;
                snapshot.label = candidate.label;
                snapshot.actions = candidate.actions;
                snapshot.frameX = out.x;
                snapshot.frameY = out.y;
                snapshot.frameWidth = out.width;
                snapshot.frameHeight = out.height;
                uint64_t rowBytes = static_cast<uint64_t>(out.width) * 4;
                uint64_t bytes = rowBytes * static_cast<uint64_t>(out.height);
                if (bytes <= kMaxRoiBytes && totalRoiBytes + bytes <= kMaxTotalRoiBytes) {
                    snapshot.roiPixels.resize(static_cast<size_t>(bytes));
                    for (int32_t row = 0; row < out.height; ++row) {
                        const uint8_t *source = session->latest.data()
                            + static_cast<size_t>(out.y + row) * session->frameStride
                            + static_cast<size_t>(out.x) * 4;
                        std::memcpy(snapshot.roiPixels.data() + static_cast<size_t>(row) * rowBytes,
                                    source, static_cast<size_t>(rowBytes));
                    }
                    totalRoiBytes += bytes;
                } else {
                    ++missingRoi;
                }
                session->axElements.push_back(std::move(snapshot));
                ++*count;
            }
        }
        session->axDiagnostics = [NSString stringWithFormat:
            @"AX visited=%zu queued=%zu candidates=%zu emitted=%u duplicate=%zu "
             "childErrors=%zu childTimeouts=%zu nodeLimit=%d queueLimit=%d timeLimit=%d "
             "outputLimit=%d roiUncached=%zu manual=%@ manualPolls=%d",
            observed->visited, observed->queued, observed->candidates.size(), *count,
            observed->duplicates, observed->childErrors, observed->childTimeouts,
            observed->nodeLimit, observed->queueLimit, observed->timeLimit,
            observed->candidates.size() > *count, missingRoi, manual, manualPolls];
        return 0;
    }
}

int32_t jc_desktop_current_window(void *opaque, jc_desktop_window *window) {
    if (!opaque || !window) return -1;
    auto *session = static_cast<MacSession *>(opaque);
    SessionCall call(session);
    if (!session->isOriginalProcess()) return -3;
    std::lock_guard guard(session->state);
    if (session->stopping || !session->current.window_id) return -3;
    *window = session->current;
    return 0;
}

int32_t jc_desktop_prepare_foreground(void *opaque, char *detail_utf8,
                                      uint32_t detail_capacity) {
    @autoreleasepool {
        writeDetail(detail_utf8, detail_capacity, @"");
        if (!opaque) return -1;
        auto *session = static_cast<MacSession *>(opaque);
        SessionCall call(session);
        std::lock_guard operation(session->control);
        if (!session->isOriginalProcess()) {
            writeDetail(detail_utf8, detail_capacity, @"Target process exited or restarted");
            return -3;
        }
        if (!AXIsProcessTrusted() || !CGPreflightPostEventAccess()) {
            writeDetail(detail_utf8, detail_capacity,
                @"Accessibility and Post Event permissions are required for foreground input");
            return -4;
        }
        jc_desktop_window target;
        {
            std::lock_guard guard(session->state);
            if (session->stopping || !session->available || !session->current.window_id) {
                writeDetail(detail_utf8, detail_capacity, @"Target window is unavailable");
                return -3;
            }
            target = session->current;
        }
        NSString *reason = nil;
        if (session->foregroundLease) {
            if (session->leasedWindowId == target.window_id
                    && NSWorkspace.sharedWorkspace.frontmostApplication.processIdentifier == session->pid
                    && targetWindowReady(session->pid, target, CGPointZero, false, &reason)
                    && focusedWindowMatches(session->pid, target)) {
                writeDetail(detail_utf8, detail_capacity, @"Selected target window is already in the foreground");
                return 0;
            }
            restoreForegroundLease(session);
            writeDetail(detail_utf8, detail_capacity,
                @"Foreground ownership changed during the prepared action; no new focus change was made");
            return -4;
        }
        NSRunningApplication *previous = NSWorkspace.sharedWorkspace.frontmostApplication;
        session->previousFrontmost = previous;
        session->previousWindowId = previous
            ? frontmostWindowIdFor(previous.processIdentifier) : 0;
        session->leasedWindowId = target.window_id;
        session->leasedWindow = target;
        session->foregroundLease = true;
        session->leaseSentInput = false;
        bool mainAttempted = false;
        AXError mainResult = setMainWindowIfSupported(session, target, &mainAttempted);
        NSRunningApplication *app = [NSRunningApplication runningApplicationWithProcessIdentifier:session->pid];
        if (!app) {
            writeDetail(detail_utf8, detail_capacity,
                [NSString stringWithFormat:@"Target running application is unavailable (pid=%d)",
                    session->pid]);
            restoreForegroundLease(session);
            return -4;
        }
        bool activationAccepted = [app activateWithOptions:0];
        if (!activationAccepted
                && NSWorkspace.sharedWorkspace.frontmostApplication.processIdentifier != session->pid) {
            writeDetail(detail_utf8, detail_capacity,
                [NSString stringWithFormat:
                    @"Target activation was rejected (pid=%d hidden=%d terminated=%d "
                     "frontPid=%d setMainAttempted=%d setMainError=%d)",
                    session->pid, app.isHidden, app.isTerminated,
                    NSWorkspace.sharedWorkspace.frontmostApplication.processIdentifier,
                    mainAttempted, mainResult]);
            restoreForegroundLease(session);
            return -4;
        }
        for (int attempt = 0; attempt < 20
                && NSWorkspace.sharedWorkspace.frontmostApplication.processIdentifier != session->pid; ++attempt)
            std::this_thread::sleep_for(std::chrono::milliseconds(50));
        if (NSWorkspace.sharedWorkspace.frontmostApplication.processIdentifier != session->pid) {
            writeDetail(detail_utf8, detail_capacity, @"Target application did not gain focus");
            restoreForegroundLease(session);
            return -4;
        }
        bool raiseAttempted = frontmostWindowIdFor(session->pid) != target.window_id;
        bool raised = !raiseAttempted || raiseWindowByCgId(session->pid, target.window_id);
        bool ready = false;
        for (int attempt = 0; attempt < 6; ++attempt) {
            reason = nil;
            ready = targetWindowReady(session->pid, target, CGPointZero, false, &reason)
                && focusedWindowMatches(session->pid, target);
            if (ready) break;
            if (!reason) reason = @"A different target window has keyboard focus";
            if (attempt < 5) std::this_thread::sleep_for(std::chrono::milliseconds(50));
        }
        if (!ready) {
            writeDetail(detail_utf8, detail_capacity,
                [NSString stringWithFormat:
                    @"%@ (setMainAttempted=%d setMainError=%d "
                     "raiseAttempted=%d raiseSucceeded=%d)",
                    reason ?: @"Selected target window could not be brought to the foreground",
                    mainAttempted, mainResult, raiseAttempted, raised]);
            // Activation was ours even if another target-app window became key.
            // Undo that focus change unless the user has switched elsewhere.
            restoreForegroundLease(session, true);
            return -4;
        }
        writeDetail(detail_utf8, detail_capacity,
            @"Selected target window is foreground; observe it again before sending input");
        return 0;
    }
}

void jc_desktop_restore_foreground(void *opaque) {
    if (!opaque) return;
    @autoreleasepool {
        auto *session = static_cast<MacSession *>(opaque);
        SessionCall call(session);
        std::lock_guard operation(session->control);
        restoreForegroundLease(session);
    }
}

int32_t jc_desktop_element_diagnostics(void *opaque, char *detail_utf8,
                                       uint32_t detail_capacity) {
    @autoreleasepool {
        writeDetail(detail_utf8, detail_capacity, @"");
        if (!opaque) return -1;
        auto *session = static_cast<MacSession *>(opaque);
        SessionCall call(session);
        std::lock_guard operation(session->control);
        writeDetail(detail_utf8, detail_capacity, session->axDiagnostics);
        return 0;
    }
}

int32_t jc_desktop_perform_element(void *opaque, const jc_desktop_action *action,
                                   uint32_t element_index, char *detail_utf8,
                                   uint32_t detail_capacity) {
    @autoreleasepool {
        writeDetail(detail_utf8, detail_capacity, @"");
        if (!opaque || !action) return JC_ACTION_FAILED;
        auto *session = static_cast<MacSession *>(opaque);
        SessionCall call(session);
        std::lock_guard operation(session->control);
        RestoreAfterForegroundAction restore{session,
            action->mode == JC_MODE_FOREGROUND && session->foregroundLease};
        if (!session->isOriginalProcess()) {
            writeDetail(detail_utf8, detail_capacity, @"Target process exited or restarted");
            return JC_ACTION_STALE_FRAME;
        }
        if (!AXIsProcessTrusted()) {
            writeDetail(detail_utf8, detail_capacity, @"Accessibility permission was revoked");
            return JC_ACTION_DENIED;
        }
        if (action->mode != JC_MODE_BACKGROUND || action->kind != JC_ACTION_CLICK
                || action->button != 1 || action->clicks != 1) {
            writeDetail(detail_utf8, detail_capacity,
                @"Direct Accessibility element action supports one left AXPress only");
            return JC_ACTION_UNSUPPORTED;
        }
        auto found = std::find_if(session->axElements.begin(), session->axElements.end(),
            [element_index](const AxSnapshotElement &entry) {
                return entry.token == element_index;
            });
        if (element_index == 0 || found == session->axElements.end()) {
            writeDetail(detail_utf8, detail_capacity,
                @"Accessibility element token is absent or expired; observe again");
            return JC_ACTION_STALE_FRAME;
        }
        // A selected element is single-use, even when a pre-dispatch check fails.
        struct InvalidateOnExit {
            MacSession *session;
            ~InvalidateOnExit() { session->clearAxElements(); }
        } invalidate{session};
        const AxSnapshotElement &snapshot = *found;
        jc_desktop_window current;
        {
            std::lock_guard guard(session->state);
            bool usableFrame = jc_ax_snapshot::frameUsable(
                session->captureFailed, session->latest.size(), session->frameWidth,
                session->frameHeight, session->frameStride);
            current = session->current;
            bool regionUnchanged = session->contentRevision == snapshot.contentRevision
                || axRoiMatches(snapshot, session->latest, session->frameStride,
                                session->frameWidth, session->frameHeight);
            if (session->stopping || !session->available || !usableFrame
                    || !jc_ax_snapshot::actionReady(
                        {snapshot.token, snapshot.windowId, snapshot.generation,
                         snapshot.contentRevision}, element_index, current.window_id,
                        session->generation, session->contentRevision,
                        action->generation, action->content_revision, regionUnchanged)
                    || session->timestamp == 0
                    || nowMillis() > session->timestamp + kFrameFreshness.count()
                    || action->x < snapshot.frameX || action->y < snapshot.frameY
                    || action->x >= snapshot.frameX + snapshot.frameWidth
                    || action->y >= snapshot.frameY + snapshot.frameHeight) {
                writeDetail(detail_utf8, detail_capacity,
                    @"Accessibility element or observed region changed; observe again");
                return JC_ACTION_STALE_FRAME;
            }
        }
        AXUIElementRef element = snapshot.element;
        AXUIElementSetMessagingTimeout(element, 0.25);
        pid_t owner = 0;
        AXError error = AXUIElementGetPid(element, &owner);
        if (error != kAXErrorSuccess) {
            writeDetail(detail_utf8, detail_capacity,
                [NSString stringWithFormat:@"AX element PID check failed before dispatch (%d)", error]);
            return preDispatchAxStatus(error);
        }
        if (owner != session->pid) {
            writeDetail(detail_utf8, detail_capacity,
                @"Accessibility element now belongs to a different process");
            return JC_ACTION_STALE_FRAME;
        }
        CFTypeRef rawWindow = nullptr;
        error = AXUIElementCopyAttributeValue(element, kAXWindowAttribute, &rawWindow);
        if (error != kAXErrorSuccess) {
            if (rawWindow) CFRelease(rawWindow);
            writeDetail(detail_utf8, detail_capacity,
                [NSString stringWithFormat:@"AX element window check failed before dispatch (%d)", error]);
            return preDispatchAxStatus(error);
        }
        if (!rawWindow || CFGetTypeID(rawWindow) != AXUIElementGetTypeID()) {
            if (rawWindow) CFRelease(rawWindow);
            writeDetail(detail_utf8, detail_capacity, @"AX element window identity is unavailable");
            return JC_ACTION_STALE_FRAME;
        }
        AXUIElementRef window = static_cast<AXUIElementRef>(const_cast<void *>(rawWindow));
        AXUIElementSetMessagingTimeout(window, 0.25);
        bool sameWindowHandle = CFEqual(window, snapshot.window);
        CGRect windowBounds{};
        AXError boundsError = kAXErrorSuccess;
        bool windowReadable = axBounds(window, &windowBounds, &boundsError);
        CFRelease(rawWindow);
        if (!windowReadable) {
            writeDetail(detail_utf8, detail_capacity,
                [NSString stringWithFormat:@"AX window bounds check failed before dispatch (%d)", boundsError]);
            return preDispatchAxStatus(boundsError);
        }
        if (!sameWindowHandle || !sameBounds(windowBounds, current)) {
            writeDetail(detail_utf8, detail_capacity, @"AX element moved to another window");
            return JC_ACTION_STALE_FRAME;
        }
        CGRect elementBounds{};
        boundsError = kAXErrorSuccess;
        if (!axBounds(element, &elementBounds, &boundsError)) {
            writeDetail(detail_utf8, detail_capacity,
                [NSString stringWithFormat:@"AX element bounds check failed before dispatch (%d)", boundsError]);
            return preDispatchAxStatus(boundsError);
        }
        if (!sameAxBounds(elementBounds, snapshot.bounds)) {
            writeDetail(detail_utf8, detail_capacity, @"AX element bounds changed; observe again");
            return JC_ACTION_STALE_FRAME;
        }
        CFTypeRef rawRole = nullptr;
        error = AXUIElementCopyAttributeValue(element, kAXRoleAttribute, &rawRole);
        if (error != kAXErrorSuccess) {
            if (rawRole) CFRelease(rawRole);
            writeDetail(detail_utf8, detail_capacity,
                [NSString stringWithFormat:@"AX element role check failed before dispatch (%d)", error]);
            return preDispatchAxStatus(error);
        }
        std::string role = rawRole && CFGetTypeID(rawRole) == CFStringGetTypeID()
            ? ((__bridge NSString *)rawRole).UTF8String ?: "" : "";
        if (rawRole) CFRelease(rawRole);
        if (role != snapshot.role) {
            writeDetail(detail_utf8, detail_capacity, @"AX element role changed; observe again");
            return JC_ACTION_STALE_FRAME;
        }
        std::string label;
        AXError labelError = kAXErrorSuccess;
        if (!axLabelForDispatch(element, role, &label, &labelError)) {
            writeDetail(detail_utf8, detail_capacity,
                [NSString stringWithFormat:@"AX label check failed before dispatch (%d)", labelError]);
            return preDispatchAxStatus(labelError);
        }
        if (label != snapshot.label) {
            writeDetail(detail_utf8, detail_capacity, @"AX element label changed; observe again");
            return JC_ACTION_STALE_FRAME;
        }
        AXError actionError = kAXErrorSuccess;
        uint32_t availableActions = axActions(element, &actionError);
        if (actionError != kAXErrorSuccess) {
            writeDetail(detail_utf8, detail_capacity,
                [NSString stringWithFormat:@"AX action check failed before dispatch (%d)", actionError]);
            return preDispatchAxStatus(actionError);
        }
        if (!(snapshot.actions & JC_ELEMENT_PRESS)
                || !(availableActions & JC_ELEMENT_PRESS)) {
            writeDetail(detail_utf8, detail_capacity,
                @"Observed AXPress action is no longer available; observe again");
            return JC_ACTION_STALE_FRAME;
        }
        if (!session->isOriginalProcess()) {
            writeDetail(detail_utf8, detail_capacity, @"Target process changed before AXPress");
            return JC_ACTION_STALE_FRAME;
        }
        NSString *cgReason = nil;
        if (!targetWindowExists(session->pid, current, &cgReason)) {
            writeDetail(detail_utf8, detail_capacity,
                cgReason ?: @"Selected CG window changed before AXPress");
            return JC_ACTION_STALE_FRAME;
        }
        AXError latestWindowError = kAXErrorSuccess;
        ScopedAxRef latestWindow(uniqueAxWindow(session->pid, current, &latestWindowError));
        if (!latestWindow.value) {
            writeDetail(detail_utf8, detail_capacity,
                [NSString stringWithFormat:
                    @"Selected AX window could not be reidentified before AXPress (%d)",
                    latestWindowError]);
            return preDispatchAxStatus(latestWindowError);
        }
        if (!CFEqual(latestWindow.value, snapshot.window)) {
            writeDetail(detail_utf8, detail_capacity,
                @"Selected AX window changed before AXPress; observe again");
            return JC_ACTION_STALE_FRAME;
        }
        {
            std::lock_guard guard(session->state);
            bool usableFrame = jc_ax_snapshot::frameUsable(
                session->captureFailed, session->latest.size(), session->frameWidth,
                session->frameHeight, session->frameStride);
            bool regionUnchanged = session->contentRevision == snapshot.contentRevision
                || axRoiMatches(snapshot, session->latest, session->frameStride,
                                session->frameWidth, session->frameHeight);
            if (session->stopping || !session->available || !usableFrame
                    || !jc_ax_snapshot::actionReady(
                        {snapshot.token, snapshot.windowId, snapshot.generation,
                         snapshot.contentRevision}, element_index, session->current.window_id,
                        session->generation, session->contentRevision,
                        action->generation, action->content_revision, regionUnchanged)
                    || session->current.x != current.x || session->current.y != current.y
                    || session->current.width != current.width
                    || session->current.height != current.height
                    || session->timestamp == 0
                    || nowMillis() > session->timestamp + kFrameFreshness.count()) {
                writeDetail(detail_utf8, detail_capacity,
                    @"Target frame or AX element region changed immediately before AXPress");
                return JC_ACTION_STALE_FRAME;
            }
        }
        // AXPerformAction may have reached the app even when it returns an error.
        error = AXUIElementPerformAction(element, kAXPressAction);
        writeDetail(detail_utf8, detail_capacity, error == kAXErrorSuccess
            ? @"Accessibility press was accepted; target effect is not confirmed"
            : [NSString stringWithFormat:
                @"Accessibility press returned AX error %d after dispatch; target effect is uncertain",
                error]);
        return error == kAXErrorSuccess ? JC_ACTION_ACCEPTED : JC_ACTION_UNKNOWN;
    }
}

int32_t jc_desktop_perform(void *opaque, const jc_desktop_action *action,
                           char *detail_utf8, uint32_t detail_capacity) {
    @autoreleasepool {
        writeDetail(detail_utf8, detail_capacity, @"");
        if (!opaque || !action) return JC_ACTION_FAILED;
        auto *session = static_cast<MacSession *>(opaque);
        SessionCall call(session);
        std::lock_guard operation(session->control);
        session->clearAxElements();
        RestoreAfterForegroundAction restore{session,
            action->mode == JC_MODE_FOREGROUND && session->foregroundLease};
        if (!session->isOriginalProcess()) {
            writeDetail(detail_utf8, detail_capacity, @"Target process exited or restarted");
            return JC_ACTION_STALE_FRAME;
        }
        jc_desktop_window current;
        int32_t width;
        int32_t height;
        uint64_t generation;
        uint64_t contentRevision;
        uint64_t timestamp;
        {
            std::lock_guard guard(session->state);
            if (session->stopping || !session->available) {
                writeDetail(detail_utf8, detail_capacity, @"Target window is unavailable or minimized");
                return JC_ACTION_FAILED;
            }
            if (!jc_ax_snapshot::frameUsable(
                    session->captureFailed, session->latest.size(), session->frameWidth,
                    session->frameHeight, session->frameStride)) {
                writeDetail(detail_utf8, detail_capacity,
                    @"Capture frame is unavailable or failed; observe again before input");
                return JC_ACTION_STALE_FRAME;
            }
            current = session->current;
            width = session->frameWidth;
            height = session->frameHeight;
            generation = session->generation;
            contentRevision = session->contentRevision;
            timestamp = session->timestamp;
        }
        if (action->generation != generation || !timestamp
                || nowMillis() > timestamp + kFrameFreshness.count()
                || (action->content_revision != 0 && action->content_revision != contentRevision)) {
            writeDetail(detail_utf8, detail_capacity, @"Frame is stale; inspect the latest frame before acting");
            return JC_ACTION_STALE_FRAME;
        }
        if ((action->kind == JC_ACTION_CLICK
                && (action->button < 1 || action->button > 3
                    || action->clicks < 1 || action->clicks > 2))
                || (action->kind == JC_ACTION_SCROLL
                    && (action->amount == 0 || action->amount < -1000 || action->amount > 1000))
                || ((action->kind == JC_ACTION_TYPE || action->kind == JC_ACTION_KEY)
                    && (action->text_bytes > 16384
                        || (action->text_bytes > 0 && !action->text_utf8)))) {
            writeDetail(detail_utf8, detail_capacity, @"Invalid click, scroll, or text action arguments");
            return JC_ACTION_FAILED;
        }
        if (width <= 0 || height <= 0 || action->x < 0 || action->y < 0
                || action->x >= width || action->y >= height) {
            writeDetail(detail_utf8, detail_capacity, @"Action coordinate is outside the current frame");
            return JC_ACTION_FAILED;
        }
        CGPoint point = CGPointMake(current.x + (action->x + 0.5) * current.width / width,
                                    current.y + (action->y + 0.5) * current.height / height);
        if (action->mode == JC_MODE_BACKGROUND) {
            return backgroundAction(session, current, action, contentRevision, point,
                                    detail_utf8, detail_capacity);
        }
        if (action->mode == JC_MODE_FOREGROUND) {
            return foregroundAction(session, current, action, point, detail_utf8, detail_capacity);
        }
        writeDetail(detail_utf8, detail_capacity, @"Unsupported action mode");
        return JC_ACTION_UNSUPPORTED;
    }
}

void jc_desktop_close(void *opaque) {
    if (!opaque) return;
    @autoreleasepool { delete static_cast<MacSession *>(opaque); }
}

} // extern "C"
