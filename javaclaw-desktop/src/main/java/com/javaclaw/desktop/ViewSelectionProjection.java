package com.javaclaw.desktop;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.desktop.view.ViewLoadRequest;
import com.javaclaw.extension.spi.ViewInitialSelection;
import com.javaclaw.protocol.CanonicalJson;

/** 按页面显式选择、版本化初选、旧主从默认顺序投影行选择，不改变命令绑定优先级。 */
final class ViewSelectionProjection {
    private static final CanonicalJson JSON = new CanonicalJson();

    private ViewSelectionProjection() {}

    static Optional<String> select(
            String sourceId,
            String keyField,
            List<Map<String, Object>> rows,
            Map<String, Object> values,
            ViewLoadRequest request,
            boolean master) {
        if (keyField == null) {
            return Optional.empty();
        }
        if (request.selectionInitialized(sourceId)) {
            return request.selectedKey(sourceId)
                    .filter(key -> rows.stream().anyMatch(row -> key.equals(text(row.get(keyField)))));
        }
        if (request.initialSelections().containsKey(sourceId)) {
            return initial(sourceId, keyField, rows, request.initialSelections().get(sourceId));
        }
        if (values.containsKey(ViewInitialSelection.VALUES_KEY)) {
            return initial(sourceId, keyField, rows, values.get(ViewInitialSelection.VALUES_KEY));
        }
        return master
                ? rows.stream()
                        .map(row -> text(row.get(keyField)))
                        .filter(key -> !key.isBlank())
                        .findFirst()
                : Optional.empty();
    }

    private static Optional<String> initial(
            String sourceId, String keyField, List<Map<String, Object>> rows, Object metadata) {
        try {
            var payload =
                    metadata instanceof com.javaclaw.api.CanonicalPayload canonical ? canonical : JSON.encode(metadata);
            ViewInitialSelection hint = JSON.decode(payload, ViewInitialSelection.class);
            if (!sourceId.equals(hint.dataSourceId())) {
                return Optional.empty();
            }
            return rows.stream()
                    .filter(row -> hint.key().equals(text(row.get(keyField))))
                    .filter(row -> exactRevision(row.get(hint.revisionField()), hint.revision()))
                    .findFirst()
                    .map(row -> hint.key());
        } catch (IllegalArgumentException | com.javaclaw.protocol.ProtocolException invalidMetadata) {
            // 元数据只是展示提示：未知版本和损坏提示不能使旧页面不可用，也不能悄悄选取最新引用。
            return Optional.empty();
        }
    }

    private static boolean exactRevision(Object value, long revision) {
        return value instanceof Number && Long.toString(revision).equals(value.toString());
    }

    private static String text(Object value) {
        return Objects.toString(value, "");
    }
}
