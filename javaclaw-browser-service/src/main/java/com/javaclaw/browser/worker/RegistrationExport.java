package com.javaclaw.browser.worker;

import java.util.Arrays;

import com.javaclaw.browser.protocol.BrowserRegistrationProtocol;

/** 一次完成命令拥有的私有载荷；发送者用完副本后清零，关闭本对象清零所有者缓冲区。 */
final class RegistrationExport implements AutoCloseable {
    private final BrowserRegistrationProtocol.PrivateResult metadata;
    private final byte[] bytes;

    RegistrationExport(BrowserRegistrationProtocol.PrivateResult metadata, byte[] state, byte[] credentials) {
        this.metadata = metadata;
        bytes = Arrays.copyOf(state, state.length + credentials.length);
        System.arraycopy(credentials, 0, bytes, state.length, credentials.length);
    }

    BrowserRegistrationProtocol.PrivateResult metadata() {
        return metadata;
    }

    byte[] bytes() {
        return bytes.clone();
    }

    @Override
    public void close() {
        Arrays.fill(bytes, (byte) 0);
    }
}
