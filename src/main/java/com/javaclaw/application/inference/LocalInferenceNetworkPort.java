package com.javaclaw.application.inference;

import java.util.Optional;

/** 为快速设置解析可安全绑定的本机私有网卡与空闲端口。 */
public interface LocalInferenceNetworkPort {

    Optional<String> preferredPrivateIpv4();

    boolean portAvailable(String bindAddress, int port);
}
