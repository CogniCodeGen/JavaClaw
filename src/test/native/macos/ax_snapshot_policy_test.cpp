#include "ax_snapshot_policy.h"

#include <algorithm>
#include <cassert>
#include <cstdint>
#include <vector>

int main() {
    using namespace jc_ax_snapshot;

    Identity token{7, 91, 4, 12};
    assert(actionReady(token, 7, 91, 4, 12, 4, 12, true));
    assert(!actionReady(token, 6, 91, 4, 12, 4, 12, true));
    assert(!actionReady(token, 7, 91, 5, 12, 5, 12, true));
    assert(!actionReady(token, 7, 92, 4, 12, 4, 12, true));
    assert(actionReady(token, 7, 91, 4, 13, 4, 13, true));
    assert(!actionReady(token, 7, 91, 4, 13, 4, 13, false));
    assert(!actionReady(token, 7, 91, 4, 13, 4, 12, true));

    constexpr int32_t width = 4;
    constexpr int32_t height = 4;
    constexpr int32_t stride = width * 4;
    assert(frameUsable(false, static_cast<size_t>(stride) * height,
                       width, height, stride));
    assert(!frameUsable(true, static_cast<size_t>(stride) * height,
                        width, height, stride));
    assert(!frameUsable(false, 0, width, height, stride));
    assert(!frameUsable(false, static_cast<size_t>(stride) * height - 1,
                        width, height, stride));
    assert(!frameUsable(false, static_cast<size_t>(stride) * height,
                        width, height, stride - 1));
    Region region{1, 1, 2, 2};
    std::vector<uint8_t> frame(static_cast<size_t>(stride) * height, 0x33);
    std::vector<uint8_t> observed(static_cast<size_t>(region.width) * region.height * 4);
    for (int32_t row = 0; row < region.height; ++row) {
        const uint8_t *source = frame.data()
            + static_cast<size_t>(region.y + row) * stride
            + static_cast<size_t>(region.x) * 4;
        std::copy(source, source + region.width * 4,
                  observed.begin() + static_cast<size_t>(row) * region.width * 4);
    }
    assert(regionEquals(observed.data(), observed.size(), frame.data(), frame.size(),
                        width, height, stride, region));
    frame[0] = 0x44; // Animation outside the selected element is allowed.
    assert(regionEquals(observed.data(), observed.size(), frame.data(), frame.size(),
                        width, height, stride, region));
    frame[static_cast<size_t>(region.y) * stride + region.x * 4] = 0x44;
    assert(!regionEquals(observed.data(), observed.size(), frame.data(), frame.size(),
                         width, height, stride, region));
    assert(!regionEquals(nullptr, 0, frame.data(), frame.size(),
                         width, height, stride, region));

    OncePerProcess<int> priming;
    int attempts = 0;
    auto first = priming.getOrCreate(51, 1001, [&] { return ++attempts; });
    auto repeated = priming.getOrCreate(51, 1001, [&] { return ++attempts; });
    auto restarted = priming.getOrCreate(51, 1002, [&] { return ++attempts; });
    auto anotherProcess = priming.getOrCreate(52, 1001, [&] { return ++attempts; });
    assert(first.first == 1 && first.second);
    assert(repeated.first == 1 && !repeated.second);
    assert(restarted.first == 2 && restarted.second);
    assert(anotherProcess.first == 3 && anotherProcess.second);
    assert(attempts == 3);

    OncePerProcess<int> recoverableProbe;
    int probes = 0;
    auto runProbe = [&](uint64_t at, uint64_t processInstance) {
        return recoverableProbe.getOrCreate(61, processInstance, at,
            [&] { return ++probes < 3 ? -1 : 1; },
            [](int outcome) { return outcome >= 0; }, 3, 1000);
    };
    assert(runProbe(100, 400).second && probes == 1);
    assert(!runProbe(1099, 400).second && probes == 1);
    assert(runProbe(1100, 400).second && probes == 2);
    assert(!runProbe(2099, 400).second && probes == 2);
    assert(runProbe(2100, 400).second && probes == 3);
    assert(!runProbe(3100, 400).second && probes == 3); // Successful setter is once.
    assert(runProbe(3100, 401).second && probes == 4); // New process instance.

    OncePerProcess<int> exhaustedProbe;
    int exhaustedAttempts = 0;
    for (uint64_t at : {uint64_t{0}, uint64_t{1000}, uint64_t{2000}, uint64_t{3000}})
        exhaustedProbe.getOrCreate(62, 500, at,
            [&] { ++exhaustedAttempts; return -1; },
            [](int) { return false; }, 3, 1000);
    assert(exhaustedAttempts == 3);
    return 0;
}
