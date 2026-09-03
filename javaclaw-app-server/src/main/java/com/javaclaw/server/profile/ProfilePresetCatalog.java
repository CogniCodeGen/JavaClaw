package com.javaclaw.server.profile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

import com.javaclaw.api.AgentProfilePreset;
import com.javaclaw.api.TurnBudget;

/** 读取随发行版审阅和版本化的内置 Agent Profile 预设。 */
public final class ProfilePresetCatalog {
    private static final List<PresetResource> RESOURCES = List.of(
            new PresetResource(
                    "default",
                    1,
                    "默认软件工程师",
                    "端到端推进任务，并在当前授权范围内协调可用子任务。",
                    "/prompts/profile-default-v1.txt",
                    "98392b5123c7f4c994cb638e3682c0d5fce595b231e5e592b9328bfa67555f2f",
                    new TurnBudget(32_000, 4_000, 32, 4, Duration.ofMinutes(15))),
            new PresetResource(
                    "worker",
                    1,
                    "专注执行",
                    "围绕交办目标完成最小且完整的实现、修复或分析。",
                    "/prompts/profile-worker-v1.txt",
                    "6dde8ad059b7f467d8683c2e9ef28a025cb57d45dab700a1db4e9d89ee64d5e6",
                    new TurnBudget(32_000, 4_000, 24, 0, Duration.ofMinutes(10))),
            new PresetResource(
                    "explorer",
                    1,
                    "只读探索",
                    "检索并阅读代码和运行证据，建立可追溯的事实、推断与未知。",
                    "/prompts/profile-explorer-v1.txt",
                    "147ec3b0c07d7ce1df89d9013a20c33f18e3bd12ef863f3a779712f7c776113f",
                    new TurnBudget(32_000, 4_000, 24, 0, Duration.ofMinutes(5))));

    private final List<AgentProfilePreset> presets;

    /** 读取并校验所有内置 Prompt 资源。 */
    public ProfilePresetCatalog() {
        presets = RESOURCES.stream().map(ProfilePresetCatalog::load).toList();
    }

    /**
     * 返回当前发行版支持的预设。
     *
     * @return 按稳定标识排列的不可变预设
     */
    public List<AgentProfilePreset> list() {
        return presets;
    }

    /**
     * 读取精确预设版本。
     *
     * @param id 预设稳定标识
     * @param revision 预设版本
     * @return 精确预设
     * @throws IllegalArgumentException 预设或版本不存在
     */
    public AgentProfilePreset require(String id, long revision) {
        String checkedId = Objects.requireNonNull(id, "id").strip();
        return presets.stream()
                .filter(preset -> preset.id().equals(checkedId) && preset.revision() == revision)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Agent Profile 预设不存在"));
    }

    private static AgentProfilePreset load(PresetResource resource) {
        String instruction = read(resource.path());
        String digest = digest(instruction);
        if (!resource.digest().equals(digest)) {
            throw new IllegalStateException("Agent Profile 预设摘要不匹配: " + resource.id());
        }
        return new AgentProfilePreset(
                resource.id(),
                resource.revision(),
                resource.displayName(),
                resource.description(),
                instruction,
                digest,
                resource.defaultBudget());
    }

    private static String read(String path) {
        try (InputStream input = ProfilePresetCatalog.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException("缺少 Agent Profile 预设资源: " + path);
            }
            String value = new String(input.readAllBytes(), StandardCharsets.UTF_8).strip();
            if (value.isEmpty()) {
                throw new IllegalStateException("Agent Profile 预设不能为空: " + path);
            }
            return value;
        } catch (IOException failure) {
            throw new IllegalStateException("无法读取 Agent Profile 预设: " + path, failure);
        }
    }

    private static String digest(String value) {
        try {
            byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 不可用", failure);
        }
    }

    private record PresetResource(
            String id,
            long revision,
            String displayName,
            String description,
            String path,
            String digest,
            TurnBudget defaultBudget) {}
}
