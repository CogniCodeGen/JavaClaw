package com.javaclaw.platform.http;

import java.net.URI;
import java.net.http.HttpHeaders;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/** HTTP 响应的不可变值对象。 */
public record HttpResult(URI uri, int statusCode, HttpHeaders headers, byte[] body) {

    public HttpResult {
        body = body == null ? new byte[0] : body.clone();
    }

    @Override
    public byte[] body() {
        return body.clone();
    }

    public String bodyText() {
        return bodyText(StandardCharsets.UTF_8);
    }

    public String bodyText(Charset charset) {
        return new String(body, charset);
    }

    public boolean isSuccessful() {
        return statusCode >= 200 && statusCode < 300;
    }
}
