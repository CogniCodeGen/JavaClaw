package com.javaclaw.desktop.settings;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;

import com.javaclaw.api.ProviderImageSupport;
import com.javaclaw.api.ProviderModelDiscoveryCandidate;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderModelSpec;

/** 模型选择草稿；未知用途保留为空，只有用户明确赋值后才能转为可保存配置。 */
final class ProviderSetupModelSelection {
    private final Map<String, Model> models = new LinkedHashMap<>();
    private final Set<String> selected = new LinkedHashSet<>();

    void seed(List<ProviderModelSpec> saved) {
        saved.forEach(model -> {
            models.put(
                    model.modelId(),
                    new Model(
                            model.modelId(),
                            model.displayName(),
                            model.purposes(),
                            dimensions(model.embeddingDimensions()),
                            model.imageSupport()));
            selected.add(model.modelId());
        });
    }

    void candidates(List<ProviderModelDiscoveryCandidate> candidates) {
        candidates.forEach(candidate -> models.putIfAbsent(
                candidate.modelId(),
                new Model(
                        candidate.modelId(),
                        candidate.displayName(),
                        candidate.suggestedPurposes(),
                        dimensions(candidate.embeddingDimensions()),
                        ProviderImageSupport.UNKNOWN)));
    }

    /** 连接目标变化后舍弃未选择的旧目录；已勾选模型继续属于用户草稿。 */
    void clearCandidates() {
        models.keySet().removeIf(id -> !selected.contains(id));
    }

    void addManual(String id, ProviderModelPurpose purpose) {
        var checked = new ProviderModelSpec(id, id, Set.of(purpose), OptionalInt.empty());
        models.putIfAbsent(
                checked.modelId(),
                new Model(checked.modelId(), checked.displayName(), checked.purposes(), "", checked.imageSupport()));
        if (models.get(checked.modelId()).purposes().isEmpty()) {
            purposes(checked.modelId(), checked.purposes());
        }
        selected.add(checked.modelId());
    }

    List<Model> models() {
        return List.copyOf(models.values());
    }

    List<Model> matching(String query) {
        String normalized = query.strip().toLowerCase(Locale.ROOT);
        return models.values().stream()
                .filter(model -> model.id().toLowerCase(Locale.ROOT).contains(normalized)
                        || model.name().toLowerCase(Locale.ROOT).contains(normalized))
                .toList();
    }

    Model model(String id) {
        return Objects.requireNonNull(models.get(id), "model");
    }

    List<Model> selectedDrafts() {
        return selected.stream().map(models::get).toList();
    }

    int selectedCount() {
        return selected.size();
    }

    boolean selected(String id) {
        return selected.contains(id);
    }

    void select(String id, boolean include) {
        if (include) {
            selected.add(id);
        } else {
            selected.remove(id);
        }
    }

    void purposes(String id, Set<ProviderModelPurpose> purposes) {
        Model before = models.get(id);
        models.put(
                id,
                new Model(
                        id,
                        before.name(),
                        purposes,
                        purposes.contains(ProviderModelPurpose.EMBEDDING) ? before.dimensions() : "",
                        purposes.contains(ProviderModelPurpose.CHAT) ? before.images() : ProviderImageSupport.UNKNOWN));
    }

    void selectedPurposes(Set<ProviderModelPurpose> purposes) {
        List.copyOf(selected).forEach(id -> purposes(id, purposes));
    }

    void dimensions(String id, String value) {
        Model before = models.get(id);
        models.put(id, new Model(id, before.name(), before.purposes(), value, before.images()));
    }

    void images(String id, ProviderImageSupport images) {
        Model before = models.get(id);
        models.put(id, new Model(id, before.name(), before.purposes(), before.dimensions(), images));
    }

    List<ProviderModelSpec> selectedModels() {
        return selected.stream().map(models::get).map(Model::toSpec).toList();
    }

    private static String dimensions(OptionalInt value) {
        return value.isPresent() ? Integer.toString(value.getAsInt()) : "";
    }

    record Model(
            String id,
            String name,
            Set<ProviderModelPurpose> purposes,
            String dimensions,
            ProviderImageSupport images) {
        Model {
            purposes = Set.copyOf(purposes);
        }

        ProviderModelSpec toSpec() {
            if (purposes.isEmpty()) {
                throw new IllegalArgumentException("请为模型“" + name + "”明确选择用途");
            }
            OptionalInt size =
                    dimensions.isBlank() ? OptionalInt.empty() : OptionalInt.of(Integer.parseInt(dimensions.strip()));
            return new ProviderModelSpec(id, name, purposes, size, images);
        }

        String label() {
            return id.equals(name) ? id : name + " · " + id;
        }
    }
}
