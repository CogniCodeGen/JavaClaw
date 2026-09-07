#include "guard.hpp"
#include <aclapi.h>
#include <filesystem>
#include <shlobj.h>
#include <iostream>

namespace javaclaw {
class ServiceHandle {
    SC_HANDLE value_;

  public:
    explicit ServiceHandle(SC_HANDLE value) : value_(value) {
        require(value != nullptr, "Service handle");
    }
    ~ServiceHandle() {
        CloseServiceHandle(value_);
    }
    SC_HANDLE get() const {
        return value_;
    }
};

static bool privilegedSid(PSID sid) {
    return IsWellKnownSid(sid, WinLocalSystemSid) || IsWellKnownSid(sid, WinBuiltinAdministratorsSid);
}

static void verifyProtectedAcl(const std::filesystem::path& path) {
    PACL dacl = nullptr;
    PSID owner = nullptr;
    PSECURITY_DESCRIPTOR descriptor = nullptr;
    status(GetNamedSecurityInfoW(path.c_str(), SE_FILE_OBJECT, DACL_SECURITY_INFORMATION | OWNER_SECURITY_INFORMATION,
                                 &owner, nullptr, &dacl, nullptr, &descriptor),
           "GetNamedSecurityInfoW");
    try {
        if (!dacl || !owner || !privilegedSid(owner)) {
            throw std::runtime_error("NETWORK_GUARD_INSTALL_OWNER_INVALID");
        }
        for (DWORD index = 0; index < dacl->AceCount; ++index) {
            void* raw = nullptr;
            require(GetAce(dacl, index, &raw), "GetAce");
            auto header = static_cast<ACE_HEADER*>(raw);
            if (header->AceFlags & INHERIT_ONLY_ACE) {
                continue;
            }
            if (header->AceType == ACCESS_DENIED_ACE_TYPE) {
                continue;
            }
            if (header->AceType != ACCESS_ALLOWED_ACE_TYPE) {
                throw std::runtime_error("NETWORK_GUARD_INSTALL_ACL_UNSUPPORTED");
            }
            auto allowed = static_cast<ACCESS_ALLOWED_ACE*>(raw);
            constexpr ACCESS_MASK mutation = GENERIC_ALL | GENERIC_WRITE | FILE_WRITE_DATA | FILE_APPEND_DATA |
                                             FILE_WRITE_EA | FILE_WRITE_ATTRIBUTES | FILE_DELETE_CHILD | WRITE_DAC |
                                             WRITE_OWNER | DELETE;
            if ((allowed->Mask & mutation) && !privilegedSid(&allowed->SidStart)) {
                throw std::runtime_error("NETWORK_GUARD_INSTALL_WRITABLE_BY_USER");
            }
        }
    } catch (...) {
        LocalFree(descriptor);
        throw;
    }
    LocalFree(descriptor);
}

static void protectInstallation(const std::wstring& executable) {
    PWSTR known = nullptr;
    HRESULT located = SHGetKnownFolderPath(FOLDERID_ProgramFiles, KF_FLAG_DEFAULT, nullptr, &known);
    if (FAILED(located)) {
        throw std::runtime_error("NETWORK_GUARD_INSTALL_ROOT_UNAVAILABLE");
    }
    std::filesystem::path programFiles(known);
    CoTaskMemFree(known);
    auto target = std::filesystem::canonical(executable);
    auto root = std::filesystem::canonical(programFiles);
    auto relative = target.lexically_relative(root);
    if (relative.empty() || *relative.begin() == L"..") {
        throw std::runtime_error("NETWORK_GUARD_PROTECTED_INSTALL_REQUIRED");
    }
    // 检查所有可替换层级的 owner 和每一项写入 ACE，不能只检查常见用户组而漏掉具体用户。
    for (auto path = target; path != root; path = path.parent_path()) {
        DWORD attributes = GetFileAttributesW(path.c_str());
        if (attributes == INVALID_FILE_ATTRIBUTES || (attributes & FILE_ATTRIBUTE_REPARSE_POINT)) {
            throw std::runtime_error("NETWORK_GUARD_REPARSE_INSTALL_REJECTED");
        }
        verifyProtectedAcl(path);
    }
}

static void stop(SC_HANDLE service) {
    SERVICE_STATUS current{};
    if (!ControlService(service, SERVICE_CONTROL_STOP, &current) && GetLastError() != ERROR_SERVICE_NOT_ACTIVE) {
        require(false, "ControlService(STOP)");
    }
    ULONGLONG deadline = GetTickCount64() + 30000;
    while (GetTickCount64() < deadline) {
        require(QueryServiceStatus(service, &current), "QueryServiceStatus");
        if (current.dwCurrentState == SERVICE_STOPPED) {
            return;
        }
        Sleep(50);
    }
    throw std::runtime_error("NETWORK_GUARD_STOP_TIMEOUT");
}

int installService(bool remove) {
    auto image = executablePath(GetCurrentProcess());
    verifyInstalledImage(image);
    if (!remove) {
        protectInstallation(image);
    }
    ServiceHandle manager(OpenSCManagerW(nullptr, nullptr, SC_MANAGER_CONNECT | SC_MANAGER_CREATE_SERVICE));
    SC_HANDLE existing =
        OpenServiceW(manager.get(), serviceName,
                     SERVICE_QUERY_STATUS | SERVICE_STOP | DELETE | SERVICE_CHANGE_CONFIG | SERVICE_START);
    if (remove) {
        if (existing) {
            ServiceHandle service(existing);
            stop(service.get());
            recoverOwnedPolicy(true);
            require(DeleteService(service.get()), "DeleteService");
        } else if (GetLastError() == ERROR_SERVICE_DOES_NOT_EXIST) {
            recoverOwnedPolicy(true);
        } else {
            require(false, "OpenServiceW");
        }
        return 0;
    }
    std::wstring command = L"\"" + image + L"\" --service";
    if (!existing) {
        if (GetLastError() != ERROR_SERVICE_DOES_NOT_EXIST) {
            require(false, "OpenServiceW");
        }
        existing = CreateServiceW(manager.get(), serviceName, L"JavaClaw bounded network guard",
                                  SERVICE_QUERY_STATUS | SERVICE_START | SERVICE_CHANGE_CONFIG | SERVICE_STOP,
                                  SERVICE_WIN32_OWN_PROCESS, SERVICE_AUTO_START, SERVICE_ERROR_NORMAL, command.c_str(),
                                  nullptr, nullptr, L"BFE\0MpsSvc\0\0", nullptr, nullptr);
    } else {
        // 升级前先终止所有租约并清理自有例外，之后才能替换固定程序路径。
        ServiceHandle previous(existing);
        stop(previous.get());
        recoverOwnedPolicy(false);
        require(ChangeServiceConfigW(previous.get(), SERVICE_NO_CHANGE, SERVICE_AUTO_START, SERVICE_NO_CHANGE,
                                     command.c_str(), nullptr, nullptr, L"BFE\0MpsSvc\0\0", nullptr, nullptr, nullptr),
                "ChangeServiceConfigW");
        existing =
            OpenServiceW(manager.get(), serviceName, SERVICE_QUERY_STATUS | SERVICE_START | SERVICE_CHANGE_CONFIG);
    }
    ServiceHandle service(existing);
    SERVICE_DESCRIPTIONW description{const_cast<wchar_t*>(
        L"Restricts JavaClaw AppContainers to one approved loopback proxy; no command execution API.")};
    require(ChangeServiceConfig2W(service.get(), SERVICE_CONFIG_DESCRIPTION, &description), "Service description");
    SC_ACTION actions[] = {{SC_ACTION_RESTART, 1000}, {SC_ACTION_RESTART, 5000}, {SC_ACTION_NONE, 0}};
    SERVICE_FAILURE_ACTIONSW failures{3600, nullptr, nullptr, 3, actions};
    require(ChangeServiceConfig2W(service.get(), SERVICE_CONFIG_FAILURE_ACTIONS, &failures), "Service recovery");
    SERVICE_FAILURE_ACTIONS_FLAG restartOnFailure{TRUE};
    require(ChangeServiceConfig2W(service.get(), SERVICE_CONFIG_FAILURE_ACTIONS_FLAG, &restartOnFailure),
            "Service failure recovery");
    if (!StartServiceW(service.get(), 0, nullptr) && GetLastError() != ERROR_SERVICE_ALREADY_RUNNING) {
        require(false, "StartServiceW");
    }
    return 0;
}
} // namespace javaclaw

int wmain(int count, wchar_t** arguments) {
    try {
        if (count != 2) {
            return 2;
        }
        if (wcscmp(arguments[1], L"--service") == 0) {
            return javaclaw::runService();
        }
        if (wcscmp(arguments[1], L"--install") == 0) {
            return javaclaw::installService(false);
        }
        if (wcscmp(arguments[1], L"--uninstall") == 0) {
            return javaclaw::installService(true);
        }
        return 2;
    } catch (...) {
        std::cerr << "NETWORK_GUARD_INSTALL_OR_RECOVERY_FAILED\n";
        return 1;
    }
}
