package com.javaclaw.server.turn;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CoreTools;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.ToolCallRequest;
import com.javaclaw.api.ToolCallResult;
import com.javaclaw.api.ToolCatalogSnapshot;
import com.javaclaw.api.ToolDescriptor;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ToolRpcContracts;
import com.javaclaw.runtime.ToolExecutionOutcome;

/** 工具目录的稳定搜索和版本计算；不读取扩展或权限状态。 */
final class ToolCatalogQueries {
    private ToolCatalogQueries() {}

    static ToolExecutionOutcome searchFrozen(
            CanonicalJson json, ToolCallRequest request, ToolCatalogSnapshot snapshot) {
        ToolRpcContracts.SearchArguments arguments =
                json.decode(request.arguments(), ToolRpcContracts.SearchArguments.class);
        List<ToolDescriptor> found = search(snapshot.tools(), arguments).stream()
                .filter(tool -> !tool.identity().equals(CoreTools.search().identity()))
                .toList();
        ToolCallResult result = new ToolCallResult(
                request.callId(),
                true,
                json.encode(new ToolRpcContracts.SearchResult(snapshot.catalogRevision(), found)),
                Optional.empty());
        return new ToolExecutionOutcome(result, found);
    }

    /** @return 名称、说明或标签匹配的有界稳定切片 */
    static List<ToolDescriptor> search(List<ToolDescriptor> tools, ToolRpcContracts.SearchArguments arguments) {
        String normalized =
                Objects.requireNonNull(arguments, "arguments").query().toLowerCase(Locale.ROOT);
        return List.copyOf(tools).stream()
                .filter(tool -> matches(tool, normalized))
                .limit(arguments.limit())
                .toList();
    }

    /** @return 覆盖完整可执行目录和有效权限的正整数版本 */
    static long revision(CanonicalJson json, List<ToolDescriptor> tools, PermissionProfile permissions) {
        String digest = Objects.requireNonNull(json, "json")
                .encode(new CatalogFingerprint(List.copyOf(tools), Objects.requireNonNull(permissions, "permissions")))
                .sha256();
        return Long.parseLong(digest.substring(0, 15), 16) + 1;
    }

    private static boolean matches(ToolDescriptor tool, String query) {
        return tool.identity().name().toLowerCase(Locale.ROOT).contains(query)
                || tool.description().toLowerCase(Locale.ROOT).contains(query)
                || tool.tags().stream().anyMatch(tag -> tag.contains(query));
    }

    private record CatalogFingerprint(List<ToolDescriptor> tools, PermissionProfile permissions) {}
}
