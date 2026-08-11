package com.javaclaw.application.onboarding;

import java.io.IOException;
import java.net.URI;

/** 阻塞式 HTTP 可达性探测端口；中断必须取消当前请求并向上传播。 */
public interface ConnectionProbePort {

    int status(URI uri) throws IOException, InterruptedException;
}
