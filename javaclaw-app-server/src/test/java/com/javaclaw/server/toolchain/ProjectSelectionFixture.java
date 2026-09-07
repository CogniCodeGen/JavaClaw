package com.javaclaw.server.toolchain;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.Environment;
import com.javaclaw.nativehost.coding.WorkspaceFileAccess;
import com.javaclaw.server.TurnContractFixtures;

/** 生成包含缺失文件证据的受限读取替身，独立于声明解析结果。 */
final class ProjectSelectionFixture {
    private ProjectSelectionFixture() {}

    static CodingEnvironmentSelection select(Map<String, String> content, Environment environment) {
        return select((root, permission) -> snapshots(content), environment);
    }

    static CodingEnvironmentSelection select(
            ProjectToolchainSelector.DeclarationReader reader, Environment environment) {
        return new ProjectToolchainSelector(CodingToolchainCatalog.bundled(), reader)
                .select(Path.of("."), TurnContractFixtures.TOOL_CATALOG.permissionCeiling(), environment);
    }

    static Map<String, WorkspaceFileAccess.Snapshot> snapshots(Map<String, String> content) throws Exception {
        var snapshots = new HashMap<String, WorkspaceFileAccess.Snapshot>();
        for (String path : ProjectToolchainDeclarations.paths()) {
            byte[] bytes = content.getOrDefault(path, "").getBytes(StandardCharsets.UTF_8);
            boolean exists = content.containsKey(path);
            String digest = exists
                    ? HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
                    : "";
            snapshots.put(path, new WorkspaceFileAccess.Snapshot(path, exists, digest, bytes));
        }
        return snapshots;
    }
}
