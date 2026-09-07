#include "guard.hpp"
#include <tlhelp32.h>
#include <wintrust.h>
#include <softpub.h>
#include <filesystem>

namespace javaclaw {
std::wstring executablePath(HANDLE process) {
    std::wstring path(32768, L'\0');
    DWORD length = static_cast<DWORD>(path.size());
    require(QueryFullProcessImageNameW(process, 0, path.data(), &length), "QueryFullProcessImageNameW");
    path.resize(length);
    return std::filesystem::canonical(path).wstring();
}

void verifyInstalledImage(const std::wstring& path) {
    WINTRUST_FILE_INFO file{};
    file.cbStruct = sizeof(file);
    file.pcwszFilePath = path.c_str();
    WINTRUST_DATA trust{};
    trust.cbStruct = sizeof(trust);
    trust.dwUIChoice = WTD_UI_NONE;
    trust.fdwRevocationChecks = WTD_REVOKE_NONE;
    trust.dwUnionChoice = WTD_CHOICE_FILE;
    trust.pFile = &file;
    trust.dwStateAction = WTD_STATEACTION_VERIFY;
    trust.dwProvFlags = WTD_CACHE_ONLY_URL_RETRIEVAL;
    GUID policy = WINTRUST_ACTION_GENERIC_VERIFY_V2;
    LONG result = WinVerifyTrust(nullptr, &policy, &trust);
    trust.dwStateAction = WTD_STATEACTION_CLOSE;
    WinVerifyTrust(nullptr, &policy, &trust);
    if (result != ERROR_SUCCESS) {
        throw std::runtime_error("NETWORK_GUARD_SIGNATURE_INVALID");
    }
}

static std::vector<BYTE> tokenInformation(HANDLE token, TOKEN_INFORMATION_CLASS type) {
    DWORD length = 0;
    GetTokenInformation(token, type, nullptr, 0, &length);
    if (length < 1 || length > 65536) {
        throw std::runtime_error("NETWORK_GUARD_TOKEN_INVALID");
    }
    std::vector<BYTE> data(length);
    require(GetTokenInformation(token, type, data.data(), length, &length), "GetTokenInformation");
    return data;
}

static Handle openToken(HANDLE process) {
    HANDLE token = nullptr;
    require(OpenProcessToken(process, TOKEN_QUERY, &token), "OpenProcessToken");
    return Handle(token);
}

static DWORD parentProcess(DWORD processId) {
    Handle snapshot(CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0));
    require(snapshot.get() != INVALID_HANDLE_VALUE, "CreateToolhelp32Snapshot");
    PROCESSENTRY32W entry{};
    entry.dwSize = sizeof(entry);
    if (Process32FirstW(snapshot.get(), &entry)) {
        do {
            if (entry.th32ProcessID == processId) {
                return entry.th32ParentProcessID;
            }
        } while (Process32NextW(snapshot.get(), &entry));
    }
    throw std::runtime_error("NETWORK_GUARD_PROCESS_MISSING");
}

// 仅为同一 Pipe peer 创建的低完整性 AppContainer 子进程装配 Job；PID 不能指向任意系统进程。
Handle authorizeChild(HANDLE pipe, DWORD processId, PSID expectedSid) {
    ULONG clientPid = 0;
    require(GetNamedPipeClientProcessId(pipe, &clientPid), "GetNamedPipeClientProcessId");
    if (parentProcess(processId) != clientPid) {
        throw std::runtime_error("NETWORK_GUARD_PARENT_MISMATCH");
    }
    Handle client(OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, clientPid));
    require(client.get() != nullptr, "OpenProcess(client)");
    auto installedRoot =
        std::filesystem::path(executablePath(GetCurrentProcess())).parent_path().parent_path().parent_path();
    auto expectedJava = std::filesystem::canonical(installedRoot / L"runtime" / L"bin" / L"java.exe").wstring();
    auto peerImage = executablePath(client.get());
    if (_wcsicmp(peerImage.c_str(), expectedJava.c_str()) != 0) {
        throw std::runtime_error("NETWORK_GUARD_CLIENT_IMAGE_INVALID");
    }
    verifyInstalledImage(peerImage);
    Handle child(OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION | PROCESS_SET_QUOTA | PROCESS_TERMINATE | SYNCHRONIZE,
                             FALSE, processId));
    require(child.get() != nullptr, "OpenProcess(child)");
    Handle clientToken = openToken(client.get());
    Handle childToken = openToken(child.get());
    auto clientUser = tokenInformation(clientToken.get(), TokenUser);
    auto childUser = tokenInformation(childToken.get(), TokenUser);
    if (!EqualSid(reinterpret_cast<TOKEN_USER*>(clientUser.data())->User.Sid,
                  reinterpret_cast<TOKEN_USER*>(childUser.data())->User.Sid)) {
        throw std::runtime_error("NETWORK_GUARD_USER_MISMATCH");
    }
    auto container = tokenInformation(childToken.get(), TokenIsAppContainer);
    auto sid = tokenInformation(childToken.get(), TokenAppContainerSid);
    auto integrity = tokenInformation(childToken.get(), TokenIntegrityLevel);
    auto label = reinterpret_cast<TOKEN_MANDATORY_LABEL*>(integrity.data());
    DWORD rid = *GetSidSubAuthority(label->Label.Sid, *GetSidSubAuthorityCount(label->Label.Sid) - 1);
    if (*reinterpret_cast<DWORD*>(container.data()) != 1 || rid > SECURITY_MANDATORY_LOW_RID ||
        !EqualSid(reinterpret_cast<TOKEN_APPCONTAINER_INFORMATION*>(sid.data())->TokenAppContainer, expectedSid)) {
        throw std::runtime_error("NETWORK_GUARD_APPCONTAINER_MISMATCH");
    }
    return child;
}

bool hasLiveContainer(PSID expectedSid) {
    Handle snapshot(CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0));
    require(snapshot.get() != INVALID_HANDLE_VALUE, "CreateToolhelp32Snapshot");
    PROCESSENTRY32W entry{};
    entry.dwSize = sizeof(entry);
    if (!Process32FirstW(snapshot.get(), &entry)) {
        return false;
    }
    do {
        Handle process(OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, entry.th32ProcessID));
        if (!process.get()) {
            continue;
        }
        try {
            Handle token = openToken(process.get());
            auto sid = tokenInformation(token.get(), TokenAppContainerSid);
            PSID actual = reinterpret_cast<TOKEN_APPCONTAINER_INFORMATION*>(sid.data())->TokenAppContainer;
            if (actual && EqualSid(actual, expectedSid)) {
                return true;
            }
        } catch (...) {
            // 受保护的系统进程无法读取 Token；它们不使用本服务产生的随机 AppContainer SID。
        }
    } while (Process32NextW(snapshot.get(), &entry));
    return false;
}
} // namespace javaclaw
