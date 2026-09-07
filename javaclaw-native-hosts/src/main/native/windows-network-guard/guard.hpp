#pragma once
#define WIN32_LEAN_AND_MEAN
#include <winsock2.h>
#include <windows.h>
#include <fwpmu.h>
#include <netfw.h>
#include <sddl.h>
#include <string>
#include <vector>
#include <stdexcept>

namespace javaclaw {
inline constexpr wchar_t serviceName[] = L"JavaClawNetworkGuard";
inline constexpr wchar_t pipeName[] = L"\\\\.\\pipe\\JavaClaw.NetworkGuard.v1";
inline constexpr wchar_t profilePrefix[] = L"JavaClaw.Sandbox.v6.";
extern const GUID providerKey;
extern const GUID sublayerKey;

// 服务独占的句柄不继承、不发给客户端；异常析构同样触发 Job 的 kill-on-close。
class Handle {
    HANDLE value_ = nullptr;

  public:
    explicit Handle(HANDLE value = nullptr) : value_(value) {}
    Handle(const Handle&) = delete;
    Handle& operator=(const Handle&) = delete;
    Handle(Handle&& other) noexcept : value_(other.release()) {}
    Handle& operator=(Handle&& other) noexcept {
        if (this != &other) {
            reset(other.release());
        }
        return *this;
    }
    ~Handle() {
        reset();
    }
    HANDLE get() const {
        return value_;
    }
    HANDLE release() {
        HANDLE value = value_;
        value_ = nullptr;
        return value;
    }
    void reset(HANDLE value = nullptr) {
        if (value_ && value_ != INVALID_HANDLE_VALUE) {
            CloseHandle(value_);
        }
        value_ = value;
    }
};

inline void require(bool result, const char* operation) {
    if (!result) {
        throw std::runtime_error(std::string(operation) + ":" + std::to_string(GetLastError()));
    }
}
inline void status(DWORD result, const char* operation) {
    if (result != ERROR_SUCCESS) {
        throw std::runtime_error(std::string(operation) + ":" + std::to_string(result));
    }
}

std::wstring executablePath(HANDLE process);
void verifyInstalledImage(const std::wstring& path);
void modifyLoopback(PSID sid, bool add);
void recoverOwnedPolicy(bool uninstall);
int installService(bool remove);
int runService();

// 每连接一个租约。永久拒绝在独立持久会话写入，允许项仅属于本动态会话。
class NetworkLease {
    HANDLE engine_ = nullptr;
    std::vector<UINT64> permits_;
    std::vector<UINT64> denies_;
    std::vector<BYTE> sid_;
    Handle job_;
    bool loopback_ = false;
    bool revoked_ = false;

  public:
    NetworkLease(HANDLE process, PSID sid, UINT16 port, UINT64 memoryBytes, DWORD processes, DWORD timeoutMillis);
    NetworkLease(const NetworkLease&) = delete;
    ~NetworkLease();
    void revoke();
    void close();
};
} // namespace javaclaw
