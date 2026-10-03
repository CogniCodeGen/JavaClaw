#ifndef JAVACLAW_MACOS_AX_SNAPSHOT_POLICY_H
#define JAVACLAW_MACOS_AX_SNAPSHOT_POLICY_H

#include <cstddef>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <unordered_map>
#include <utility>

namespace jc_ax_snapshot {

struct Identity {
    uint32_t token;
    uint64_t windowId;
    uint64_t generation;
    uint64_t contentRevision;
};

inline bool actionReady(const Identity &snapshot, uint32_t requestedToken,
                        uint64_t currentWindowId, uint64_t currentGeneration,
                        uint64_t currentRevision, uint64_t actionGeneration,
                        uint64_t actionRevision, bool regionUnchanged) {
    return requestedToken != 0 && requestedToken == snapshot.token
        && currentWindowId == snapshot.windowId
        && currentGeneration == snapshot.generation
        && actionGeneration == currentGeneration
        && currentRevision >= snapshot.contentRevision
        && actionRevision != 0 && actionRevision == currentRevision
        && (currentRevision == snapshot.contentRevision || regionUnchanged);
}

struct Region {
    int32_t x;
    int32_t y;
    int32_t width;
    int32_t height;
};

inline bool frameUsable(bool captureFailed, size_t pixelBytes,
                        int32_t frameWidth, int32_t frameHeight, int32_t stride) {
    return !captureFailed && pixelBytes != 0 && frameWidth > 0 && frameHeight > 0
        && stride >= static_cast<int64_t>(frameWidth) * 4
        && pixelBytes >= static_cast<uint64_t>(stride) * static_cast<uint64_t>(frameHeight);
}

inline bool regionEquals(const uint8_t *observed, size_t observedBytes,
                         const uint8_t *current, size_t currentBytes,
                         int32_t frameWidth, int32_t frameHeight, int32_t stride,
                         Region region) {
    if (!observed || !current || frameWidth < 1 || frameHeight < 1
            || stride < static_cast<int64_t>(frameWidth) * 4
            || region.x < 0 || region.y < 0 || region.width < 1 || region.height < 1
            || static_cast<int64_t>(region.x) + region.width > frameWidth
            || static_cast<int64_t>(region.y) + region.height > frameHeight)
        return false;
    size_t rowBytes = static_cast<size_t>(region.width) * 4;
    if (observedBytes != rowBytes * static_cast<size_t>(region.height)
            || currentBytes < static_cast<size_t>(stride) * static_cast<size_t>(frameHeight))
        return false;
    for (int32_t row = 0; row < region.height; ++row) {
        const uint8_t *source = current
            + static_cast<size_t>(region.y + row) * static_cast<size_t>(stride)
            + static_cast<size_t>(region.x) * 4;
        const uint8_t *expected = observed + static_cast<size_t>(row) * rowBytes;
        if (std::memcmp(source, expected, rowBytes) != 0) return false;
    }
    return true;
}

template <typename Value>
class OncePerProcess {
public:
    template <typename Create>
    std::pair<Value, bool> getOrCreate(int64_t pid, uint64_t processInstance,
                                       Create create) {
        return getOrCreate(pid, processInstance, 0, create,
            [](const Value &) { return true; }, 1, 0);
    }

    template <typename Create, typename Terminal>
    std::pair<Value, bool> getOrCreate(int64_t pid, uint64_t processInstance,
                                       uint64_t nowMillis, Create create,
                                       Terminal terminal, int maxAttempts,
                                       uint64_t retryAfterMillis) {
        std::lock_guard<std::mutex> guard(mutex_);
        Key key{pid, processInstance};
        auto found = values_.find(key);
        if (found != values_.end()) {
            Entry &entry = found->second;
            if (entry.terminal || entry.attempts >= maxAttempts
                    || nowMillis < entry.nextRetryMillis)
                return {entry.value, false};
            Value retryValue = create();
            entry.value = retryValue;
            ++entry.attempts;
            entry.terminal = terminal(retryValue);
            entry.nextRetryMillis = nowMillis + retryAfterMillis;
            return {retryValue, true};
        }
        Value value = create();
        values_.emplace(key, Entry{value, 1, terminal(value),
                                   nowMillis + retryAfterMillis});
        return {value, true};
    }

private:
    struct Entry {
        Value value;
        int attempts;
        bool terminal;
        uint64_t nextRetryMillis;
    };
    struct Key {
        int64_t pid;
        uint64_t processInstance;
        bool operator==(const Key &other) const {
            return pid == other.pid && processInstance == other.processInstance;
        }
    };
    struct KeyHash {
        size_t operator()(const Key &key) const {
            size_t first = std::hash<int64_t>{}(key.pid);
            size_t second = std::hash<uint64_t>{}(key.processInstance);
            return first ^ (second + static_cast<size_t>(0x9e3779b9) + (first << 6)
                + (first >> 2));
        }
    };
    std::mutex mutex_;
    std::unordered_map<Key, Entry, KeyHash> values_;
};

} // namespace jc_ax_snapshot

#endif
