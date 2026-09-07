package com.javaclaw.server.coding;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.function.Consumer;

import com.javaclaw.api.SandboxFrame;
import com.javaclaw.api.SandboxResult;
import com.javaclaw.server.persistence.CodingCommandStreamRepository;

/** 两个原生排空线程共用的同步追加器；提交形成背压，禁止中断正在持有 H2 连接的线程。 */
final class CodingCommandStreamCollector implements Consumer<SandboxFrame> {
    private final CodingCommandStreamRepository repository;
    private final MessageDigest stdout = digest();
    private final MessageDigest stderr = digest();
    private CodingCommandStreamRepository.Snapshot owner;
    private boolean completed;

    CodingCommandStreamCollector(
            CodingCommandStreamRepository repository, CodingCommandStreamRepository.Snapshot owner) {
        this.repository = repository;
        this.owner = owner;
    }

    @Override
    public synchronized void accept(SandboxFrame frame) {
        if (completed || !java.util.Set.of("stdout", "stderr").contains(frame.channel())) {
            throw new IllegalStateException("命令输出观察器已结束或通道无效");
        }
        byte[] bytes = frame.bytes();
        if (bytes.length > owner.maximumBytes() - owner.outputBytes()) {
            throw new IllegalStateException("原生输出超过冻结保留预算");
        }
        for (int offset = 0; offset < bytes.length; offset += 65_536) {
            byte[] part = Arrays.copyOfRange(bytes, offset, Math.min(bytes.length, offset + 65_536));
            owner = repository.append(owner, frame.channel(), part);
            (frame.channel().equals("stdout") ? stdout : stderr).update(part);
        }
    }

    synchronized void complete(SandboxResult result) {
        if (completed
                || !Arrays.equals(stdout.digest(), digest().digest(result.standardOutput()))
                || !Arrays.equals(stderr.digest(), digest().digest(result.standardError()))) {
            throw new IllegalStateException("增量输出与原生最终保留结果不一致");
        }
        repository.complete(owner, CodingProcessManager.state(result).name(), result.exitCode());
        completed = true;
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 不可用", failure);
        }
    }
}
