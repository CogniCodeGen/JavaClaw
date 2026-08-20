package com.javaclaw.ui.javafx.settings;

import com.javaclaw.application.inference.InferenceCatalogPort;
import com.javaclaw.inference.api.InferenceModelAsset;
import com.javaclaw.inference.api.InferenceModelProfile;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Derives user-facing model purposes without duplicating content-addressed model files. */
final class InferenceModelPurposeClassifier {
    private InferenceModelPurposeClassifier() { }

    static Set<InferenceModelProfile.Kind> purposes(
            InferenceModelAsset asset,
            List<InferenceCatalogPort.RuntimeInstallation> runtimes,
            List<InferenceModelProfile> profiles) {
        EnumSet<InferenceModelProfile.Kind> result = EnumSet.noneOf(InferenceModelProfile.Kind.class);
        if (asset == null) return Set.of();
        if (profiles != null) profiles.stream()
                .filter(profile -> asset.id().equals(profile.assetId()))
                .map(InferenceModelProfile::kind)
                .forEach(result::add);
        result.addAll(purposes(asset.modelType(), runtimes));
        return Set.copyOf(result);
    }

    static Set<InferenceModelProfile.Kind> purposes(
            String modelType, List<InferenceCatalogPort.RuntimeInstallation> runtimes) {
        EnumSet<InferenceModelProfile.Kind> result = EnumSet.noneOf(InferenceModelProfile.Kind.class);
        if (modelType == null || modelType.isBlank() || runtimes == null) return Set.of();
        List<InferenceCatalogPort.RuntimeInstallation> active = runtimes.stream()
                .filter(InferenceCatalogPort.RuntimeInstallation::active).toList();
        List<InferenceCatalogPort.RuntimeInstallation> candidates = active.isEmpty() ? runtimes : active;
        for (InferenceCatalogPort.RuntimeInstallation runtime : candidates) {
            for (InferenceModelProfile.Kind kind : InferenceModelProfile.Kind.values()) {
                if (runtime.manifest().supportedModelTypes(kind).contains(modelType)) result.add(kind);
            }
        }
        return Set.copyOf(result);
    }

    static String purposeLabel(Set<InferenceModelProfile.Kind> purposes) {
        boolean generation = purposes != null
                && purposes.contains(InferenceModelProfile.Kind.GENERATION);
        boolean embedding = purposes != null
                && purposes.contains(InferenceModelProfile.Kind.EMBEDDING);
        if (generation && embedding) return "推理模型 / 向量化模型";
        if (generation) return "推理模型";
        if (embedding) return "向量化模型";
        return "用途待识别";
    }

    static String kindLabel(InferenceModelProfile.Kind kind) {
        return kind == InferenceModelProfile.Kind.EMBEDDING ? "向量化模型" : "推理模型";
    }

    static String stateLabel(InferenceModelProfile.State state) {
        return switch (state) {
            case DRAFT -> "待测试";
            case VERIFYING -> "测试中";
            case READY -> "可用";
            case FAILED -> "失败";
            case DISABLED -> "已停用";
        };
    }
}
