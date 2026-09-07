#include "guard.hpp"
#include <atomic>
#include <chrono>
#include <cstring>
#include <memory>
#include <sstream>
#include <thread>
#include <userenv.h>

namespace javaclaw {
extern Handle authorizeChild(HANDLE pipe, DWORD processId, PSID expectedSid);
static std::atomic<bool> stopping{false};
static SERVICE_STATUS_HANDLE serviceHandle = nullptr;
static SERVICE_STATUS serviceStatus{};

static void report(DWORD state, DWORD error = NO_ERROR) {
    serviceStatus.dwServiceType = SERVICE_WIN32_OWN_PROCESS;
    serviceStatus.dwCurrentState = state;
    serviceStatus.dwControlsAccepted = state == SERVICE_RUNNING ? SERVICE_ACCEPT_STOP | SERVICE_ACCEPT_SHUTDOWN : 0;
    serviceStatus.dwWin32ExitCode = error;
    serviceStatus.dwWaitHint = state == SERVICE_STOP_PENDING ? 10000 : 0;
    SetServiceStatus(serviceHandle, &serviceStatus);
}

static DWORD WINAPI control(DWORD event, DWORD, void*, void*) {
    if (event == SERVICE_CONTROL_STOP || event == SERVICE_CONTROL_SHUTDOWN) {
        stopping.store(true);
        report(SERVICE_STOP_PENDING);
    }
    return NO_ERROR;
}

static std::string readLine(HANDLE pipe, ULONGLONG deadline) {
    std::string value;
    while (!stopping.load() && GetTickCount64() < deadline) {
        DWORD available = 0;
        require(PeekNamedPipe(pipe, nullptr, 0, nullptr, &available, nullptr), "PeekNamedPipe");
        if (!available) {
            Sleep(20);
            continue;
        }
        char byte = 0;
        DWORD count = 0;
        require(ReadFile(pipe, &byte, 1, &count, nullptr) && count == 1, "ReadFile");
        if (byte == '\n') {
            return value;
        }
        if ((byte < 0x20 && byte != '\t') || byte > 0x7e || value.size() >= 1024) {
            throw std::runtime_error("NETWORK_GUARD_PROTOCOL_INVALID");
        }
        value.push_back(byte);
    }
    throw std::runtime_error("NETWORK_GUARD_DEADLINE");
}

static void reply(HANDLE pipe, const char* value) {
    DWORD written = 0;
    DWORD length = static_cast<DWORD>(strlen(value));
    require(WriteFile(pipe, value, length, &written, nullptr) && written == length, "WriteFile");
}

static std::vector<std::string> split(const std::string& line) {
    std::vector<std::string> fields;
    std::istringstream stream(line);
    std::string field;
    while (std::getline(stream, field, '\t')) {
        fields.push_back(field);
    }
    return fields;
}

static UINT64 number(const std::string& value, UINT64 maximum) {
    if (value.empty() || value.find_first_not_of("0123456789") != std::string::npos) {
        throw std::runtime_error("NETWORK_GUARD_NUMBER_INVALID");
    }
    UINT64 result = std::stoull(value);
    if (!result || result > maximum) {
        throw std::runtime_error("NETWORK_GUARD_LIMIT_INVALID");
    }
    return result;
}

static void serve(HANDLE rawPipe) {
    Handle pipe(rawPipe);
    try {
        auto request = split(readLine(pipe.get(), GetTickCount64() + 10000));
        // v1 ATTACH pid port timeoutMillis memoryBytes processCount profileName sid；无 argv、路径或任意系统命令。
        if (request.size() != 9 || request[0] != "JCG1" || request[1] != "ATTACH") {
            throw std::runtime_error("NETWORK_GUARD_PROTOCOL_INVALID");
        }
        DWORD processId = static_cast<DWORD>(number(request[2], MAXDWORD));
        UINT16 port = static_cast<UINT16>(number(request[3], 65535));
        DWORD timeout = static_cast<DWORD>(number(request[4], 86400000));
        UINT64 memory = number(request[5], 64ULL * 1024 * 1024 * 1024);
        DWORD processes = static_cast<DWORD>(number(request[6], 1024));
        std::wstring profile(request[7].begin(), request[7].end());
        std::wstring sidText(request[8].begin(), request[8].end());
        if (profile.rfind(profilePrefix, 0) != 0 || profile.size() != wcslen(profilePrefix) + 32 ||
            profile.substr(wcslen(profilePrefix)).find_first_not_of(L"0123456789abcdef") != std::wstring::npos) {
            throw std::runtime_error("NETWORK_GUARD_PROFILE_INVALID");
        }
        PSID declared = nullptr;
        require(ConvertStringSidToSidW(sidText.c_str(), &declared), "ConvertStringSidToSidW");
        PSID derived = nullptr;
        HRESULT derivedStatus = DeriveAppContainerSidFromAppContainerName(profile.c_str(), &derived);
        bool identityMatches = SUCCEEDED(derivedStatus) && derived && EqualSid(declared, derived);
        if (derived) {
            FreeSid(derived);
        }
        if (!identityMatches) {
            LocalFree(declared);
            throw std::runtime_error("NETWORK_GUARD_PROFILE_SID_MISMATCH");
        }
        std::unique_ptr<NetworkLease> lease;
        try {
            Handle child = authorizeChild(pipe.get(), processId, declared);
            lease = std::make_unique<NetworkLease>(child.get(), declared, port, memory, processes, timeout);
        } catch (...) {
            LocalFree(declared);
            throw;
        }
        LocalFree(declared);
        reply(pipe.get(), "OK\n");
        ULONGLONG deadline = GetTickCount64() + timeout;
        bool revoked = false;
        while (true) {
            auto operation = readLine(pipe.get(), deadline);
            if (operation == "REVOKE" && !revoked) {
                lease->revoke();
                revoked = true;
                deadline = GetTickCount64() + 5000;
                reply(pipe.get(), "OK\n");
            } else if (operation == "CLOSE" && revoked) {
                lease->close();
                reply(pipe.get(), "OK\n");
                return;
            } else {
                throw std::runtime_error("NETWORK_GUARD_OPERATION_INVALID");
            }
        }
    } catch (...) {
        // 不返回内部异常、宿主路径或 Token；连接作用域析构先撤授权再终止进程树。
        try {
            reply(pipe.get(), "ERROR NETWORK_GUARD_REJECTED\n");
        } catch (...) {
        }
    }
}

struct Worker {
    std::shared_ptr<std::atomic<bool>> done;
    std::jthread thread;
};

static void acceptConnections() {
    PSECURITY_DESCRIPTOR descriptor = nullptr;
    // 排除 FILE_CREATE_PIPE_INSTANCE；只允许本地交互用户读写已有 Pipe，不允许创建伪实例。
    require(ConvertStringSecurityDescriptorToSecurityDescriptorW(L"O:SYG:SYD:P(A;;GA;;;SY)(A;;GA;;;BA)(A;;0x12019b;;;IU)",
                                                                 SDDL_REVISION_1, &descriptor, nullptr),
            "Pipe DACL");
    SECURITY_ATTRIBUTES security{sizeof(security), descriptor, FALSE};
    std::vector<Worker> workers;
    bool first = true;
    try {
        while (!stopping.load()) {
            std::erase_if(workers, [](const Worker& worker) { return worker.done->load(); });
            if (workers.size() >= 64) {
                Sleep(50);
                continue;
            }
            DWORD openMode = PIPE_ACCESS_DUPLEX | ((first || workers.empty()) ? FILE_FLAG_FIRST_PIPE_INSTANCE : 0);
            Handle pipe(CreateNamedPipeW(pipeName, openMode,
                                         PIPE_TYPE_BYTE | PIPE_READMODE_BYTE | PIPE_NOWAIT | PIPE_REJECT_REMOTE_CLIENTS,
                                         64, 4096, 4096, 1000, &security));
            require(pipe.get() != INVALID_HANDLE_VALUE, "CreateNamedPipeW");
            first = false;
            bool connected = false;
            while (!stopping.load()) {
                if (ConnectNamedPipe(pipe.get(), nullptr) || GetLastError() == ERROR_PIPE_CONNECTED) {
                    connected = true;
                    break;
                }
                if (GetLastError() != ERROR_PIPE_LISTENING) {
                    require(false, "ConnectNamedPipe");
                }
                Sleep(20);
            }
            if (!connected) {
                break;
            }
            auto done = std::make_shared<std::atomic<bool>>(false);
            HANDLE accepted = pipe.release();
            workers.push_back({done, std::jthread([accepted, done] {
                                   serve(accepted);
                                   done->store(true);
                               })});
        }
    } catch (...) {
        stopping.store(true);
        LocalFree(descriptor);
        throw;
    }
    LocalFree(descriptor);
    // jthread join 等待每连接撤允许并关闭独占根 Job 后才向 SCM 报告 STOPPED。
}

static void WINAPI serviceMain(DWORD, wchar_t**) {
    serviceHandle = RegisterServiceCtrlHandlerExW(serviceName, control, nullptr);
    if (!serviceHandle) {
        return;
    }
    report(SERVICE_START_PENDING);
    try {
        verifyInstalledImage(executablePath(GetCurrentProcess()));
        recoverOwnedPolicy(false);
        report(SERVICE_RUNNING);
        acceptConnections();
        recoverOwnedPolicy(false);
        report(SERVICE_STOPPED);
    } catch (...) {
        report(SERVICE_STOPPED, ERROR_SERVICE_SPECIFIC_ERROR);
    }
}

int runService() {
    SERVICE_TABLE_ENTRYW table[] = {{const_cast<wchar_t*>(serviceName), serviceMain}, {nullptr, nullptr}};
    return StartServiceCtrlDispatcherW(table) ? 0 : static_cast<int>(GetLastError());
}
} // namespace javaclaw
