// Included inside the desktop bridge's private namespace; no activation/input.
std::wstring application_catalog_key(std::wstring value) {
    std::transform(value.begin(), value.end(), value.begin(),
                   [](wchar_t ch) { return std::towlower(ch); });
    return value;
}

std::wstring application_catalog_stem(const std::wstring& name) {
    return name.size() > 4 && _wcsicmp(name.c_str() + name.size() - 4, L".exe") == 0
        ? name.substr(0, name.size() - 4) : name;
}

void add_application_catalog(const std::wstring& launch_name,
        const std::wstring& executable, const std::wstring& label,
        std::vector<jc_application_catalog::Application>& applications,
        std::unordered_map<std::string, size_t>& indices) {
    if (!regular_executable(executable)) return;
    std::wstring executable_name = executable.substr(executable.find_last_of(L"\\/") + 1);
    std::string identifier = to_utf8(application_catalog_key(executable_name));
    std::string launch = to_utf8(launch_name);
    std::string name = to_utf8(label);
    std::wstring validated;
    if (!jc_application_catalog::validIdentity(identifier)
            || !valid_application_request(launch.c_str(), &validated)) return;
    if (!valid_application_request(name.c_str(), &validated)) name = identifier;
    auto found = indices.find(identifier);
    if (found == indices.end()) {
        jc_application_catalog::Application application;
        application.name = name;
        application.displayName = name;
        application.applicationId = identifier;
        application.launchName = launch;
        applications.push_back(std::move(application));
        found = indices.emplace(identifier, applications.size() - 1).first;
    }
    auto& application = applications[found->second];
    jc_application_catalog::addAlias(application, identifier);
    jc_application_catalog::addAlias(application, launch);
    jc_application_catalog::addAlias(application, name);
    jc_application_catalog::addAlias(application, to_utf8(application_catalog_stem(executable_name)));
}

void collect_registered_applications(std::vector<std::wstring>& names, bool& truncated) {
    constexpr wchar_t app_paths[] = L"SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths";
    size_t inspected = 0;
    for (HKEY hive : {HKEY_CURRENT_USER, HKEY_LOCAL_MACHINE}) {
        for (REGSAM view : {KEY_WOW64_64KEY, KEY_WOW64_32KEY}) {
            ApplicationRegistryKey root;
            LSTATUS opened = RegOpenKeyExW(hive, app_paths, 0,
                    KEY_ENUMERATE_SUB_KEYS | view, &root.value);
            if (opened != ERROR_SUCCESS) {
                if (opened != ERROR_FILE_NOT_FOUND && opened != ERROR_PATH_NOT_FOUND)
                    truncated = true;
                continue;
            }
            for (DWORD index = 0; ; ++index) {
                wchar_t name[256]{};
                DWORD length = static_cast<DWORD>(std::size(name));
                LSTATUS result = RegEnumKeyExW(root.value, index, name, &length,
                        nullptr, nullptr, nullptr, nullptr);
                if (result == ERROR_NO_MORE_ITEMS) break;
                if (++inspected > 4096) {
                    truncated = true;
                    break;
                }
                if (result != ERROR_SUCCESS) {
                    truncated = true;
                    if (result != ERROR_MORE_DATA) break;
                    continue;
                }
                std::wstring candidate(name, length);
                std::wstring validated;
                if (valid_application_request(to_utf8(candidate).c_str(), &validated))
                    names.push_back(std::move(candidate));
            }
            if (inspected > 4096) break;
        }
        if (inspected > 4096) break;
    }
    std::sort(names.begin(), names.end(), [](const auto& first, const auto& second) {
        std::wstring first_key = application_catalog_key(first);
        std::wstring second_key = application_catalog_key(second);
        return first_key == second_key ? first < second : first_key < second_key;
    });
    names.erase(std::unique(names.begin(), names.end(), [](const auto& first, const auto& second) {
        return _wcsicmp(first.c_str(), second.c_str()) == 0;
    }), names.end());
}

struct ApplicationCatalogShortcut {
    std::wstring label;
    std::wstring path;
};

struct ApplicationCatalogSearch {
    HANDLE value;
    ~ApplicationCatalogSearch() {
        if (value != INVALID_HANDLE_VALUE) FindClose(value);
    }
};

void collect_application_shortcuts(const std::wstring& folder, int depth,
        size_t& visited, bool& truncated,
        std::unordered_map<std::wstring, size_t>& label_counts,
        std::vector<ApplicationCatalogShortcut>& shortcuts) {
    WIN32_FIND_DATAW entry{};
    ApplicationCatalogSearch search{FindFirstFileW((folder + L"\\*").c_str(), &entry)};
    if (search.value == INVALID_HANDLE_VALUE) {
        DWORD error = GetLastError();
        if (error != ERROR_FILE_NOT_FOUND && error != ERROR_PATH_NOT_FOUND) truncated = true;
        return;
    }
    do {
        if (wcscmp(entry.cFileName, L".") == 0 || wcscmp(entry.cFileName, L"..") == 0) continue;
        if (++visited > 20000) {
            truncated = true;
            break;
        }
        if ((entry.dwFileAttributes & FILE_ATTRIBUTE_REPARSE_POINT) != 0) continue;
        std::wstring path = folder + L"\\" + entry.cFileName;
        if ((entry.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) != 0) {
            if (depth >= 7) truncated = true;
            else collect_application_shortcuts(path, depth + 1, visited,
                    truncated, label_counts, shortcuts);
            if (visited > 20000) break;
            continue;
        }
        std::wstring label(entry.cFileName);
        if (label.size() < 5 || _wcsicmp(label.c_str() + label.size() - 4, L".lnk") != 0)
            continue;
        label.resize(label.size() - 4);
        ++label_counts[application_catalog_key(label)];
        std::wstring validated;
        if (!valid_application_request(to_utf8(label).c_str(), &validated)) continue;
        if (shortcuts.size() >= 4096) {
            truncated = true;
            continue;
        }
        shortcuts.push_back({std::move(label), std::move(path)});
    } while (FindNextFileW(search.value, &entry));
    DWORD error = GetLastError();
    if (visited <= 20000 && error != ERROR_NO_MORE_FILES) truncated = true;
}

std::string installed_application_catalog() {
    std::vector<jc_application_catalog::Application> applications;
    std::unordered_map<std::string, size_t> indices;
    bool truncated = false;
    std::vector<std::wstring> registrations;
    collect_registered_applications(registrations, truncated);
    for (const auto& registration : registrations) {
        std::wstring executable = registered_executable(registration);
        add_application_catalog(registration, executable,
                application_catalog_stem(registration), applications, indices);
    }

    std::unordered_map<std::wstring, size_t> label_counts;
    std::vector<ApplicationCatalogShortcut> shortcuts;
    size_t visited = 0;
    const KNOWNFOLDERID folders[] = {FOLDERID_Programs, FOLDERID_CommonPrograms};
    for (const auto& folder : folders) {
        PWSTR root = nullptr;
        HRESULT result = SHGetKnownFolderPath(folder, KF_FLAG_DEFAULT, nullptr, &root);
        std::unique_ptr<wchar_t, decltype(&CoTaskMemFree)> owned_root(root, &CoTaskMemFree);
        if (SUCCEEDED(result) && root) {
            collect_application_shortcuts(root, 0, visited, truncated, label_counts, shortcuts);
        } else {
            truncated = true;
        }
        if (visited > 20000) break;
    }
    std::sort(shortcuts.begin(), shortcuts.end(), [](const auto& first, const auto& second) {
        auto first_key = application_catalog_key(first.label);
        auto second_key = application_catalog_key(second.label);
        return first_key == second_key ? first.label < second.label : first_key < second_key;
    });
    for (const auto& shortcut : shortcuts) {
        // Keep the same exact-name, unique-shortcut admission as launch_application.
        if (label_counts[application_catalog_key(shortcut.label)] != 1) continue;
        std::wstring executable = shortcut_executable(shortcut.path);
        if (executable.empty()) continue;
        // A registry entry of this label has launch precedence over its shortcut.
        std::wstring registered = registered_executable(shortcut.label);
        if (!registered.empty()) executable = std::move(registered);
        add_application_catalog(shortcut.label, executable, shortcut.label, applications, indices);
    }
    return jc_application_catalog::boundedJson(std::move(applications), truncated);
}
