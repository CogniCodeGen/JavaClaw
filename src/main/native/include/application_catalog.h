#ifndef JAVACLAW_APPLICATION_CATALOG_H
#define JAVACLAW_APPLICATION_CATALOG_H

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <string>
#include <utility>
#include <vector>

// Internal C++ helpers for the optional, read-only installed-application ABI.
namespace jc_application_catalog {

constexpr size_t kMaxApplications = 256;
constexpr size_t kMaxCatalogBytes = 32768;
constexpr size_t kMaxIdentityBytes = 256;
constexpr size_t kMaxAliases = 8;

struct Application {
    std::string name;
    std::string displayName;
    std::string applicationId;
    std::string launchName;
    std::vector<std::string> aliases;
};

inline bool validIdentity(const std::string& value) {
    if (value.empty() || value.size() > kMaxIdentityBytes
            || value.find("..") != std::string::npos
            || value.front() == ' ' || value.back() == ' ') return false;
    for (unsigned char byte : value) {
        if (byte < 0x20 || byte == 0x7f || byte == '/' || byte == '\\' || byte == ':')
            return false;
    }
    return true;
}

inline void addAlias(Application& application, const std::string& alias) {
    if (!validIdentity(alias) || application.aliases.size() >= kMaxAliases
            || std::find(application.aliases.begin(), application.aliases.end(), alias)
                != application.aliases.end()) return;
    application.aliases.push_back(alias);
}

inline void appendJsonString(std::string& output, const std::string& value) {
    static const char hex[] = "0123456789abcdef";
    output.push_back('"');
    for (unsigned char byte : value) {
        if (byte == '"' || byte == '\\') {
            output.push_back('\\');
            output.push_back(static_cast<char>(byte));
        } else if (byte < 0x20) {
            output += "\\u00";
            output.push_back(hex[byte >> 4]);
            output.push_back(hex[byte & 0x0f]);
        } else {
            output.push_back(static_cast<char>(byte));
        }
    }
    output.push_back('"');
}

inline std::string serialize(const std::vector<Application>& applications,
                             size_t count, bool truncated) {
    std::string result = "{\"schemaVersion\":1,\"applications\":[";
    for (size_t index = 0; index < count; ++index) {
        const auto& application = applications[index];
        if (index) result.push_back(',');
        result += "{\"name\":";
        appendJsonString(result, application.name);
        result += ",\"displayName\":";
        appendJsonString(result, application.displayName);
        result += ",\"applicationId\":";
        appendJsonString(result, application.applicationId);
        result += ",\"launchName\":";
        appendJsonString(result, application.launchName);
        result += ",\"aliases\":[";
        for (size_t aliasIndex = 0; aliasIndex < application.aliases.size(); ++aliasIndex) {
            if (aliasIndex) result.push_back(',');
            appendJsonString(result, application.aliases[aliasIndex]);
        }
        result += "]}";
    }
    result += "],\"count\":" + std::to_string(count)
        + ",\"truncated\":" + (truncated ? "true}" : "false}");
    return result;
}

inline std::string boundedJson(std::vector<Application> applications, bool truncated) {
    std::sort(applications.begin(), applications.end(), [](const auto& first, const auto& second) {
        if (first.applicationId != second.applicationId)
            return first.applicationId < second.applicationId;
        return first.name < second.name;
    });
    for (auto& application : applications)
        std::sort(application.aliases.begin(), application.aliases.end());
    size_t count = std::min(applications.size(), kMaxApplications);
    truncated = truncated || count < applications.size();
    std::string result = serialize(applications, count, truncated);
    while (result.size() + 1 > kMaxCatalogBytes && count > 0) {
        result = serialize(applications, --count, true);
    }
    return result;
}

inline int32_t copyJson(const std::string& json, char* destination,
                       uint32_t capacity, uint32_t* requiredBytes) {
    if (!requiredBytes || (!destination && capacity != 0)) return -1;
    *requiredBytes = static_cast<uint32_t>(json.size() + 1);
    if (!destination && capacity == 0) return 0;
    if (capacity < *requiredBytes) {
        if (capacity) destination[0] = '\0';
        return -1;
    }
    std::memcpy(destination, json.data(), json.size());
    destination[json.size()] = '\0';
    return 0;
}

} // namespace jc_application_catalog

#endif
