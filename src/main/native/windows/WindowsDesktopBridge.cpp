#include "../desktop_bridge.h"
#include "../include/application_catalog.h"

#include <windows.h>
#include <dwmapi.h>
#include <shellapi.h>
#include <shlobj.h>
#include <roapi.h>
#include <uiautomation.h>
#include <d3d11.h>
#include <dxgi.h>
#include <unknwn.h>
#include <windows.graphics.capture.interop.h>
#include <windows.graphics.capture.h>
#include <windows.graphics.directx.direct3d11.interop.h>
#include <winrt/base.h>
#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Graphics.h>
#include <winrt/Windows.Graphics.Capture.h>
#include <winrt/Windows.Graphics.DirectX.h>
#include <winrt/Windows.Graphics.DirectX.Direct3D11.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstddef>
#include <condition_variable>
#include <cstdio>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <cwctype>
#include <functional>
#include <initializer_list>
#include <iterator>
#include <limits>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

using namespace winrt;
using namespace winrt::Windows::Graphics;
using namespace winrt::Windows::Graphics::Capture;
using namespace winrt::Windows::Graphics::DirectX;
using namespace winrt::Windows::Graphics::DirectX::Direct3D11;

static_assert(sizeof(jc_desktop_window) == 432 && offsetof(jc_desktop_window, app_utf8) == 44);
static_assert(sizeof(jc_desktop_frame) == 64 && offsetof(jc_desktop_frame, timestamp_millis) == 32);
static_assert(sizeof(jc_desktop_element) == 348 && offsetof(jc_desktop_element, role_utf8) == 28);
static_assert(sizeof(jc_desktop_action) == 64 && offsetof(jc_desktop_action, text_utf8) == 48);

namespace {

constexpr uint64_t kMaximumFrameBytes = 128ull * 1024ull * 1024ull;
constexpr int32_t kInvalidArgument = -1;
constexpr int32_t kUnavailable = -2;
constexpr int32_t kNativeFailure = -3;

struct HandleCloser {
    void operator()(void* handle) const noexcept {
        if (handle != nullptr && handle != INVALID_HANDLE_VALUE) CloseHandle(handle);
    }
};
using ProcessHandle = std::unique_ptr<void, HandleCloser>;

// All entry points are callable from arbitrary Java carrier threads. Initialize
// WinRT for the duration of a call, leaving an existing STA apartment intact.
class Apartment final {
public:
    Apartment() : result_(RoInitialize(RO_INIT_MULTITHREADED)) {}
    ~Apartment() {
        if (SUCCEEDED(result_)) {
            RoUninitialize();
        }
    }
    Apartment(const Apartment&) = delete;
    Apartment& operator=(const Apartment&) = delete;
    bool usable() const { return SUCCEEDED(result_) || result_ == RPC_E_CHANGED_MODE; }

private:
    HRESULT result_;
};

template <size_t N>
void copy_text(char (&target)[N], const std::string& source) {
    std::memset(target, 0, N);
    size_t length = std::min(source.size(), N - 1);
    // Do not truncate in the middle of a UTF-8 code point.
    while (length > 0 && length < source.size()
           && (static_cast<unsigned char>(source[length]) & 0xc0) == 0x80) {
        --length;
    }
    std::memcpy(target, source.data(), length);
}

void detail(char* target, uint32_t capacity, const std::string& message) {
    if (target == nullptr || capacity == 0) {
        return;
    }
    size_t length = std::min(message.size(), static_cast<size_t>(capacity - 1));
    while (length > 0 && length < message.size()
           && (static_cast<unsigned char>(message[length]) & 0xc0) == 0x80) {
        --length;
    }
    std::memcpy(target, message.data(), length);
    target[length] = 0;
}

std::string hresult_detail(HRESULT hr) {
    char buffer[32];
    std::snprintf(buffer, sizeof(buffer), "HRESULT 0x%08lX",
                  static_cast<unsigned long>(static_cast<uint32_t>(hr)));
    return buffer;
}

std::string to_utf8(const std::wstring& text) {
    if (text.empty()) {
        return {};
    }
    int size = WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, text.data(),
                                   static_cast<int>(text.size()), nullptr, 0, nullptr, nullptr);
    if (size <= 0) {
        return {};
    }
    std::string result(static_cast<size_t>(size), '\0');
    WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, text.data(),
                        static_cast<int>(text.size()), result.data(), size, nullptr, nullptr);
    return result;
}

std::wstring from_utf8(const char* text, uint32_t bytes) {
    if (text == nullptr || bytes == 0 || bytes > 1'048'576) {
        return {};
    }
    int size = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, text,
                                   static_cast<int>(bytes), nullptr, 0);
    if (size <= 0) {
        return {};
    }
    std::wstring result(static_cast<size_t>(size), L'\0');
    if (MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, text,
                            static_cast<int>(bytes), result.data(), size) != size) {
        return {};
    }
    return result;
}

std::wstring window_title(HWND window) {
    int length = GetWindowTextLengthW(window);
    if (length <= 0) {
        return {};
    }
    std::wstring buffer(static_cast<size_t>(length) + 1, L'\0');
    int written = GetWindowTextW(window, buffer.data(), length + 1);
    buffer.resize(static_cast<size_t>(std::max(written, 0)));
    return buffer;
}

std::wstring process_name(DWORD process_id) {
    HANDLE process = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, process_id);
    if (process == nullptr) {
        return L"process " + std::to_wstring(process_id);
    }
    wchar_t path[32768]{};
    DWORD length = static_cast<DWORD>(std::size(path));
    BOOL ok = QueryFullProcessImageNameW(process, 0, path, &length);
    CloseHandle(process);
    if (!ok || length == 0) {
        return L"process " + std::to_wstring(process_id);
    }
    std::wstring name(path, length);
    auto slash = name.find_last_of(L"\\/");
    if (slash != std::wstring::npos) {
        name.erase(0, slash + 1);
    }
    return name;
}

bool valid_application_request(const char* input, std::wstring* name) {
    if (input == nullptr || name == nullptr) return false;
    const size_t length = strnlen_s(input, 257);
    if (length == 0 || length > 256 || std::strstr(input, "..")) return false;
    for (size_t index = 0; index < length; ++index) {
        unsigned char byte = static_cast<unsigned char>(input[index]);
        if (byte < 0x20 || byte == 0x7f || byte == '/' || byte == '\\' || byte == ':')
            return false;
    }
    *name = from_utf8(input, static_cast<uint32_t>(length));
    return !name->empty() && !std::iswspace(name->front())
        && !std::iswspace(name->back());
}

bool regular_executable(const std::wstring& path) {
    if (path.size() < 7 || !std::iswalpha(path[0]) || path[1] != L':'
            || (path[2] != L'\\' && path[2] != L'/')
            || _wcsicmp(path.c_str() + path.size() - 4, L".exe") != 0)
        return false;
    DWORD attributes = GetFileAttributesW(path.c_str());
    return attributes != INVALID_FILE_ATTRIBUTES
        && (attributes & FILE_ATTRIBUTE_DIRECTORY) == 0;
}

struct ApplicationRegistryKey {
    HKEY value = nullptr;
    ~ApplicationRegistryKey() { if (value) RegCloseKey(value); }
    ApplicationRegistryKey() = default;
    ApplicationRegistryKey(const ApplicationRegistryKey&) = delete;
    ApplicationRegistryKey& operator=(const ApplicationRegistryKey&) = delete;
};

std::wstring registered_executable(const std::wstring& name) {
    std::wstring key = L"SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths\\";
    key += name;
    if (name.size() < 4 || _wcsicmp(name.c_str() + name.size() - 4, L".exe") != 0)
        key += L".exe";
    for (HKEY hive : {HKEY_CURRENT_USER, HKEY_LOCAL_MACHINE}) {
        for (REGSAM view : {KEY_WOW64_64KEY, KEY_WOW64_32KEY}) {
            ApplicationRegistryKey registered;
            if (RegOpenKeyExW(hive, key.c_str(), 0, KEY_QUERY_VALUE | view,
                    &registered.value) != ERROR_SUCCESS) continue;
            DWORD bytes = 0;
            LSTATUS result = RegGetValueW(registered.value, nullptr, nullptr,
                    RRF_RT_REG_SZ | RRF_RT_REG_EXPAND_SZ, nullptr, nullptr, &bytes);
            if (result != ERROR_SUCCESS || bytes < sizeof(wchar_t) || bytes > 65536) {
                continue;
            }
            std::wstring path(bytes / sizeof(wchar_t), L'\0');
            result = RegGetValueW(registered.value, nullptr, nullptr,
                    RRF_RT_REG_SZ | RRF_RT_REG_EXPAND_SZ, nullptr, path.data(), &bytes);
            if (result != ERROR_SUCCESS) continue;
            path.resize(wcsnlen_s(path.c_str(), path.size()));
            if (regular_executable(path)) return path;
        }
    }
    return {};
}

void find_start_menu_shortcuts(const std::wstring& folder, const std::wstring& name,
                               int depth, size_t* visited,
                               std::vector<std::wstring>* matches) {
    if (depth > 7 || *visited > 20000 || matches->size() > 1) return;
    WIN32_FIND_DATAW entry{};
    HANDLE search = FindFirstFileW((folder + L"\\*").c_str(), &entry);
    if (search == INVALID_HANDLE_VALUE) return;
    do {
        if (++*visited > 20000 || matches->size() > 1) break;
        if (wcscmp(entry.cFileName, L".") == 0 || wcscmp(entry.cFileName, L"..") == 0
                || (entry.dwFileAttributes & FILE_ATTRIBUTE_REPARSE_POINT) != 0) continue;
        std::wstring path = folder + L"\\" + entry.cFileName;
        if ((entry.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) != 0) {
            find_start_menu_shortcuts(path, name, depth + 1, visited, matches);
            continue;
        }
        std::wstring label(entry.cFileName);
        if (label.size() < 5 || _wcsicmp(label.c_str() + label.size() - 4, L".lnk") != 0)
            continue;
        label.resize(label.size() - 4);
        if (_wcsicmp(label.c_str(), name.c_str()) == 0) matches->push_back(path);
    } while (FindNextFileW(search, &entry));
    FindClose(search);
}

std::vector<std::wstring> installed_shortcuts(const std::wstring& name) {
    std::vector<std::wstring> matches;
    size_t visited = 0;
    const KNOWNFOLDERID folders[] = {FOLDERID_Programs, FOLDERID_CommonPrograms};
    for (const auto& folder : folders) {
        PWSTR root = nullptr;
        if (SUCCEEDED(SHGetKnownFolderPath(folder, KF_FLAG_DEFAULT, nullptr, &root)) && root) {
            find_start_menu_shortcuts(root, name, 0, &visited, &matches);
            CoTaskMemFree(root);
        }
    }
    return matches;
}

std::wstring shortcut_executable(const std::wstring& path) {
    HRESULT initialized = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    if (FAILED(initialized) && initialized != RPC_E_CHANGED_MODE) return {};
    IShellLinkW* link = nullptr;
    IPersistFile* file = nullptr;
    std::wstring result;
    if (SUCCEEDED(CoCreateInstance(CLSID_ShellLink, nullptr, CLSCTX_INPROC_SERVER,
            IID_IShellLinkW, reinterpret_cast<void**>(&link)))
            && SUCCEEDED(link->QueryInterface(IID_IPersistFile,
                    reinterpret_cast<void**>(&file)))
            && SUCCEEDED(file->Load(path.c_str(), STGM_READ))) {
        wchar_t executable[32768]{};
        wchar_t arguments[2]{};
        WIN32_FIND_DATAW target{};
        if (SUCCEEDED(link->GetPath(executable, static_cast<int>(std::size(executable)),
                                   &target, SLGP_RAWPATH))
                && SUCCEEDED(link->GetArguments(arguments,
                        static_cast<int>(std::size(arguments))))
                && arguments[0] == 0 && regular_executable(executable))
            result = executable;
    }
    if (file) file->Release();
    if (link) link->Release();
    if (SUCCEEDED(initialized)) CoUninitialize();
    return result;
}

std::wstring resolved_executable_id(const std::wstring& name) {
    std::wstring executable = registered_executable(name);
    if (executable.empty()) {
        auto shortcuts = installed_shortcuts(name);
        if (shortcuts.size() != 1) return {};
        executable = shortcut_executable(shortcuts.front());
    }
    if (!regular_executable(executable)) return {};
    std::wstring id = executable.substr(executable.find_last_of(L"\\/") + 1);
    std::transform(id.begin(), id.end(), id.begin(),
                   [](wchar_t ch) { return std::towlower(ch); });
    return id;
}

#include "application_catalog_windows.inc"

RECT physical_bounds(HWND window) {
    RECT bounds{};
    DPI_AWARENESS_CONTEXT prior = SetThreadDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
    HRESULT hr = DwmGetWindowAttribute(window, DWMWA_EXTENDED_FRAME_BOUNDS,
                                       &bounds, sizeof(bounds));
    if (FAILED(hr) || bounds.right <= bounds.left || bounds.bottom <= bounds.top) {
        GetWindowRect(window, &bounds);
    }
    if (prior != nullptr) {
        SetThreadDpiAwarenessContext(prior);
    }
    return bounds;
}

bool valid_window(HWND window, DWORD process_id) {
    if (!IsWindow(window)) {
        return false;
    }
    DWORD actual = 0;
    GetWindowThreadProcessId(window, &actual);
    return actual == process_id;
}

uint64_t process_instance_id(FILETIME created) {
    return (static_cast<uint64_t>(created.dwHighDateTime) << 32) | created.dwLowDateTime;
}

bool discover_process_instance(HWND window, DWORD pid, uint64_t* identity) {
    ProcessHandle process{OpenProcess(SYNCHRONIZE | PROCESS_QUERY_LIMITED_INFORMATION,
                                      FALSE, pid)};
    if (!process) return false;
    FILETIME created{}, exited{}, kernel{}, user{};
    if (!GetProcessTimes(process.get(), &created, &exited, &kernel, &user)
        || WaitForSingleObject(process.get(), 0) != WAIT_TIMEOUT
        || !valid_window(window, pid)) return false;
    *identity = process_instance_id(created);
    return *identity != 0;
}

void fill_window(HWND window, HWND root, uint64_t identity, jc_desktop_window* output) {
    std::memset(output, 0, sizeof(*output));
    DWORD process_id = 0;
    GetWindowThreadProcessId(window, &process_id);
    RECT bounds = physical_bounds(window);
    output->process_id = process_id;
    output->window_id = reinterpret_cast<uint64_t>(window);
    output->process_instance_id = identity;
    output->x = bounds.left;
    output->y = bounds.top;
    output->width = std::max(0L, bounds.right - bounds.left);
    output->height = std::max(0L, bounds.bottom - bounds.top);
    if (IsIconic(window)) output->flags |= JC_WINDOW_MINIMIZED;
    if (IsWindowVisible(window)) output->flags |= JC_WINDOW_VISIBLE;
    if (window != root) output->flags |= JC_WINDOW_POPUP;
    copy_text(output->app_utf8, to_utf8(process_name(process_id)));
    copy_text(output->title_utf8, to_utf8(window_title(window)));
}

HWND active_owned_window(HWND root, DWORD process_id) {
    if (!valid_window(root, process_id)) {
        return nullptr;
    }
    HWND popup = GetLastActivePopup(root);
    if (popup != nullptr && popup != root && valid_window(popup, process_id)
        && IsWindowVisible(popup) && !IsIconic(popup)
        && GetAncestor(popup, GA_ROOTOWNER) == root) {
        return popup;
    }
    return root;
}

struct FrameData {
    std::vector<uint8_t> bgra;
    int32_t width = 0;
    int32_t height = 0;
    uint64_t timestamp_millis = 0;
    uint64_t generation = 0;
    uint64_t window_id = 0;
    uint64_t serial = 0;
    uint64_t content_revision = 0;
    RECT screen_bounds{};
};

class ForegroundLease;

class Session final : public std::enable_shared_from_this<Session> {
public:
    Session(DWORD process_id, DWORD root_thread_id, HWND root,
            ProcessHandle process, FILETIME created)
        : process_id_(process_id), root_thread_id_(root_thread_id), root_(root), current_(root),
          process_(std::move(process)), process_created_(created) {}
    ~Session() { close(); }

    void start() {
        if (!process_instance_alive() || !root_window_alive()) {
            throw hresult_error(HRESULT_FROM_WIN32(ERROR_INVALID_HANDLE));
        }
        UINT flags = D3D11_CREATE_DEVICE_BGRA_SUPPORT;
        HRESULT hr = D3D11CreateDevice(nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr, flags,
                                       nullptr, 0, D3D11_SDK_VERSION, d3d_.put(),
                                       nullptr, context_.put());
        if (FAILED(hr)) {
            hr = D3D11CreateDevice(nullptr, D3D_DRIVER_TYPE_WARP, nullptr, flags,
                                   nullptr, 0, D3D11_SDK_VERSION, d3d_.put(),
                                   nullptr, context_.put());
        }
        check_hresult(hr);
        auto dxgi = d3d_.as<IDXGIDevice>();
        check_hresult(::CreateDirect3D11DeviceFromDXGIDevice(
                dxgi.get(), reinterpret_cast<::IInspectable**>(put_abi(graphics_device_))));
        auto factory = get_activation_factory<GraphicsCaptureItem>()
                .as<IGraphicsCaptureItemInterop>();
        GraphicsCaptureItem root_item{nullptr};
        check_hresult(factory->CreateForWindow(
                root_, guid_of<ABI::Windows::Graphics::Capture::IGraphicsCaptureItem>(),
                reinterpret_cast<void**>(put_abi(root_item))));
        std::weak_ptr<Session> weak = weak_from_this();
        root_closed_token_ = root_item.Closed([weak](auto const&, auto const&) {
            if (auto owner = weak.lock()) {
                owner->root_destroyed_ = true;
                owner->frame_ready_.notify_all();
            }
        });
        root_item_ = std::move(root_item);
        if (!process_instance_alive() || root_destroyed_ || !root_window_alive()) {
            throw hresult_error(HRESULT_FROM_WIN32(ERROR_INVALID_HANDLE));
        }
        capture_window(current_);
    }

    void close() noexcept {
        try {
            restore_foreground();
            std::lock_guard lock(capture_mutex_);
            terminate_locked();
        } catch (...) {}
    }

    int32_t poll(jc_desktop_frame* output, uint32_t timeout_millis) {
        if (output == nullptr) return kInvalidArgument;
        std::memset(output, 0, sizeof(*output));
        if (closed_) return kUnavailable;
        try {
            refresh_window();
        } catch (...) {
            return kNativeFailure;
        }
        std::unique_lock lock(frame_mutex_);
        frame_ready_.wait_for(lock, std::chrono::milliseconds(std::min(timeout_millis, 5000u)),
                              [&] { return closed_ || root_destroyed_
                                          || capture_destroyed_
                                          || latest_.serial > delivered_serial_; });
        if (closed_ || root_destroyed_ || !process_instance_alive()) return kUnavailable;
        if (capture_destroyed_) return 1;
        if (latest_.serial <= delivered_serial_ || latest_.bgra.empty()) return 1;
        void* pixels = std::malloc(latest_.bgra.size());
        if (pixels == nullptr) return kNativeFailure;
        std::memcpy(pixels, latest_.bgra.data(), latest_.bgra.size());
        output->pixels = static_cast<uint8_t*>(pixels);
        output->byte_count = latest_.bgra.size();
        output->width = latest_.width;
        output->height = latest_.height;
        output->stride = latest_.width * 4;
        output->timestamp_millis = latest_.timestamp_millis;
        output->generation = latest_.generation;
        output->window_id = latest_.window_id;
        output->content_revision = latest_.content_revision;
        delivered_serial_ = latest_.serial;
        return 0;
    }

    int32_t current_window(jc_desktop_window* output) {
        if (output == nullptr) return kInvalidArgument;
        if (closed_) return kUnavailable;
        try {
            refresh_window();
        } catch (...) {
            return kNativeFailure;
        }
        HWND window = current_.load();
        if (closed_ || root_destroyed_ || capture_destroyed_ || !process_instance_alive()
            || !valid_window(window, process_id_)) return kUnavailable;
        fill_window(window, root_, process_instance_id(process_created_), output);
        return 0;
    }

    int32_t perform(const jc_desktop_action* action, char* message, uint32_t capacity);
    int32_t prepare_foreground(char* message, uint32_t capacity);
    void restore_foreground() noexcept;
    int32_t list_elements(uint64_t generation, uint64_t content_revision,
                          jc_desktop_element* elements, uint32_t capacity,
                          uint32_t* count);

    bool frame_still_current(HWND window, const jc_desktop_action& action) {
        try { refresh_window(); } catch (...) { return false; }
        if (closed_ || root_destroyed_ || capture_destroyed_
            || !process_instance_alive() || !valid_window(window, process_id_)) return false;
        std::lock_guard lock(frame_mutex_);
        if (latest_.bgra.empty() || latest_.window_id != reinterpret_cast<uint64_t>(window)
            || latest_.generation != action.generation
            || (action.content_revision != 0
                && latest_.content_revision != action.content_revision)) return false;
        RECT bounds = physical_bounds(window);
        return bounds.left == latest_.screen_bounds.left
               && bounds.top == latest_.screen_bounds.top
               && bounds.right == latest_.screen_bounds.right
               && bounds.bottom == latest_.screen_bounds.bottom;
    }

private:
    bool process_instance_alive() const noexcept {
        if (!process_ || WaitForSingleObject(process_.get(), 0) != WAIT_TIMEOUT) return false;
        FILETIME created{}, exited{}, kernel{}, user{};
        return GetProcessTimes(process_.get(), &created, &exited, &kernel, &user)
            && CompareFileTime(&created, &process_created_) == 0;
    }

    bool root_window_alive() const noexcept {
        if (!IsWindow(root_)) return false;
        DWORD actual_process = 0;
        DWORD actual_thread = GetWindowThreadProcessId(root_, &actual_process);
        return actual_process == process_id_ && actual_thread == root_thread_id_;
    }

    // Called while capture_mutex_ is held. An observed process exit or root
    // window closure permanently invalidates this session.
    void terminate_locked() noexcept {
        if (closed_.exchange(true)) return;
        stop_capture();
        if (root_item_) {
            try { root_item_.Closed(root_closed_token_); } catch (...) {}
            root_item_ = nullptr;
        }
        current_ = nullptr;
        {
            std::lock_guard lock(frame_mutex_);
            latest_.bgra.clear();
        }
        frame_ready_.notify_all();
    }

    void stop_capture() {
        ++capture_epoch_;
        if (item_) {
            try { item_.Closed(item_closed_token_); } catch (...) {}
        }
        if (frame_pool_) {
            try { frame_pool_.FrameArrived(frame_token_); } catch (...) {}
        }
        if (capture_session_) {
            try { capture_session_.Close(); } catch (...) {}
        }
        if (frame_pool_) {
            try { frame_pool_.Close(); } catch (...) {}
        }
        capture_session_ = nullptr;
        frame_pool_ = nullptr;
        item_ = nullptr;
        capture_destroyed_ = false;
    }

    void capture_window(HWND window) {
        if (root_destroyed_ || !process_instance_alive() || !root_window_alive()
            || !valid_window(window, process_id_)) {
            throw hresult_error(HRESULT_FROM_WIN32(ERROR_INVALID_HANDLE));
        }
        auto factory = get_activation_factory<GraphicsCaptureItem>()
                .as<IGraphicsCaptureItemInterop>();
        GraphicsCaptureItem item{nullptr};
        check_hresult(factory->CreateForWindow(
                window, guid_of<ABI::Windows::Graphics::Capture::IGraphicsCaptureItem>(),
                reinterpret_cast<void**>(put_abi(item))));
        auto size = item.Size();
        if (size.Width <= 0 || size.Height <= 0) {
            throw hresult_error(E_INVALIDARG);
        }
        auto pool = Direct3D11CaptureFramePool::CreateFreeThreaded(
                graphics_device_, DirectXPixelFormat::B8G8R8A8UIntNormalized, 2, size);
        auto capture = pool.CreateCaptureSession(item);
        const uint64_t epoch = ++capture_epoch_;
        std::weak_ptr<Session> weak = weak_from_this();
        auto closed_token = item.Closed([weak, epoch](auto const&, auto const&) {
            if (auto owner = weak.lock(); owner && epoch == owner->capture_epoch_) {
                owner->capture_destroyed_ = true;
                owner->frame_ready_.notify_all();
            }
        });
        auto token = pool.FrameArrived([weak, epoch](auto const& sender, auto const&) {
            if (auto owner = weak.lock()) owner->on_frame(sender, epoch);
        });
        try {
            capture.StartCapture();
        } catch (...) {
            try { item.Closed(closed_token); } catch (...) {}
            try { pool.FrameArrived(token); } catch (...) {}
            try { capture.Close(); } catch (...) {}
            try { pool.Close(); } catch (...) {}
            throw;
        }
        item_ = std::move(item);
        frame_pool_ = std::move(pool);
        capture_session_ = std::move(capture);
        frame_token_ = token;
        item_closed_token_ = closed_token;
    }

    void refresh_window() {
        std::lock_guard capture_lock(capture_mutex_);
        if (closed_) return;
        if (root_destroyed_ || !process_instance_alive() || !root_window_alive()) {
            terminate_locked();
            return;
        }
        HWND wanted = active_owned_window(root_, process_id_);
        if (wanted == nullptr) {
            terminate_locked();
            return;
        }
        HWND prior = current_;
        bool capture_destroyed = capture_destroyed_;
        if (capture_destroyed && prior == root_) {
            terminate_locked();
            return;
        }
        RECT bounds = physical_bounds(wanted);
        bool moved = bounds.left != last_bounds_.left || bounds.top != last_bounds_.top
                  || bounds.right != last_bounds_.right || bounds.bottom != last_bounds_.bottom;
        bool minimized = IsIconic(wanted);
        if (wanted == prior && moved && !minimized) {
            ++generation_;
            std::lock_guard frame_lock(frame_mutex_);
            latest_.bgra.clear();
        }
        last_bounds_ = bounds;
        if (wanted == prior && !minimized && frame_pool_ && !capture_destroyed) return;
        if (wanted != prior || minimized || !frame_pool_ || capture_destroyed) {
            stop_capture();
            current_ = wanted;
            ++generation_;
            {
                std::lock_guard frame_lock(frame_mutex_);
                latest_.bgra.clear();
            }
            if (!minimized) capture_window(wanted);
        }
    }

    void on_frame(const Direct3D11CaptureFramePool& sender, uint64_t epoch) noexcept {
        if (closed_ || root_destroyed_ || capture_destroyed_
            || !process_instance_alive()
            || epoch != capture_epoch_) return;
        try {
            auto frame = sender.TryGetNextFrame();
            if (!frame) return;
            auto content_size = frame.ContentSize();
            auto surface = frame.Surface();
            auto access = surface.as<::Windows::Graphics::DirectX::Direct3D11::IDirect3DDxgiInterfaceAccess>();
            com_ptr<ID3D11Texture2D> source;
            check_hresult(access->GetInterface(__uuidof(ID3D11Texture2D), source.put_void()));
            D3D11_TEXTURE2D_DESC desc{};
            source->GetDesc(&desc);
            int32_t width = std::min(content_size.Width, static_cast<int32_t>(desc.Width));
            int32_t height = std::min(content_size.Height, static_cast<int32_t>(desc.Height));
            uint64_t bytes = static_cast<uint64_t>(width) * static_cast<uint64_t>(height) * 4;
            if (width <= 0 || height <= 0 || bytes > kMaximumFrameBytes) return;
            std::vector<uint8_t> bgra(static_cast<size_t>(bytes));
            {
                std::lock_guard gpu_lock(gpu_mutex_);
                if (!staging_ || staging_width_ != desc.Width || staging_height_ != desc.Height) {
                    D3D11_TEXTURE2D_DESC stage = desc;
                    stage.Usage = D3D11_USAGE_STAGING;
                    stage.BindFlags = 0;
                    stage.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
                    stage.MiscFlags = 0;
                    stage.MipLevels = 1;
                    stage.ArraySize = 1;
                    stage.SampleDesc.Count = 1;
                    stage.SampleDesc.Quality = 0;
                    staging_ = nullptr;
                    check_hresult(d3d_->CreateTexture2D(&stage, nullptr, staging_.put()));
                    staging_width_ = desc.Width;
                    staging_height_ = desc.Height;
                }
                context_->CopyResource(staging_.get(), source.get());
                D3D11_MAPPED_SUBRESOURCE mapped{};
                check_hresult(context_->Map(staging_.get(), 0, D3D11_MAP_READ, 0, &mapped));
                for (int32_t row = 0; row < height; ++row) {
                    auto* src = static_cast<const uint8_t*>(mapped.pData) + row * mapped.RowPitch;
                    auto* dst = bgra.data() + static_cast<size_t>(row) * width * 4;
                    std::memcpy(dst, src, static_cast<size_t>(width) * 4);
                }
                context_->Unmap(staging_.get(), 0);
            }
            auto timestamp = std::chrono::duration_cast<std::chrono::milliseconds>(
                    std::chrono::system_clock::now().time_since_epoch()).count();
            RECT bounds = physical_bounds(current_);
            {
                std::lock_guard frame_lock(frame_mutex_);
                if (closed_ || root_destroyed_ || capture_destroyed_
                    || !process_instance_alive()
                    || epoch != capture_epoch_) return;
                bool content_changed = latest_.width != width || latest_.height != height
                                       || latest_.bgra != bgra;
                bool geometry_changed = latest_.width != width || latest_.height != height
                    || latest_.screen_bounds.left != bounds.left
                    || latest_.screen_bounds.top != bounds.top
                    || latest_.screen_bounds.right != bounds.right
                    || latest_.screen_bounds.bottom != bounds.bottom;
                if (geometry_changed) ++generation_;
                if (content_changed) ++latest_.content_revision;
                latest_.bgra = std::move(bgra);
                latest_.width = width;
                latest_.height = height;
                latest_.timestamp_millis = static_cast<uint64_t>(timestamp);
                latest_.generation = generation_;
                latest_.window_id = reinterpret_cast<uint64_t>(current_.load());
                latest_.screen_bounds = bounds;
                ++latest_.serial;
            }
            frame_ready_.notify_all();
            frame.Close();
            if (content_size.Width > 0 && content_size.Height > 0
                && (content_size.Width != static_cast<int32_t>(desc.Width)
                    || content_size.Height != static_cast<int32_t>(desc.Height))) {
                sender.Recreate(graphics_device_, DirectXPixelFormat::B8G8R8A8UIntNormalized,
                                2, content_size);
            }
        } catch (...) {
            // An unavailable or protected surface produces no new frame. Java
            // treats the expired timestamp as paused rather than reusing it.
        }
    }

    bool action_point(const jc_desktop_action& action, HWND* window, POINT* point,
                      std::string* failure) {
        try {
            refresh_window();
        } catch (...) {
            *failure = "capture target is unavailable";
            return false;
        }
        if (closed_ || root_destroyed_ || capture_destroyed_
            || !process_instance_alive()) {
            *failure = "target process exited or window closed";
            return false;
        }
        std::lock_guard lock(frame_mutex_);
        if (latest_.bgra.empty() || action.generation != latest_.generation
            || (action.content_revision != 0
                && action.content_revision != latest_.content_revision)) {
            *failure = "stale or missing frame";
            return false;
        }
        HWND selected = current_;
        if (!valid_window(selected, process_id_) || IsIconic(selected)
            || latest_.window_id != reinterpret_cast<uint64_t>(selected)) {
            *failure = "target closed or minimized";
            return false;
        }
        RECT bounds = physical_bounds(selected);
        if (bounds.left != latest_.screen_bounds.left
            || bounds.top != latest_.screen_bounds.top
            || bounds.right != latest_.screen_bounds.right
            || bounds.bottom != latest_.screen_bounds.bottom) {
            *failure = "target moved since frame";
            return false;
        }
        if (action.x < 0 || action.y < 0 || action.x >= latest_.width
            || action.y >= latest_.height) {
            *failure = "point outside captured frame";
            return false;
        }
        int64_t bound_width = static_cast<int64_t>(bounds.right) - bounds.left;
        int64_t bound_height = static_cast<int64_t>(bounds.bottom) - bounds.top;
        if (bound_width <= 0 || bound_height <= 0) {
            *failure = "target has no visible bounds";
            return false;
        }
        point->x = bounds.left + static_cast<LONG>(action.x * bound_width / latest_.width);
        point->y = bounds.top + static_cast<LONG>(action.y * bound_height / latest_.height);
        *window = selected;
        return true;
    }

    DWORD process_id_;
    DWORD root_thread_id_;
    HWND root_;
    std::atomic<HWND> current_;
    ProcessHandle process_;
    FILETIME process_created_{};
    std::atomic<bool> closed_{false};
    std::atomic<bool> root_destroyed_{false};
    std::atomic<bool> capture_destroyed_{false};
    std::atomic<uint64_t> capture_epoch_{0};
    std::atomic<uint64_t> generation_{1};
    RECT last_bounds_{};
    std::mutex capture_mutex_;
    std::mutex frame_mutex_;
    std::condition_variable frame_ready_;
    FrameData latest_;
    uint64_t delivered_serial_ = 0;
    std::mutex foreground_mutex_;
    std::shared_ptr<ForegroundLease> foreground_lease_;
    std::mutex gpu_mutex_;
    com_ptr<ID3D11Device> d3d_;
    com_ptr<ID3D11DeviceContext> context_;
    com_ptr<ID3D11Texture2D> staging_;
    UINT staging_width_ = 0;
    UINT staging_height_ = 0;
    IDirect3DDevice graphics_device_{nullptr};
    GraphicsCaptureItem item_{nullptr};
    GraphicsCaptureItem root_item_{nullptr};
    event_token root_closed_token_{};
    event_token item_closed_token_{};
    Direct3D11CaptureFramePool frame_pool_{nullptr};
    GraphicsCaptureSession capture_session_{nullptr};
    event_token frame_token_{};
};

struct Candidate {
    com_ptr<IUIAutomationElement> element;
    com_ptr<IUnknown> pattern;
    PATTERNID pattern_id = 0;
    int64_t area = std::numeric_limits<int64_t>::max();
};

Candidate find_semantic_element(HWND window, POINT point,
                                std::initializer_list<PATTERNID> patterns) {
    com_ptr<IUIAutomation> automation;
    check_hresult(CoCreateInstance(CLSID_CUIAutomation, nullptr, CLSCTX_INPROC_SERVER,
                                   __uuidof(IUIAutomation), automation.put_void()));
    com_ptr<IUIAutomationElement> root;
    check_hresult(automation->ElementFromHandle(window, root.put()));
    com_ptr<IUIAutomationCondition> true_condition;
    check_hresult(automation->CreateTrueCondition(true_condition.put()));
    com_ptr<IUIAutomationElementArray> descendants;
    check_hresult(root->FindAll(TreeScope_Descendants, true_condition.get(), descendants.put()));
    int length = 0;
    check_hresult(descendants->get_Length(&length));
    Candidate chosen;
    auto consider = [&](const com_ptr<IUIAutomationElement>& element) {
        RECT bounds{};
        BOOL enabled = FALSE;
        BOOL offscreen = TRUE;
        if (FAILED(element->get_CurrentBoundingRectangle(&bounds))
            || FAILED(element->get_CurrentIsEnabled(&enabled))
            || FAILED(element->get_CurrentIsOffscreen(&offscreen))
            || !enabled || offscreen || point.x < bounds.left || point.x >= bounds.right
            || point.y < bounds.top || point.y >= bounds.bottom) {
            return;
        }
        int64_t area = static_cast<int64_t>(bounds.right - bounds.left)
                       * (bounds.bottom - bounds.top);
        if (area <= 0 || area >= chosen.area) return;
        for (PATTERNID id : patterns) {
            com_ptr<IUnknown> pattern;
            if (SUCCEEDED(element->GetCurrentPattern(id, pattern.put())) && pattern) {
                chosen.element = element;
                chosen.pattern = std::move(pattern);
                chosen.pattern_id = id;
                chosen.area = area;
                return;
            }
        }
    };
    consider(root);
    // Large virtualized trees can still be slow in the provider. Bound the
    // number of COM queries made after FindAll returns.
    for (int index = 0; index < std::min(length, 2048); ++index) {
        com_ptr<IUIAutomationElement> element;
        if (SUCCEEDED(descendants->GetElement(index, element.put())) && element) {
            consider(element);
        }
    }
    return chosen;
}

const char* control_role(CONTROLTYPEID type) {
    switch (type) {
        case UIA_ButtonControlTypeId: return "button";
        case UIA_CheckBoxControlTypeId: return "checkbox";
        case UIA_RadioButtonControlTypeId: return "radio";
        case UIA_ComboBoxControlTypeId: return "combo";
        case UIA_EditControlTypeId: return "edit";
        case UIA_ListItemControlTypeId: return "list-item";
        case UIA_TabItemControlTypeId: return "tab";
        case UIA_MenuItemControlTypeId: return "menu-item";
        case UIA_HyperlinkControlTypeId: return "link";
        case UIA_ScrollBarControlTypeId: return "scrollbar";
        case UIA_DocumentControlTypeId: return "document";
        default: return "control";
    }
}

int32_t Session::list_elements(uint64_t generation, uint64_t content_revision,
                               jc_desktop_element* elements, uint32_t capacity,
                               uint32_t* count) {
    if (count == nullptr || (elements == nullptr && capacity != 0)) return kInvalidArgument;
    *count = 0;
    try { refresh_window(); } catch (...) { return kNativeFailure; }
    if (closed_ || root_destroyed_ || capture_destroyed_ || !process_instance_alive()) {
        return kUnavailable;
    }
    HWND window = current_.load();
    RECT window_bounds{};
    int32_t frame_width = 0;
    int32_t frame_height = 0;
    {
        std::lock_guard lock(frame_mutex_);
        if (latest_.bgra.empty() || generation != latest_.generation
            || content_revision != latest_.content_revision
            || latest_.window_id != reinterpret_cast<uint64_t>(window)) return 1;
        window_bounds = latest_.screen_bounds;
        frame_width = latest_.width;
        frame_height = latest_.height;
    }
    if (!valid_window(window, process_id_)) return 1;
    RECT now = physical_bounds(window);
    if (now.left != window_bounds.left || now.top != window_bounds.top
        || now.right != window_bounds.right || now.bottom != window_bounds.bottom) return 1;
    const int64_t screen_width = static_cast<int64_t>(now.right) - now.left;
    const int64_t screen_height = static_cast<int64_t>(now.bottom) - now.top;
    if (frame_width <= 0 || frame_height <= 0 || screen_width <= 0 || screen_height <= 0) return 1;
    const uint32_t limit = std::min(capacity, 128u);
    if (limit == 0) return 0;
    Apartment apartment;
    if (!apartment.usable()) return kUnavailable;
    try {
        com_ptr<IUIAutomation> automation;
        check_hresult(CoCreateInstance(CLSID_CUIAutomation, nullptr, CLSCTX_INPROC_SERVER,
                                       __uuidof(IUIAutomation), automation.put_void()));
        com_ptr<IUIAutomationElement> root;
        check_hresult(automation->ElementFromHandle(window, root.put()));
        com_ptr<IUIAutomationCondition> condition;
        check_hresult(automation->CreateTrueCondition(condition.put()));
        com_ptr<IUIAutomationElementArray> descendants;
        check_hresult(root->FindAll(TreeScope_Descendants, condition.get(), descendants.put()));
        int length = 0;
        check_hresult(descendants->get_Length(&length));
        auto consider = [&](const com_ptr<IUIAutomationElement>& element) {
            if (*count >= limit) return;
            RECT bounds{};
            BOOL enabled = FALSE;
            BOOL offscreen = TRUE;
            CONTROLTYPEID type = 0;
            if (FAILED(element->get_CurrentBoundingRectangle(&bounds))
                || FAILED(element->get_CurrentIsEnabled(&enabled))
                || FAILED(element->get_CurrentIsOffscreen(&offscreen))
                || FAILED(element->get_CurrentControlType(&type))
                || !enabled || offscreen) return;
            const int64_t left = std::max<int64_t>(bounds.left, now.left);
            const int64_t top = std::max<int64_t>(bounds.top, now.top);
            const int64_t right = std::min<int64_t>(bounds.right, now.right);
            const int64_t bottom = std::min<int64_t>(bounds.bottom, now.bottom);
            if (right <= left || bottom <= top) return;
            uint32_t actions = 0;
            for (PATTERNID pattern_id : {UIA_TogglePatternId, UIA_SelectionItemPatternId,
                                         UIA_InvokePatternId}) {
                com_ptr<IUnknown> pattern;
                if (SUCCEEDED(element->GetCurrentPattern(pattern_id, pattern.put())) && pattern) {
                    actions |= JC_ELEMENT_PRESS;
                    break;
                }
            }
            com_ptr<IUnknown> value_pattern;
            if (SUCCEEDED(element->GetCurrentPattern(UIA_ValuePatternId, value_pattern.put()))
                && value_pattern) {
                auto value = value_pattern.as<IUIAutomationValuePattern>();
                BOOL read_only = TRUE;
                if (SUCCEEDED(value->get_CurrentIsReadOnly(&read_only)) && !read_only) {
                    actions |= JC_ELEMENT_WRITE;
                }
            }
            com_ptr<IUnknown> scroll_pattern;
            if (SUCCEEDED(element->GetCurrentPattern(UIA_ScrollPatternId, scroll_pattern.put()))
                && scroll_pattern) {
                auto scroll = scroll_pattern.as<IUIAutomationScrollPattern>();
                BOOL vertical = FALSE;
                if (SUCCEEDED(scroll->get_CurrentVerticallyScrollable(&vertical)) && vertical) {
                    actions |= JC_ELEMENT_SCROLL;
                }
            }
            if (actions == 0) return;
            jc_desktop_element& output = elements[*count];
            std::memset(&output, 0, sizeof(output));
            output.index = *count;
            output.x = static_cast<int32_t>((left - now.left) * frame_width / screen_width);
            output.y = static_cast<int32_t>((top - now.top) * frame_height / screen_height);
            int32_t right_frame = static_cast<int32_t>((right - now.left) * frame_width / screen_width);
            int32_t bottom_frame = static_cast<int32_t>((bottom - now.top) * frame_height / screen_height);
            output.width = std::max(1, right_frame - output.x);
            output.height = std::max(1, bottom_frame - output.y);
            output.actions = actions;
            copy_text(output.role_utf8, control_role(type));
            // An edit control's accessible name can contain the current input.
            // Never expose that or any ValuePattern value in the observation.
            BOOL password = FALSE;
            element->get_CurrentIsPassword(&password);
            if (!password && type != UIA_EditControlTypeId
                && type != UIA_DocumentControlTypeId) {
                BSTR name = nullptr;
                if (SUCCEEDED(element->get_CurrentName(&name)) && name != nullptr) {
                    copy_text(output.label_utf8,
                              to_utf8(std::wstring(name,
                                  std::min<UINT>(SysStringLen(name), 256u))));
                }
                if (name != nullptr) SysFreeString(name);
            }
            ++*count;
        };
        consider(root);
        for (int index = 0; index < std::min(length, 2048) && *count < limit; ++index) {
            com_ptr<IUIAutomationElement> element;
            if (SUCCEEDED(descendants->GetElement(index, element.put())) && element) {
                consider(element);
            }
        }
    } catch (...) {
        *count = 0;
        return kNativeFailure;
    }
    {
        std::lock_guard lock(frame_mutex_);
        if (closed_ || latest_.bgra.empty() || generation != latest_.generation
            || content_revision != latest_.content_revision
            || latest_.window_id != reinterpret_cast<uint64_t>(window)) {
            *count = 0;
            return 1;
        }
    }
    return 0;
}

int32_t semantic_click(HWND window, POINT point, std::string* failure) {
    auto candidate = find_semantic_element(window, point,
            {UIA_TogglePatternId, UIA_SelectionItemPatternId, UIA_InvokePatternId});
    if (!candidate.pattern) {
        *failure = "no actionable UI Automation control at the frame point";
        return JC_ACTION_UNSUPPORTED;
    }
    if (candidate.pattern_id == UIA_TogglePatternId) {
        auto toggle = candidate.pattern.as<IUIAutomationTogglePattern>();
        ToggleState before{};
        HRESULT hr = toggle->get_CurrentToggleState(&before);
        if (FAILED(hr)) {
            *failure = "UI Automation could not read toggle state before input: "
                    + hresult_detail(hr);
            return hr == E_ACCESSDENIED ? JC_ACTION_DENIED : JC_ACTION_FAILED;
        }
        hr = toggle->Toggle();
        if (FAILED(hr)) {
            *failure = "UI Automation Toggle returned an error after dispatch; effect is unknown: "
                    + hresult_detail(hr);
            return JC_ACTION_UNKNOWN;
        }
        for (int attempt = 0; attempt < 5; ++attempt) {
            ToggleState after{};
            if (SUCCEEDED(toggle->get_CurrentToggleState(&after)) && after != before) {
                *failure = "UI Automation toggle state changed";
                return JC_ACTION_VERIFIED;
            }
            Sleep(20);
        }
        *failure = "toggle was sent but state did not confirm";
        return JC_ACTION_ACCEPTED;
    }
    if (candidate.pattern_id == UIA_SelectionItemPatternId) {
        auto selection = candidate.pattern.as<IUIAutomationSelectionItemPattern>();
        HRESULT hr = selection->Select();
        if (FAILED(hr)) {
            *failure = "UI Automation Select returned an error after dispatch; effect is unknown: "
                    + hresult_detail(hr);
            return JC_ACTION_UNKNOWN;
        }
        BOOL selected = FALSE;
        for (int attempt = 0; attempt < 5; ++attempt) {
            if (SUCCEEDED(selection->get_CurrentIsSelected(&selected)) && selected) {
                *failure = "UI Automation selection confirmed";
                return JC_ACTION_VERIFIED;
            }
            Sleep(20);
        }
        *failure = "selection was sent but state did not confirm";
        return JC_ACTION_ACCEPTED;
    }
    auto invoke = candidate.pattern.as<IUIAutomationInvokePattern>();
    HRESULT hr = invoke->Invoke();
    if (FAILED(hr)) {
        *failure = "UI Automation Invoke returned an error after dispatch; effect is unknown: "
                + hresult_detail(hr);
        return JC_ACTION_UNKNOWN;
    }
    *failure = "UI Automation accepted Invoke; application result is unverified";
    return JC_ACTION_ACCEPTED;
}

int32_t semantic_type(HWND window, POINT point, const jc_desktop_action& action,
                      std::string* failure) {
    std::wstring value = from_utf8(action.text_utf8, action.text_bytes);
    if (value.empty() && action.text_bytes != 0) {
        *failure = "invalid UTF-8 text";
        return JC_ACTION_FAILED;
    }
    auto candidate = find_semantic_element(window, point, {UIA_ValuePatternId});
    if (!candidate.pattern) {
        *failure = "no writable UI Automation value control at the frame point";
        return JC_ACTION_UNSUPPORTED;
    }
    auto pattern = candidate.pattern.as<IUIAutomationValuePattern>();
    BOOL read_only = TRUE;
    if (FAILED(pattern->get_CurrentIsReadOnly(&read_only)) || read_only) {
        *failure = "UI Automation value is read-only";
        return JC_ACTION_DENIED;
    }
    HRESULT hr = pattern->SetValue(value.c_str());
    if (FAILED(hr)) {
        *failure = "UI Automation SetValue returned an error after dispatch; effect is unknown: "
                + hresult_detail(hr);
        return JC_ACTION_UNKNOWN;
    }
    for (int attempt = 0; attempt < 5; ++attempt) {
        BSTR observed = nullptr;
        hr = pattern->get_CurrentValue(&observed);
        if (SUCCEEDED(hr)) {
            bool equal = observed == nullptr ? value.empty()
                    : std::wstring(observed, SysStringLen(observed)) == value;
            if (observed != nullptr) SysFreeString(observed);
            if (equal) {
                *failure = "UI Automation value confirmed";
                return JC_ACTION_VERIFIED;
            }
        }
        Sleep(20);
    }
    *failure = "value was sent but readback did not confirm";
    return JC_ACTION_ACCEPTED;
}

int32_t semantic_scroll(HWND window, POINT point, int32_t amount, std::string* failure) {
    if (amount == 0) {
        *failure = "zero scroll amount";
        return JC_ACTION_FAILED;
    }
    if (amount < -10 || amount > 10) {
        *failure = "scroll amount must be between -10 and 10 notches";
        return JC_ACTION_FAILED;
    }
    auto candidate = find_semantic_element(window, point, {UIA_ScrollPatternId});
    if (!candidate.pattern) {
        *failure = "no UI Automation scroll control at the frame point";
        return JC_ACTION_UNSUPPORTED;
    }
    auto scroll = candidate.pattern.as<IUIAutomationScrollPattern>();
    BOOL can_scroll = FALSE;
    if (FAILED(scroll->get_CurrentVerticallyScrollable(&can_scroll)) || !can_scroll) {
        *failure = "control cannot scroll vertically";
        return JC_ACTION_UNSUPPORTED;
    }
    double before = 0;
    HRESULT hr = scroll->get_CurrentVerticalScrollPercent(&before);
    if (FAILED(hr)) {
        *failure = "control cannot scroll vertically";
        return JC_ACTION_UNSUPPORTED;
    }
    ScrollAmount direction = amount > 0 ? ScrollAmount_SmallIncrement : ScrollAmount_SmallDecrement;
    for (int step = 0; step < std::abs(amount); ++step) {
        hr = scroll->Scroll(ScrollAmount_NoAmount, direction);
        if (FAILED(hr)) {
            *failure = "UI Automation Scroll returned an error after dispatch; effect is unknown: "
                    + hresult_detail(hr);
            return JC_ACTION_UNKNOWN;
        }
        bool confirmed = false;
        for (int attempt = 0; attempt < 5; ++attempt) {
            double after = before;
            if (SUCCEEDED(scroll->get_CurrentVerticalScrollPercent(&after)) && after != before) {
                before = after;
                confirmed = true;
                break;
            }
            Sleep(20);
        }
        if (!confirmed) {
            *failure = "scroll was accepted but position did not confirm";
            // A single accepted final step is transport-complete. If this
            // aborts a longer sequence, some requested steps were not sent.
            return step + 1 == std::abs(amount) ? JC_ACTION_ACCEPTED : JC_ACTION_UNKNOWN;
        }
    }
    *failure = "UI Automation scroll position changed";
    return JC_ACTION_VERIFIED;
}

struct ButtonSearch {
    DWORD process_id;
    POINT point;
    HWND best = nullptr;
    int64_t area = std::numeric_limits<int64_t>::max();
};

BOOL CALLBACK find_button(HWND child, LPARAM opaque) {
    auto* search = reinterpret_cast<ButtonSearch*>(opaque);
    DWORD process_id = 0;
    GetWindowThreadProcessId(child, &process_id);
    if (process_id != search->process_id || !IsWindowVisible(child)) return TRUE;
    wchar_t name[32]{};
    GetClassNameW(child, name, static_cast<int>(std::size(name)));
    if (_wcsicmp(name, L"Button") != 0) return TRUE;
    RECT bounds{};
    if (!GetWindowRect(child, &bounds) || search->point.x < bounds.left
        || search->point.x >= bounds.right || search->point.y < bounds.top
        || search->point.y >= bounds.bottom) return TRUE;
    int64_t area = static_cast<int64_t>(bounds.right - bounds.left)
                   * (bounds.bottom - bounds.top);
    if (area > 0 && area < search->area) {
        search->best = child;
        search->area = area;
    }
    return TRUE;
}

int32_t directed_button_click(HWND window, POINT point, DWORD process_id,
                              std::string* failure) {
    ButtonSearch search{process_id, point};
    EnumChildWindows(window, find_button, reinterpret_cast<LPARAM>(&search));
    if (search.best == nullptr) {
        *failure = "no classic Win32 Button control at the frame point";
        return JC_ACTION_UNSUPPORTED;
    }
    DWORD_PTR before = 0;
    bool had_state = SendMessageTimeoutW(search.best, BM_GETCHECK, 0, 0,
                SMTO_ABORTIFHUNG | SMTO_BLOCK, 200, &before) != 0;
    if (!PostMessageW(search.best, BM_CLICK, 0, 0)) {
        DWORD error = GetLastError();
        *failure = "PostMessage BM_CLICK failed: " + std::to_string(error);
        return error == ERROR_ACCESS_DENIED ? JC_ACTION_DENIED : JC_ACTION_FAILED;
    }
    if (had_state) {
        for (int attempt = 0; attempt < 5; ++attempt) {
            Sleep(20);
            DWORD_PTR after = before;
            if (SendMessageTimeoutW(search.best, BM_GETCHECK, 0, 0,
                    SMTO_ABORTIFHUNG | SMTO_BLOCK, 200, &after) != 0 && after != before) {
                *failure = "classic button state changed";
                return JC_ACTION_VERIFIED;
            }
        }
    }
    *failure = "BM_CLICK queued; application result is unverified";
    return JC_ACTION_ACCEPTED;
}

bool foreground_is(HWND window) {
    return IsWindow(window) && IsWindowVisible(window) && !IsIconic(window)
           && GetForegroundWindow() == window;
}

bool activate_for_takeover(HWND window) {
    if (foreground_is(window)) return true;
    SetForegroundWindow(window);
    return foreground_is(window);
}

bool point_hits_target(HWND window, POINT point) {
    if (!foreground_is(window)) return false;
    HWND hit = WindowFromPoint(point);
    return hit != nullptr && GetAncestor(hit, GA_ROOT) == window;
}

bool cursor_hits_target(HWND window) {
    POINT cursor{};
    return GetCursorPos(&cursor) && point_hits_target(window, cursor);
}

bool focus_belongs_to_target(HWND window) {
    if (!foreground_is(window)) return false;
    GUITHREADINFO info{};
    info.cbSize = sizeof(info);
    DWORD thread = GetWindowThreadProcessId(window, nullptr);
    if (thread == 0 || !GetGUIThreadInfo(thread, &info)) return false;
    HWND focused = info.hwndFocus != nullptr ? info.hwndFocus : info.hwndActive;
    return focused != nullptr && GetAncestor(focused, GA_ROOT) == window;
}

// A lease can span the fresh foreground observation and its following action.
// Do not undo a user's focus or pointer change made while it was running.
class ForegroundLease final {
public:
    explicit ForegroundLease(HWND target) : target_(target), previous_(GetForegroundWindow()) {
        has_previous_cursor_ = GetCursorPos(&previous_cursor_) != 0;
    }
    ForegroundLease(const ForegroundLease&) = delete;
    ForegroundLease& operator=(const ForegroundLease&) = delete;
    ~ForegroundLease() { restore(); }
    void restore() noexcept {
        std::lock_guard lock(mutex_);
        if (restored_) return;
        restored_ = true;
        if (GetForegroundWindow() != target_) return;
        if (moved_cursor_ && has_previous_cursor_) {
            POINT now{};
            if (GetCursorPos(&now) && now.x == synthetic_cursor_.x
                && now.y == synthetic_cursor_.y) {
                SetCursorPos(previous_cursor_.x, previous_cursor_.y);
            }
        }
        if (previous_ != nullptr && previous_ != target_
            && IsWindow(previous_) && IsWindowVisible(previous_)
            && !IsIconic(previous_)) {
            SetForegroundWindow(previous_);
        }
    }
    bool activate() const { return activate_for_takeover(target_); }
    HWND target() const { return target_; }
    bool move_to(POINT point);

private:
    std::mutex mutex_;
    HWND target_;
    HWND previous_;
    POINT previous_cursor_{};
    POINT synthetic_cursor_{};
    bool has_previous_cursor_ = false;
    bool moved_cursor_ = false;
    bool restored_ = false;
};

bool send_inputs(const std::vector<INPUT>& events) {
    return !events.empty() && SendInput(static_cast<UINT>(events.size()),
                                        const_cast<INPUT*>(events.data()), sizeof(INPUT))
                               == events.size();
}

INPUT mouse_event(DWORD flags, DWORD data = 0, LONG x = 0, LONG y = 0) {
    INPUT input{};
    input.type = INPUT_MOUSE;
    input.mi.dwFlags = flags;
    input.mi.mouseData = data;
    input.mi.dx = x;
    input.mi.dy = y;
    return input;
}

INPUT key_event(WORD vk, DWORD flags) {
    INPUT input{};
    input.type = INPUT_KEYBOARD;
    input.ki.wVk = vk;
    input.ki.dwFlags = flags;
    return input;
}

INPUT unicode_event(WCHAR character, DWORD flags) {
    INPUT input{};
    input.type = INPUT_KEYBOARD;
    input.ki.wScan = character;
    input.ki.dwFlags = KEYEVENTF_UNICODE | flags;
    return input;
}

bool move_mouse(POINT point) {
    int left = GetSystemMetrics(SM_XVIRTUALSCREEN);
    int top = GetSystemMetrics(SM_YVIRTUALSCREEN);
    int width = GetSystemMetrics(SM_CXVIRTUALSCREEN);
    int height = GetSystemMetrics(SM_CYVIRTUALSCREEN);
    if (width <= 1 || height <= 1) return false;
    LONG x = static_cast<LONG>(std::clamp<int64_t>(
            (static_cast<int64_t>(point.x) - left) * 65535 / (width - 1), 0, 65535));
    LONG y = static_cast<LONG>(std::clamp<int64_t>(
            (static_cast<int64_t>(point.y) - top) * 65535 / (height - 1), 0, 65535));
    return send_inputs({mouse_event(MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE
                                   | MOUSEEVENTF_VIRTUALDESK, 0, x, y)});
}

bool ForegroundLease::move_to(POINT point) {
    std::lock_guard lock(mutex_);
    if (restored_) return false;
    synthetic_cursor_ = point;
    moved_cursor_ = true;
    bool inserted = move_mouse(point);
    POINT actual{};
    if (GetCursorPos(&actual)) synthetic_cursor_ = actual;
    return inserted;
}

int32_t Session::prepare_foreground(char* message, uint32_t capacity) {
    try { refresh_window(); } catch (...) {
        detail(message, capacity, "capture target is unavailable");
        return kNativeFailure;
    }
    HWND window = current_.load();
    if (closed_ || root_destroyed_ || capture_destroyed_
        || !process_instance_alive() || !valid_window(window, process_id_)
        || !IsWindowVisible(window) || IsIconic(window)) {
        detail(message, capacity, "target window is unavailable for foreground input");
        return kUnavailable;
    }
    std::lock_guard lock(foreground_mutex_);
    if (foreground_lease_ && foreground_lease_->target() == window
        && foreground_is(window)) {
        detail(message, capacity, "exact target window is already in the foreground");
        return 0;
    }
    if (foreground_lease_) {
        if (!foreground_is(foreground_lease_->target())) {
            foreground_lease_.reset();
            detail(message, capacity, "user or another process changed foreground focus");
            return kUnavailable;
        }
        foreground_lease_->restore();
        foreground_lease_.reset();
    }
    auto lease = std::make_shared<ForegroundLease>(window);
    if (!lease->activate()) {
        detail(message, capacity, "Windows refused to activate the exact target window");
        return kUnavailable;
    }
    if (closed_ || root_destroyed_ || !process_instance_alive()
        || !valid_window(window, process_id_)) {
        lease->restore();
        detail(message, capacity, "target changed during foreground preparation");
        return kUnavailable;
    }
    foreground_lease_ = std::move(lease);
    detail(message, capacity, "exact target window activated for a fresh observation");
    return 0;
}

void Session::restore_foreground() noexcept {
    std::lock_guard lock(foreground_mutex_);
    if (foreground_lease_) {
        foreground_lease_->restore();
        foreground_lease_.reset();
    }
}

WORD named_key(const std::wstring& name) {
    if (name.size() == 1 && name[0] >= L'A' && name[0] <= L'Z') return name[0];
    if (name.size() == 1 && name[0] >= L'0' && name[0] <= L'9') return name[0];
    if (name == L"ENTER" || name == L"RETURN") return VK_RETURN;
    if (name == L"TAB") return VK_TAB;
    if (name == L"ESC" || name == L"ESCAPE") return VK_ESCAPE;
    if (name == L"BACKSPACE") return VK_BACK;
    if (name == L"DELETE") return VK_DELETE;
    if (name == L"SPACE") return VK_SPACE;
    if (name == L"UP") return VK_UP;
    if (name == L"DOWN") return VK_DOWN;
    if (name == L"LEFT") return VK_LEFT;
    if (name == L"RIGHT") return VK_RIGHT;
    if (name == L"HOME") return VK_HOME;
    if (name == L"END") return VK_END;
    if (name == L"PAGEUP") return VK_PRIOR;
    if (name == L"PAGEDOWN") return VK_NEXT;
    if (name.size() >= 2 && name[0] == L'F') {
        try {
            int number = std::stoi(name.substr(1));
            if (number >= 1 && number <= 12) return static_cast<WORD>(VK_F1 + number - 1);
        } catch (...) {}
    }
    return 0;
}

int32_t foreground_key(HWND window, const jc_desktop_action& action,
                       std::string* failure) {
    std::wstring chord = from_utf8(action.text_utf8, action.text_bytes);
    if (chord.empty() || chord.size() > 64) {
        *failure = "unsupported key name";
        return JC_ACTION_UNSUPPORTED;
    }
    for (auto& character : chord) character = std::towupper(character);
    std::vector<WORD> modifiers;
    WORD base = 0;
    bool base_seen = false;
    bool invalid = false;
    size_t start = 0;
    while (start < chord.size()) {
        size_t end = chord.find(L'+', start);
        std::wstring token = chord.substr(start, end == std::wstring::npos
                                                  ? std::wstring::npos : end - start);
        if (token == L"CTRL" || token == L"CONTROL") modifiers.push_back(VK_CONTROL);
        else if (token == L"ALT") modifiers.push_back(VK_MENU);
        else if (token == L"SHIFT") modifiers.push_back(VK_SHIFT);
        else if (token == L"WIN" || token == L"META") modifiers.push_back(VK_LWIN);
        else if (!base_seen) {
            base = named_key(token);
            base_seen = true;
        } else invalid = true;
        if (end == std::wstring::npos) break;
        start = end + 1;
    }
    if (base == 0 || invalid) {
        *failure = "unsupported key chord";
        return JC_ACTION_UNSUPPORTED;
    }
    if (!focus_belongs_to_target(window)) {
        *failure = "focused control is not in the target window";
        return JC_ACTION_DENIED;
    }
    std::vector<INPUT> events;
    for (WORD modifier : modifiers) events.push_back(key_event(modifier, 0));
    events.push_back(key_event(base, 0));
    events.push_back(key_event(base, KEYEVENTF_KEYUP));
    for (auto it = modifiers.rbegin(); it != modifiers.rend(); ++it) {
        events.push_back(key_event(*it, KEYEVENTF_KEYUP));
    }
    if (!send_inputs(events)) {
        *failure = "SendInput could not confirm that the full key chord was inserted";
        return JC_ACTION_UNKNOWN;
    }
    *failure = "key chord sent; application result is unverified";
    return JC_ACTION_ACCEPTED;
}

int32_t foreground_action(HWND window, POINT point, const jc_desktop_action& action,
                          ForegroundLease& lease, bool already_prepared,
                          const std::function<bool()>& frame_still_current,
                          std::string* failure) {
    if (already_prepared ? !foreground_is(window) : !lease.activate()) {
        *failure = "Windows refused to give the target foreground focus";
        return JC_ACTION_DENIED;
    }
    if (!frame_still_current()) {
        *failure = "window or captured pixels changed after foreground activation";
        return JC_ACTION_STALE_FRAME;
    }
    if (action.kind == JC_ACTION_KEY) return foreground_key(window, action, failure);
    if (action.kind == JC_ACTION_CLICK) {
        DWORD down = MOUSEEVENTF_LEFTDOWN;
        DWORD up = MOUSEEVENTF_LEFTUP;
        if (action.button == 2) { down = MOUSEEVENTF_MIDDLEDOWN; up = MOUSEEVENTF_MIDDLEUP; }
        if (action.button == 3) { down = MOUSEEVENTF_RIGHTDOWN; up = MOUSEEVENTF_RIGHTUP; }
        if (!point_hits_target(window, point)) {
            *failure = "another window covers the target click point";
            return JC_ACTION_DENIED;
        }
        if (!lease.move_to(point)) {
            *failure = "pointer movement may have been partially inserted";
            return JC_ACTION_UNKNOWN;
        }
        if (!cursor_hits_target(window)) {
            *failure = "target lost foreground or click point was covered";
            return JC_ACTION_UNKNOWN;
        }
        std::vector<INPUT> events;
        events.reserve(static_cast<size_t>(action.clicks) * 2);
        for (int index = 0; index < action.clicks; ++index) {
            events.push_back(mouse_event(down));
            events.push_back(mouse_event(up));
        }
        // SendInput inserts the complete click sequence without interleaving
        // user input; a partial insertion is UNKNOWN and must never be replayed.
        if (!send_inputs(events)) {
            *failure = "click sequence may have been partially inserted";
            return JC_ACTION_UNKNOWN;
        }
        *failure = "click sent; application result is unverified";
        return JC_ACTION_ACCEPTED;
    }
    if (action.kind == JC_ACTION_SCROLL) {
        if (!point_hits_target(window, point)) {
            *failure = "another window covers the target scroll point";
            return JC_ACTION_DENIED;
        }
        if (!lease.move_to(point)) {
            *failure = "pointer movement may have been partially inserted";
            return JC_ACTION_UNKNOWN;
        }
        if (!cursor_hits_target(window)) {
            *failure = "target lost foreground or scroll point was covered";
            return JC_ACTION_UNKNOWN;
        }
        if (!send_inputs({mouse_event(MOUSEEVENTF_WHEEL,
                       static_cast<DWORD>(-action.amount * WHEEL_DELTA))})) {
            *failure = "wheel input may have been partially inserted";
            return JC_ACTION_UNKNOWN;
        }
        *failure = "wheel input sent; application result is unverified";
        return JC_ACTION_ACCEPTED;
    }
    if (action.kind == JC_ACTION_TYPE) {
        std::wstring value = from_utf8(action.text_utf8, action.text_bytes);
        if (value.empty() && action.text_bytes != 0) {
            *failure = "invalid UTF-8 text";
            return JC_ACTION_FAILED;
        }
        if (action.x >= 0 && action.y >= 0) {
            if (!point_hits_target(window, point)) {
                *failure = "another window covers the target text point";
                return JC_ACTION_DENIED;
            }
            if (!lease.move_to(point)) {
                *failure = "pointer movement before typing may have been partially inserted";
                return JC_ACTION_UNKNOWN;
            }
            if (!cursor_hits_target(window)) {
                *failure = "target lost foreground or text point was covered";
                return JC_ACTION_UNKNOWN;
            }
            if (!send_inputs({mouse_event(MOUSEEVENTF_LEFTDOWN),
                              mouse_event(MOUSEEVENTF_LEFTUP)})) {
                *failure = "focus click may have been partially inserted";
                return JC_ACTION_UNKNOWN;
            }
        }
        if (!focus_belongs_to_target(window)) {
            *failure = "focus click was sent but target input focus was not confirmed";
            return JC_ACTION_UNKNOWN;
        }
        for (size_t offset = 0; offset < value.size(); offset += 32) {
            if (!focus_belongs_to_target(window)) {
                *failure = "target lost foreground focus during typing";
                return JC_ACTION_UNKNOWN;
            }
            std::vector<INPUT> events;
            for (size_t index = offset; index < std::min(value.size(), offset + 32); ++index) {
                events.push_back(unicode_event(value[index], 0));
                events.push_back(unicode_event(value[index], KEYEVENTF_KEYUP));
            }
            if (!events.empty() && !send_inputs(events)) {
                *failure = "text may have been partially inserted";
                return JC_ACTION_UNKNOWN;
            }
        }
        *failure = "Unicode text sent; application result is unverified";
        return JC_ACTION_ACCEPTED;
    }
    *failure = "unknown foreground action";
    return JC_ACTION_UNSUPPORTED;
}

int32_t Session::perform(const jc_desktop_action* action, char* message, uint32_t capacity) {
    struct RestorePreparedOnExit {
        Session* session;
        ~RestorePreparedOnExit() { if (session != nullptr) session->restore_foreground(); }
    } restore{action != nullptr && action->mode == JC_MODE_FOREGROUND ? this : nullptr};
    if (action == nullptr || closed_) {
        detail(message, capacity, "session closed or action missing");
        return JC_ACTION_FAILED;
    }
    if (action->mode != JC_MODE_BACKGROUND && action->mode != JC_MODE_FOREGROUND) {
        detail(message, capacity, "invalid action mode");
        return JC_ACTION_FAILED;
    }
    if (action->kind != JC_ACTION_CLICK && action->kind != JC_ACTION_TYPE
        && action->kind != JC_ACTION_KEY && action->kind != JC_ACTION_SCROLL) {
        detail(message, capacity, "invalid action kind");
        return JC_ACTION_FAILED;
    }
    if (action->kind == JC_ACTION_CLICK
        && (action->button < 1 || action->button > 3
            || action->clicks < 1 || action->clicks > 3)) {
        detail(message, capacity, "click button must be 1-3 and count must be 1-3");
        return JC_ACTION_FAILED;
    }
    if (action->kind == JC_ACTION_SCROLL
        && (action->amount == 0 || action->amount < -10 || action->amount > 10)) {
        detail(message, capacity, "scroll amount must be between -10 and 10 nonzero notches");
        return JC_ACTION_FAILED;
    }
    if ((action->kind == JC_ACTION_TYPE || action->kind == JC_ACTION_KEY)
        && (action->text_bytes > 1'048'576
            || (action->text_utf8 == nullptr && action->text_bytes != 0))) {
        detail(message, capacity, "invalid or oversized text input");
        return JC_ACTION_FAILED;
    }
    HWND window = nullptr;
    POINT point{};
    std::string status;
    if (!action_point(*action, &window, &point, &status)) {
        detail(message, capacity, status);
        return JC_ACTION_STALE_FRAME;
    }
    if (closed_ || root_destroyed_ || capture_destroyed_
        || !process_instance_alive()
        || !valid_window(window, process_id_)) {
        detail(message, capacity, "target process exited or window closed before input");
        return JC_ACTION_FAILED;
    }
    int32_t result = JC_ACTION_UNSUPPORTED;
    try {
        if (action->mode == JC_MODE_FOREGROUND) {
            std::shared_ptr<ForegroundLease> prepared;
            {
                std::lock_guard lock(foreground_mutex_);
                prepared = foreground_lease_;
            }
            if (prepared && prepared->target() != window) {
                restore_foreground();
                detail(message, capacity, "foreground target changed after preparation");
                return JC_ACTION_STALE_FRAME;
            }
            auto temporary = prepared ? std::shared_ptr<ForegroundLease>{}
                                      : std::make_shared<ForegroundLease>(window);
            ForegroundLease& lease = prepared ? *prepared : *temporary;
            result = foreground_action(window, point, *action,
                lease, prepared != nullptr,
                [&] { return frame_still_current(window, *action); }, &status);
        } else if (action->kind == JC_ACTION_CLICK && action->button == 1
                   && action->clicks == 1) {
            result = semantic_click(window, point, &status);
            if (result == JC_ACTION_UNSUPPORTED) {
                result = directed_button_click(window, point, process_id_, &status);
            }
        } else if (action->kind == JC_ACTION_TYPE) {
            result = semantic_type(window, point, *action, &status);
        } else if (action->kind == JC_ACTION_SCROLL) {
            result = semantic_scroll(window, point, action->amount, &status);
        } else {
            status = "this background action has no reliable semantic control";
        }
        if (root_destroyed_ || capture_destroyed_ || !process_instance_alive()) {
            if (result == JC_ACTION_ACCEPTED || result == JC_ACTION_VERIFIED) {
                status += "; target exited after accepted input; application result is unknown";
            } else {
                status = "target exited during input; application result is unknown";
                result = JC_ACTION_UNKNOWN;
            }
        }
    } catch (const hresult_error& error) {
        // An exception can cross a UIA or SendInput call after the target has
        // already acted. Without a delivery boundary proof, only UNKNOWN is safe.
        status = "Windows accessibility operation may have taken effect: "
            + hresult_detail(error.code());
        result = JC_ACTION_UNKNOWN;
    } catch (...) {
        status = "Windows accessibility operation may have taken effect";
        result = JC_ACTION_UNKNOWN;
    }
    detail(message, capacity, status);
    return result;
}

std::mutex session_registry_mutex;
std::unordered_map<uintptr_t, std::shared_ptr<Session>> session_registry;
std::atomic<uintptr_t> next_session_id{1};

std::shared_ptr<Session> registered_session(void* handle) {
    if (handle == nullptr) return {};
    std::lock_guard lock(session_registry_mutex);
    auto found = session_registry.find(reinterpret_cast<uintptr_t>(handle));
    return found == session_registry.end() ? nullptr : found->second;
}

struct WindowList {
    jc_desktop_window* destination;
    uint32_t capacity;
    uint32_t total = 0;
};

BOOL CALLBACK enumerate_window(HWND window, LPARAM opaque) {
    auto* list = reinterpret_cast<WindowList*>(opaque);
    if (!IsWindowVisible(window) || (GetWindowLongPtrW(window, GWL_EXSTYLE) & WS_EX_TOOLWINDOW)
        || window_title(window).empty()) return TRUE;
    BOOL cloaked = FALSE;
    if (SUCCEEDED(DwmGetWindowAttribute(window, DWMWA_CLOAKED, &cloaked, sizeof(cloaked)))
        && cloaked) return TRUE;
    DWORD pid = 0;
    GetWindowThreadProcessId(window, &pid);
    uint64_t identity = 0;
    if (pid == 0 || !discover_process_instance(window, pid, &identity)) return TRUE;
    if (list->total < list->capacity && list->destination != nullptr) {
        fill_window(window, GetAncestor(window, GA_ROOTOWNER), identity,
                    &list->destination[list->total]);
    }
    ++list->total;
    return TRUE;
}

bool supported_windows_version(std::string* reason) {
    using RtlGetVersionFn = LONG (WINAPI*)(OSVERSIONINFOW*);
    HMODULE ntdll = GetModuleHandleW(L"ntdll.dll");
    auto rtl_get_version = ntdll == nullptr ? nullptr
            : reinterpret_cast<RtlGetVersionFn>(GetProcAddress(ntdll, "RtlGetVersion"));
    if (rtl_get_version == nullptr) {
        *reason = "cannot determine Windows build";
        return false;
    }
    OSVERSIONINFOW version{};
    version.dwOSVersionInfoSize = sizeof(version);
    if (rtl_get_version(&version) != 0 || version.dwMajorVersion < 10
        || version.dwBuildNumber < 22000) {
        *reason = "Windows 11 build 22000 or newer is required";
        return false;
    }
    return true;
}

bool graphics_available(std::string* reason) {
    if (!GraphicsCaptureSession::IsSupported()) {
        *reason = "Windows Graphics Capture is unavailable in this session";
        return false;
    }
    com_ptr<ID3D11Device> device;
    com_ptr<ID3D11DeviceContext> context;
    HRESULT hr = D3D11CreateDevice(nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr,
                                   D3D11_CREATE_DEVICE_BGRA_SUPPORT, nullptr, 0,
                                   D3D11_SDK_VERSION, device.put(), nullptr, context.put());
    if (FAILED(hr)) {
        hr = D3D11CreateDevice(nullptr, D3D_DRIVER_TYPE_WARP, nullptr,
                               D3D11_CREATE_DEVICE_BGRA_SUPPORT, nullptr, 0,
                               D3D11_SDK_VERSION, device.put(), nullptr, context.put());
    }
    if (FAILED(hr)) {
        *reason = "Direct3D 11 device unavailable: " + hresult_detail(hr);
        return false;
    }
    return true;
}

} // namespace

extern "C" {

int32_t jc_desktop_api_version(void) {
    return JC_DESKTOP_ABI_VERSION;
}

int32_t jc_desktop_probe(uint32_t* capabilities, char* detail_utf8,
                         uint32_t detail_capacity) {
    if (capabilities == nullptr) {
        detail(detail_utf8, detail_capacity, "capabilities pointer is required");
        return kInvalidArgument;
    }
    *capabilities = 0;
    std::string reason;
    if (!supported_windows_version(&reason)) {
        detail(detail_utf8, detail_capacity, reason);
        return kUnavailable;
    }
    Apartment apartment;
    if (!apartment.usable()) {
        detail(detail_utf8, detail_capacity, "WinRT apartment initialization failed");
        return kUnavailable;
    }
    try {
        if (!graphics_available(&reason)) {
            detail(detail_utf8, detail_capacity, reason);
            return kUnavailable;
        }
        *capabilities |= JC_CAP_CAPTURE | JC_CAP_FOREGROUND_INPUT | JC_CAP_DIRECTED_INPUT;
        com_ptr<IUIAutomation> automation;
        if (SUCCEEDED(CoCreateInstance(CLSID_CUIAutomation, nullptr, CLSCTX_INPROC_SERVER,
                                       __uuidof(IUIAutomation), automation.put_void()))) {
            *capabilities |= JC_CAP_SEMANTIC_INPUT;
            detail(detail_utf8, detail_capacity,
                   "WGC, UI Automation, classic button PostMessage, and approved foreground input available");
        } else {
            detail(detail_utf8, detail_capacity,
                   "WGC available; UI Automation unavailable; classic button PostMessage is limited");
        }
        return 0;
    } catch (const hresult_error& error) {
        detail(detail_utf8, detail_capacity,
               "WGC probe failed: " + hresult_detail(error.code()));
        return kUnavailable;
    } catch (...) {
        detail(detail_utf8, detail_capacity, "WGC probe failed");
        return kUnavailable;
    }
}

int32_t jc_desktop_request_permissions(uint32_t* capabilities, char* detail_utf8,
                                        uint32_t detail_capacity) {
    // The desktop WGC interop and UI Automation paths have no general
    // per-application consent dialog. Report current capabilities; a caller
    // must not pretend to grant access to protected windows or elevated UI.
    return jc_desktop_probe(capabilities, detail_utf8, detail_capacity);
}

int32_t jc_desktop_list_windows(jc_desktop_window* windows,
                                uint32_t capacity, uint32_t* count) {
    if (count == nullptr || (windows == nullptr && capacity != 0)) return kInvalidArgument;
    WindowList list{windows, capacity};
    if (!EnumWindows(enumerate_window, reinterpret_cast<LPARAM>(&list))) return kNativeFailure;
    *count = list.total;
    return 0;
}

int32_t jc_desktop_launch_application(const char* application_utf8,
                                      uint64_t* process_id,
                                      char* detail_utf8,
                                      uint32_t detail_capacity) {
    if (process_id != nullptr) *process_id = 0;
    if (process_id == nullptr) {
        detail(detail_utf8, detail_capacity,
               "application name and process ID output are required");
        return kInvalidArgument;
    }
    std::wstring name;
    if (!valid_application_request(application_utf8, &name)) {
        detail(detail_utf8, detail_capacity,
               "Use an exact installed application name or executable name, not a path or command");
        return kInvalidArgument;
    }
    std::wstring executable = registered_executable(name);
    if (executable.empty()) {
        auto shortcuts = installed_shortcuts(name);
        if (shortcuts.size() > 1) {
            detail(detail_utf8, detail_capacity,
                   "Multiple installed applications have this exact name; use an executable name");
            return -4;
        }
        if (shortcuts.size() == 1) executable = shortcut_executable(shortcuts.front());
    }
    if (!regular_executable(executable)) {
        detail(detail_utf8, detail_capacity,
               "Exact installed application did not resolve to a local executable");
        return kNativeFailure;
    }
    size_t separator = executable.find_last_of(L"\\/");
    std::wstring directory = separator == std::wstring::npos
            ? L"" : executable.substr(0, separator);
    SHELLEXECUTEINFOW launch{};
    launch.cbSize = sizeof(launch);
    launch.fMask = SEE_MASK_NOCLOSEPROCESS | SEE_MASK_NOASYNC | SEE_MASK_FLAG_NO_UI;
    launch.lpVerb = L"open";
    launch.lpFile = executable.c_str();
    launch.lpDirectory = directory.empty() ? nullptr : directory.c_str();
    launch.nShow = SW_SHOWNORMAL;
    if (!ShellExecuteExW(&launch)) {
        detail(detail_utf8, detail_capacity,
               "Application launch was not confirmed; discover windows before retrying");
        return -5;
    }
    ProcessHandle process{launch.hProcess};
    if (!process || (*process_id = GetProcessId(process.get())) == 0) {
        detail(detail_utf8, detail_capacity,
               "Application activation returned no process ID; discover windows before retrying");
        return -5;
    }
    if (WaitForSingleObject(process.get(), 0) != WAIT_TIMEOUT) {
        detail(detail_utf8, detail_capacity,
               "Application launcher exited before its window was verified; discover windows before retrying");
        return -7;
    }
    detail(detail_utf8, detail_capacity,
           "Application launch or activation accepted; discover its window before opening a session");
    return 0;
}

int32_t jc_desktop_process_application_id(uint64_t process_id,
                                          char* application_id_utf8, uint32_t capacity) {
    if (!application_id_utf8 || capacity == 0 || process_id == 0
            || process_id > std::numeric_limits<DWORD>::max()) return kInvalidArgument;
    application_id_utf8[0] = 0;
    std::wstring name = process_name(static_cast<DWORD>(process_id));
    if (name.size() < 5 || _wcsicmp(name.c_str() + name.size() - 4, L".exe") != 0)
        return kUnavailable;
    std::transform(name.begin(), name.end(), name.begin(),
                   [](wchar_t ch) { return std::towlower(ch); });
    detail(application_id_utf8, capacity, to_utf8(name));
    return application_id_utf8[0] ? 0 : kUnavailable;
}

int32_t jc_desktop_resolve_application_id(const char* application_utf8,
                                          char* application_id_utf8, uint32_t capacity) {
    if (!application_id_utf8 || capacity == 0) return kInvalidArgument;
    application_id_utf8[0] = 0;
    std::wstring name;
    if (!valid_application_request(application_utf8, &name)) return kInvalidArgument;
    std::wstring id = resolved_executable_id(name);
    if (id.empty()) return kUnavailable;
    detail(application_id_utf8, capacity, to_utf8(id));
    return application_id_utf8[0] ? 0 : kUnavailable;
}

int32_t jc_desktop_list_applications(char* catalog_utf8, uint32_t capacity,
                                      uint32_t* required_bytes) {
    if (!required_bytes || (!catalog_utf8 && capacity != 0)) return kInvalidArgument;
    *required_bytes = 0;
    if (catalog_utf8 && capacity) catalog_utf8[0] = '\0';
    try {
        return jc_application_catalog::copyJson(installed_application_catalog(),
                catalog_utf8, capacity, required_bytes);
    } catch (...) {
        return kNativeFailure;
    }
}

void* jc_desktop_open(uint64_t process_id, uint64_t window_id,
                      uint64_t process_instance_id_expected,
                      char* detail_utf8, uint32_t detail_capacity) {
    std::string reason;
    if (!supported_windows_version(&reason)) {
        detail(detail_utf8, detail_capacity, reason);
        return nullptr;
    }
    if (process_id == 0 || process_id > std::numeric_limits<DWORD>::max()
        || window_id == 0 || process_instance_id_expected == 0) {
        detail(detail_utf8, detail_capacity, "invalid process or window id");
        return nullptr;
    }
    HWND selected = reinterpret_cast<HWND>(static_cast<uintptr_t>(window_id));
    DWORD pid = static_cast<DWORD>(process_id);
    if (!valid_window(selected, pid)) {
        detail(detail_utf8, detail_capacity, "window closed or belongs to another process");
        return nullptr;
    }
    if (!IsWindowVisible(selected)) {
        detail(detail_utf8, detail_capacity, "target window is not visible");
        return nullptr;
    }
    HWND root = GetAncestor(selected, GA_ROOTOWNER);
    if (!valid_window(root, pid)) root = selected;
    DWORD root_process_id = 0;
    DWORD root_thread_id = GetWindowThreadProcessId(root, &root_process_id);
    if (root_thread_id == 0 || root_process_id != pid) {
        detail(detail_utf8, detail_capacity, "target root window changed before capture");
        return nullptr;
    }
    ProcessHandle process{OpenProcess(SYNCHRONIZE | PROCESS_QUERY_LIMITED_INFORMATION,
                                      FALSE, pid)};
    if (!process) {
        detail(detail_utf8, detail_capacity,
               "cannot bind target process instance (access denied or process exited)");
        return nullptr;
    }
    FILETIME created{}, exited{}, kernel{}, user{};
    if (!GetProcessTimes(process.get(), &created, &exited, &kernel, &user)
        || WaitForSingleObject(process.get(), 0) != WAIT_TIMEOUT
        || !valid_window(selected, pid) || !valid_window(root, pid)) {
        detail(detail_utf8, detail_capacity, "target process or window changed before capture");
        return nullptr;
    }
    if (process_instance_id(created) != process_instance_id_expected) {
        detail(detail_utf8, detail_capacity,
               "target process was replaced after discovery; discover it again");
        return nullptr;
    }
    DWORD verified_process_id = 0;
    if (GetWindowThreadProcessId(root, &verified_process_id) != root_thread_id
        || verified_process_id != pid) {
        detail(detail_utf8, detail_capacity, "target root window changed before capture");
        return nullptr;
    }
    Apartment apartment;
    if (!apartment.usable()) {
        detail(detail_utf8, detail_capacity, "WinRT apartment initialization failed");
        return nullptr;
    }
    try {
        auto session = std::make_shared<Session>(pid, root_thread_id, root,
                                                 std::move(process), created);
        session->start();
        uintptr_t id = next_session_id.fetch_add(1);
        {
            std::lock_guard lock(session_registry_mutex);
            session_registry.emplace(id, std::move(session));
        }
        detail(detail_utf8, detail_capacity, "window capture started");
        return reinterpret_cast<void*>(id);
    } catch (const hresult_error& error) {
        detail(detail_utf8, detail_capacity,
               "could not capture this window: " + hresult_detail(error.code()));
    } catch (...) {
        detail(detail_utf8, detail_capacity, "could not capture this window");
    }
    return nullptr;
}

int32_t jc_desktop_poll_frame(void* handle, jc_desktop_frame* frame,
                              uint32_t timeout_millis) {
    auto session = registered_session(handle);
    if (!session || frame == nullptr) return kInvalidArgument;
    Apartment apartment;
    if (!apartment.usable()) return kUnavailable;
    return session->poll(frame, timeout_millis);
}

void jc_desktop_release_frame(jc_desktop_frame* frame) {
    if (frame == nullptr) return;
    std::free(frame->pixels);
    std::memset(frame, 0, sizeof(*frame));
}

int32_t jc_desktop_list_elements(void* handle, uint64_t expected_generation,
                                  uint64_t expected_content_revision,
                                  jc_desktop_element* elements, uint32_t capacity,
                                  uint32_t* count) {
    auto session = registered_session(handle);
    if (!session) return kInvalidArgument;
    Apartment apartment;
    if (!apartment.usable()) return kUnavailable;
    return session->list_elements(expected_generation, expected_content_revision,
                                  elements, capacity, count);
}

int32_t jc_desktop_current_window(void* handle, jc_desktop_window* window) {
    auto session = registered_session(handle);
    if (!session || window == nullptr) return kInvalidArgument;
    Apartment apartment;
    if (!apartment.usable()) return kUnavailable;
    return session->current_window(window);
}

int32_t jc_desktop_prepare_foreground(void* handle, char* detail_utf8,
                                       uint32_t detail_capacity) {
    auto session = registered_session(handle);
    if (!session) {
        detail(detail_utf8, detail_capacity, "session closed");
        return kInvalidArgument;
    }
    Apartment apartment;
    if (!apartment.usable()) {
        detail(detail_utf8, detail_capacity, "WinRT apartment initialization failed");
        return kUnavailable;
    }
    try {
        return session->prepare_foreground(detail_utf8, detail_capacity);
    } catch (...) {
        detail(detail_utf8, detail_capacity, "foreground preparation failed");
        return kNativeFailure;
    }
}

void jc_desktop_restore_foreground(void* handle) {
    auto session = registered_session(handle);
    if (session) session->restore_foreground();
}

int32_t jc_desktop_perform(void* handle, const jc_desktop_action* action,
                           char* detail_utf8, uint32_t detail_capacity) {
    auto session = registered_session(handle);
    if (!session || action == nullptr) {
        detail(detail_utf8, detail_capacity, "session closed or action missing");
        return JC_ACTION_FAILED;
    }
    Apartment apartment;
    if (!apartment.usable()) {
        detail(detail_utf8, detail_capacity, "WinRT apartment initialization failed");
        return JC_ACTION_FAILED;
    }
    return session->perform(action, detail_utf8, detail_capacity);
}

void jc_desktop_close(void* handle) {
    if (handle == nullptr) return;
    std::shared_ptr<Session> session;
    {
        std::lock_guard lock(session_registry_mutex);
        auto found = session_registry.find(reinterpret_cast<uintptr_t>(handle));
        if (found == session_registry.end()) return;
        session = std::move(found->second);
        session_registry.erase(found);
    }
    Apartment apartment;
    session->close();
}

} // extern "C"
