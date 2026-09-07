#include "guard.hpp"
#include <algorithm>
#include <mutex>

namespace javaclaw {
const GUID providerKey = {0x94757553, 0xc544, 0x4f57, {0xab, 0x30, 0xe3, 0xee, 0xac, 0xce, 0x19, 0x21}};
const GUID sublayerKey = {0xb29da24e, 0xabfb, 0x44d9, {0xa5, 0x58, 0xa1, 0x01, 0xce, 0x5f, 0x01, 0x56}};
static std::mutex loopbackMutex;

static HANDLE openEngine(bool dynamic) {
    FWPM_SESSION0 session{};
    session.flags = dynamic ? FWPM_SESSION_FLAG_DYNAMIC : 0;
    session.displayData.name = const_cast<wchar_t*>(L"JavaClaw bounded network lease");
    HANDLE engine = nullptr;
    status(FwpmEngineOpen0(nullptr, RPC_C_AUTHN_WINNT, nullptr, &session, &engine), "FwpmEngineOpen0");
    return engine;
}

static void ensureProvider(HANDLE engine) {
    FWPM_PROVIDER0 provider{};
    provider.providerKey = providerKey;
    provider.displayData.name = const_cast<wchar_t*>(serviceName);
    provider.flags = FWPM_PROVIDER_FLAG_PERSISTENT;
    // BFE 重启后持久策略仍有效；服务必须由 SCM 配置为自动启动。
    provider.serviceName = const_cast<wchar_t*>(serviceName);
    DWORD result = FwpmProviderAdd0(engine, &provider, nullptr);
    if (result != static_cast<DWORD>(FWP_E_ALREADY_EXISTS)) {
        status(result, "FwpmProviderAdd0");
    }
    FWPM_SUBLAYER0 layer{};
    layer.subLayerKey = sublayerKey;
    layer.displayData.name = const_cast<wchar_t*>(L"JavaClaw AppContainer boundary");
    layer.flags = FWPM_SUBLAYER_FLAG_PERSISTENT;
    layer.providerKey = const_cast<GUID*>(&providerKey);
    layer.weight = 0x7000;
    result = FwpmSubLayerAdd0(engine, &layer, nullptr);
    if (result != static_cast<DWORD>(FWP_E_ALREADY_EXISTS)) {
        status(result, "FwpmSubLayerAdd0");
    }
}

static UINT64 addFilter(HANDLE engine, PSID sid, const GUID& layer, UINT16 port) {
    FWPM_FILTER_CONDITION0 conditions[4]{};
    conditions[0].fieldKey = FWPM_CONDITION_ALE_PACKAGE_ID;
    conditions[0].matchType = FWP_MATCH_EQUAL;
    conditions[0].conditionValue.type = FWP_SID;
    conditions[0].conditionValue.sid = static_cast<SID*>(sid);
    UINT64 weight = port ? 20 : 10;
    FWPM_FILTER0 filter{};
    filter.displayData.name =
        const_cast<wchar_t*>(port ? L"JavaClaw exact proxy lease" : L"JavaClaw persistent SID deny");
    filter.flags = port ? 0 : FWPM_FILTER_FLAG_PERSISTENT;
    filter.providerKey = const_cast<GUID*>(&providerKey);
    filter.providerData.size = GetLengthSid(sid);
    filter.providerData.data = static_cast<UINT8*>(sid);
    filter.layerKey = layer;
    filter.subLayerKey = sublayerKey;
    filter.weight.type = FWP_UINT64;
    filter.weight.uint64 = &weight;
    filter.numFilterConditions = 1;
    filter.filterCondition = conditions;
    filter.action.type = port ? FWP_ACTION_PERMIT : FWP_ACTION_BLOCK;
    if (port) {
        conditions[1].fieldKey = FWPM_CONDITION_IP_REMOTE_ADDRESS;
        conditions[1].matchType = FWP_MATCH_EQUAL;
        conditions[1].conditionValue.type = FWP_UINT32;
        conditions[1].conditionValue.uint32 = 0x7f000001;
        conditions[2].fieldKey = FWPM_CONDITION_IP_REMOTE_PORT;
        conditions[2].matchType = FWP_MATCH_EQUAL;
        conditions[2].conditionValue.type = FWP_UINT16;
        conditions[2].conditionValue.uint16 = port;
        conditions[3].fieldKey = FWPM_CONDITION_IP_PROTOCOL;
        conditions[3].matchType = FWP_MATCH_EQUAL;
        conditions[3].conditionValue.type = FWP_UINT8;
        conditions[3].conditionValue.uint8 = IPPROTO_TCP;
        filter.numFilterConditions = 4;
    }
    UINT64 identifier = 0;
    status(FwpmFilterAdd0(engine, &filter, nullptr, &identifier), "FwpmFilterAdd0");
    return identifier;
}

void modifyLoopback(PSID sid, bool add) {
    std::lock_guard<std::mutex> lock(loopbackMutex);
    DWORD count = 0;
    PSID_AND_ATTRIBUTES current = nullptr;
    status(NetworkIsolationGetAppContainerConfig(&count, &current), "NetworkIsolationGetAppContainerConfig");
    std::vector<SID_AND_ATTRIBUTES> next;
    bool present = false;
    for (DWORD index = 0; index < count; ++index) {
        if (EqualSid(current[index].Sid, sid)) {
            present = true;
        }
        if (add || !EqualSid(current[index].Sid, sid)) {
            next.push_back(current[index]);
        }
    }
    if (add && !present) {
        next.push_back({sid, SE_GROUP_ENABLED});
    }
    // 每次读取最新列表，仅增删本租约 SID；不会还原旧列表覆盖其他应用新配置。
    DWORD result = NetworkIsolationSetAppContainerConfig(static_cast<DWORD>(next.size()), next.data());
    if (current) {
        for (DWORD index = 0; index < count; ++index) {
            HeapFree(GetProcessHeap(), 0, current[index].Sid);
        }
        HeapFree(GetProcessHeap(), 0, current);
    }
    status(result, "NetworkIsolationSetAppContainerConfig");
}

static Handle createRootJob(UINT64 memoryBytes, DWORD processes, DWORD timeoutMillis) {
    Handle job(CreateJobObjectW(nullptr, nullptr));
    require(job.get() != nullptr, "CreateJobObjectW");
    JOBOBJECT_EXTENDED_LIMIT_INFORMATION limits{};
    limits.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_ACTIVE_PROCESS | JOB_OBJECT_LIMIT_JOB_MEMORY |
                                              JOB_OBJECT_LIMIT_DIE_ON_UNHANDLED_EXCEPTION |
                                              JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
    limits.BasicLimitInformation.ActiveProcessLimit = processes;
    limits.JobMemoryLimit = static_cast<SIZE_T>(memoryBytes);
    require(SetInformationJobObject(job.get(), JobObjectExtendedLimitInformation, &limits, sizeof(limits)),
            "Job limits");
    JOBOBJECT_BASIC_UI_RESTRICTIONS ui{};
    ui.UIRestrictionsClass = JOB_OBJECT_UILIMIT_ALL;
    require(SetInformationJobObject(job.get(), JobObjectBasicUIRestrictions, &ui, sizeof(ui)), "Job UI limits");
    // 墙钟由连接处理器的硬截止时间监控；Job 的所有者只有服务，不向 helper 返回任何 Job handle。
    (void)timeoutMillis;
    return job;
}

NetworkLease::NetworkLease(HANDLE process, PSID sid, UINT16 port, UINT64 memoryBytes, DWORD processes,
                           DWORD timeoutMillis) {
    sid_.resize(GetLengthSid(sid));
    require(CopySid(static_cast<DWORD>(sid_.size()), sid_.data(), sid), "CopySid");
    HANDLE persistent = openEngine(false);
    try {
        ensureProvider(persistent);
        status(FwpmTransactionBegin0(persistent, 0), "FwpmTransactionBegin0");
        denies_.push_back(addFilter(persistent, sid_.data(), FWPM_LAYER_ALE_AUTH_CONNECT_V4, 0));
        denies_.push_back(addFilter(persistent, sid_.data(), FWPM_LAYER_ALE_AUTH_CONNECT_V6, 0));
        denies_.push_back(addFilter(persistent, sid_.data(), FWPM_LAYER_ALE_AUTH_RECV_ACCEPT_V4, 0));
        denies_.push_back(addFilter(persistent, sid_.data(), FWPM_LAYER_ALE_AUTH_RECV_ACCEPT_V6, 0));
        status(FwpmTransactionCommit0(persistent), "FwpmTransactionCommit0");
        FwpmEngineClose0(persistent);
        persistent = nullptr;
        job_ = createRootJob(memoryBytes, processes, timeoutMillis);
        require(AssignProcessToJobObject(job_.get(), process), "AssignProcessToJobObject");
        engine_ = openEngine(true);
        permits_.push_back(addFilter(engine_, sid_.data(), FWPM_LAYER_ALE_AUTH_CONNECT_V4, port));
        modifyLoopback(sid_.data(), true);
        loopback_ = true;
    } catch (...) {
        if (persistent) {
            FwpmTransactionAbort0(persistent);
            FwpmEngineClose0(persistent);
        }
        try {
            close();
        } catch (...) {
            if (engine_) {
                FwpmEngineClose0(engine_);
                engine_ = nullptr;
            }
            job_.reset();
        }
        throw;
    }
}

void NetworkLease::revoke() {
    if (revoked_) {
        return;
    }
    if (engine_) {
        for (UINT64 filter : permits_) {
            DWORD result = FwpmFilterDeleteById0(engine_, filter);
            if (result != static_cast<DWORD>(FWP_E_FILTER_NOT_FOUND)) {
                status(result, "FwpmFilterDeleteById0");
            }
        }
        permits_.clear();
        status(FwpmEngineClose0(engine_), "FwpmEngineClose0");
        engine_ = nullptr;
    }
    revoked_ = true;
}

void NetworkLease::close() {
    // 正常路径先撤允许，客户端收到确认后关闭 Broker 隧道，再发送 CLOSE 终止 Job。
    // 断连/服务崩溃路径仍由动态会话清理、永久拒绝及唯一 Job handle 保证失败关闭。
    revoke();
    if (job_.get()) {
        require(TerminateJobObject(job_.get(), 137), "TerminateJobObject");
        ULONGLONG deadline = GetTickCount64() + 3000;
        JOBOBJECT_BASIC_ACCOUNTING_INFORMATION accounting{};
        while (true) {
            require(QueryInformationJobObject(job_.get(), JobObjectBasicAccountingInformation, &accounting,
                                              sizeof(accounting), nullptr),
                    "Job process cleanup");
            if (!accounting.ActiveProcesses) {
                break;
            }
            if (GetTickCount64() >= deadline) {
                throw std::runtime_error("NETWORK_GUARD_JOB_CLEANUP_TIMEOUT");
            }
            Sleep(10);
        }
        job_.reset();
    }
    if (loopback_) {
        modifyLoopback(sid_.data(), false);
        loopback_ = false;
    }
    // 只有独占 Job 已确认清空且 loopback 已撤销，才删除本次持久拒绝；崩溃残留由启动恢复处理。
    if (!denies_.empty()) {
        HANDLE persistent = openEngine(false);
        try {
            for (UINT64 filter : denies_) {
                DWORD result = FwpmFilterDeleteById0(persistent, filter);
                if (result != static_cast<DWORD>(FWP_E_FILTER_NOT_FOUND)) {
                    status(result, "Delete owned SID deny");
                }
            }
            denies_.clear();
        } catch (...) {
            FwpmEngineClose0(persistent);
            throw;
        }
        FwpmEngineClose0(persistent);
    }
}

NetworkLease::~NetworkLease() {
    try {
        close();
    } catch (...) {
        if (engine_) {
            FwpmEngineClose0(engine_);
        }
        job_.reset();
    }
}
} // namespace javaclaw

namespace javaclaw {
extern bool hasLiveContainer(PSID sid);

void recoverOwnedPolicy(bool uninstall) {
    HANDLE engine = openEngine(false);
    HANDLE enumeration = nullptr;
    try {
        FWPM_FILTER_ENUM_TEMPLATE0 criteria{};
        criteria.providerKey = const_cast<GUID*>(&providerKey);
        criteria.enumType = FWP_FILTER_ENUM_FULLY_CONTAINED;
        criteria.actionMask = 0xffffffff;
        status(FwpmFilterCreateEnumHandle0(engine, &criteria, &enumeration), "FwpmFilterCreateEnumHandle0");
        bool retained = false;
        while (true) {
            FWPM_FILTER0** filters = nullptr;
            UINT32 count = 0;
            status(FwpmFilterEnum0(engine, enumeration, 128, &filters, &count), "FwpmFilterEnum0");
            try {
                for (UINT32 index = 0; index < count; ++index) {
                    auto filter = filters[index];
                    if (filter->providerData.size < 8 || !IsValidSid(filter->providerData.data) ||
                        GetLengthSid(filter->providerData.data) != filter->providerData.size) {
                        retained = true;
                        continue;
                    }
                    // 清理范围严格限定本 Provider；先撤自己的 loopback，再删除已无存活进程的 SID 拒绝。
                    modifyLoopback(filter->providerData.data, false);
                    if (hasLiveContainer(filter->providerData.data)) {
                        retained = true;
                        continue;
                    }
                    DWORD result = FwpmFilterDeleteById0(engine, filter->filterId);
                    if (result != static_cast<DWORD>(FWP_E_FILTER_NOT_FOUND)) {
                        status(result, "FwpmFilterDeleteById0(recovery)");
                    }
                }
            } catch (...) {
                FwpmFreeMemory0(reinterpret_cast<void**>(&filters));
                throw;
            }
            FwpmFreeMemory0(reinterpret_cast<void**>(&filters));
            if (count < 128) {
                break;
            }
        }
        FwpmFilterDestroyEnumHandle0(engine, enumeration);
        enumeration = nullptr;
        if (uninstall) {
            if (retained) {
                throw std::runtime_error("NETWORK_GUARD_LIVE_CONTAINER_REQUIRES_RETRY");
            }
            DWORD result = FwpmSubLayerDeleteByKey0(engine, &sublayerKey);
            if (result != static_cast<DWORD>(FWP_E_SUBLAYER_NOT_FOUND)) {
                status(result, "FwpmSubLayerDeleteByKey0");
            }
            result = FwpmProviderDeleteByKey0(engine, &providerKey);
            if (result != static_cast<DWORD>(FWP_E_PROVIDER_NOT_FOUND)) {
                status(result, "FwpmProviderDeleteByKey0");
            }
        }
        FwpmEngineClose0(engine);
    } catch (...) {
        if (enumeration) {
            FwpmFilterDestroyEnumHandle0(engine, enumeration);
        }
        FwpmEngineClose0(engine);
        throw;
    }
}
} // namespace javaclaw
