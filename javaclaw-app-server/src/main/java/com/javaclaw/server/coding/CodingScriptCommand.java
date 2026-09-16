package com.javaclaw.server.coding;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.SandboxCommand;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.ToolchainRef;
import com.javaclaw.nativehost.sandbox.SandboxRuntimeAccess;
import com.javaclaw.protocol.CanonicalJson;

/** JShell 的已解析命令；源码与 Worker 摘要属于启动证据，stdout 不参与状态判定。 */
final class CodingScriptCommand implements CodingResolvedCommand {
    private final ManagedCommandResolver.Resolved owner;
    private final SandboxCommand command;
    private final SandboxRuntimeAccess access;
    private final CanonicalPayload evidence;

    CodingScriptCommand(
            ManagedCommandResolver.Resolved owner,
            SandboxCommand command,
            SandboxRuntimeAccess access,
            String helperDigest,
            byte[] source,
            ToolchainRef jdk) {
        this.owner = owner;
        this.command = command;
        this.access = access;
        evidence = new CanonicalJson().encode(new Evidence("jshell", sha256(source), source.length, helperDigest, jdk));
    }

    @Override
    public SandboxCommand command() {
        return command;
    }

    @Override
    public PermissionProfile permission() {
        return owner.permission();
    }

    @Override
    public SandboxRuntimeAccess access() {
        return access;
    }

    @Override
    public Optional<CanonicalPayload> evidence() {
        return Optional.of(evidence);
    }

    @Override
    public void close() {
        owner.close();
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    private record Evidence(
            String runtime, String sourceSha256, int sourceBytes, String helperSha256, ToolchainRef jdk) {}
}
