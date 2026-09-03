package com.javaclaw.model;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import com.javaclaw.api.CancellationToken;

/** 为模型目录发现提供拒绝重定向、限制响应大小的 SDK HTTP 边界。 */
final class DiscoveryHttpClients {
    static final long MAXIMUM_RESPONSE_BYTES = 8L * 1024L * 1024L;
    static final int MAXIMUM_REQUESTS = 20;

    private DiscoveryHttpClients() {}

    static com.openai.core.http.HttpClient openAi(
            Duration timeout, boolean stripAuthorization, CancellationToken cancellation) {
        return new OpenAiHttpClient(http(timeout, cancellation), stripAuthorization, cancellation);
    }

    static com.anthropic.core.http.HttpClient anthropic(
            Duration timeout, String baseUrl, CancellationToken cancellation) {
        return new AnthropicHttpClient(http(timeout, cancellation), baseUrl, cancellation);
    }

    static OkHttpClient google(Duration timeout, CancellationToken cancellation) {
        return http(timeout, cancellation);
    }

    private static OkHttpClient http(Duration timeout, CancellationToken cancellation) {
        CancellationToken checkedCancellation = Objects.requireNonNull(cancellation, "cancellation");
        DiscoveryRequestBudget budget = DiscoveryRequestBudget.start(timeout, MAXIMUM_REQUESTS);
        return new OkHttpClient.Builder()
                .connectTimeout(timeout)
                .readTimeout(timeout)
                .writeTimeout(timeout)
                .callTimeout(timeout)
                .followRedirects(false)
                .followSslRedirects(false)
                .addInterceptor(chain -> enforceRequestPolicy(chain, budget, checkedCancellation))
                .build();
    }

    private static Response enforceRequestPolicy(
            Interceptor.Chain chain, DiscoveryRequestBudget budget, CancellationToken cancellation) throws IOException {
        cancellation.throwIfCancelled();
        int boundedMillis = budget.acquireTimeoutMillis();
        Interceptor.Chain bounded = chain.withConnectTimeout(boundedMillis, TimeUnit.MILLISECONDS)
                .withReadTimeout(boundedMillis, TimeUnit.MILLISECONDS)
                .withWriteTimeout(boundedMillis, TimeUnit.MILLISECONDS);
        Thread watcher = cancellationWatcher(chain.call(), cancellation);
        try {
            return enforceResponsePolicy(bounded.proceed(chain.request()));
        } catch (IOException failure) {
            cancellation.throwIfCancelled();
            throw failure;
        } finally {
            watcher.interrupt();
        }
    }

    private static Thread cancellationWatcher(okhttp3.Call call, CancellationToken cancellation) {
        return Thread.ofVirtual().name("provider-discovery-cancellation").start(() -> {
            while (!Thread.currentThread().isInterrupted() && !cancellation.isCancelled()) {
                java.util.concurrent.locks.LockSupport.parkNanos(
                        Duration.ofMillis(5).toNanos());
            }
            if (cancellation.isCancelled()) {
                call.cancel();
            }
        });
    }

    private static Response enforceResponsePolicy(Response response) throws IOException {
        if (response.isRedirect()) {
            response.close();
            throw new IOException("Provider model discovery redirect is not allowed");
        }
        ResponseBody body = response.body();
        if (body == null) {
            return response;
        }
        if (body.contentLength() > MAXIMUM_RESPONSE_BYTES) {
            response.close();
            throw new IOException("Provider model discovery response exceeds byte limit");
        }
        MediaType contentType = body.contentType();
        byte[] bytes;
        try (InputStream input = body.byteStream()) {
            bytes = readBounded(input);
        } catch (IOException failure) {
            response.close();
            throw failure;
        }
        return response.newBuilder()
                .body(ResponseBody.create(contentType, bytes))
                .build();
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8_192];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            total += read;
            if (total > MAXIMUM_RESPONSE_BYTES) {
                throw new IOException("Provider model discovery response exceeds byte limit");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static Request request(
            String url,
            String method,
            java.util.Set<String> headerNames,
            java.util.function.Function<String, java.util.List<String>> headerValues,
            boolean stripAuthorization) {
        if (!"GET".equals(method)) {
            throw new IllegalArgumentException("Provider model discovery transport only accepts GET");
        }
        Request.Builder builder = new Request.Builder().url(url).get();
        for (String name : headerNames) {
            if (stripAuthorization && name.equalsIgnoreCase("Authorization")) {
                continue;
            }
            for (String value : headerValues.apply(name)) {
                builder.addHeader(name, value);
            }
        }
        return builder.build();
    }

    private static final class OpenAiHttpClient implements com.openai.core.http.HttpClient {
        private final OkHttpClient client;
        private final boolean stripAuthorization;
        private final CancellationToken cancellation;

        private OpenAiHttpClient(OkHttpClient client, boolean stripAuthorization, CancellationToken cancellation) {
            this.client = client;
            this.stripAuthorization = stripAuthorization;
            this.cancellation = cancellation;
        }

        @Override
        public com.openai.core.http.HttpResponse execute(
                com.openai.core.http.HttpRequest request, com.openai.core.RequestOptions options) {
            Objects.requireNonNull(options, "options");
            try {
                Request outbound = request(
                        request.url(),
                        request.method().name(),
                        request.headers().names(),
                        request.headers()::values,
                        stripAuthorization);
                return new OpenAiResponse(client.newCall(outbound).execute());
            } catch (IOException failure) {
                cancellation.throwIfCancelled();
                throw new com.openai.errors.OpenAIIoException("Provider model discovery request failed", failure);
            }
        }

        @Override
        public CompletableFuture<com.openai.core.http.HttpResponse> executeAsync(
                com.openai.core.http.HttpRequest request, com.openai.core.RequestOptions options) {
            return CompletableFuture.supplyAsync(() -> execute(request, options));
        }

        @Override
        public void close() {
            DiscoveryHttpClients.close(client);
        }
    }

    private static final class AnthropicHttpClient implements com.anthropic.core.http.HttpClient {
        private final OkHttpClient client;
        private final String baseUrl;
        private final CancellationToken cancellation;

        private AnthropicHttpClient(OkHttpClient client, String baseUrl, CancellationToken cancellation) {
            this.client = client;
            this.baseUrl = stripTrailingSlash(Objects.requireNonNull(baseUrl, "baseUrl"));
            this.cancellation = cancellation;
        }

        @Override
        public com.anthropic.core.http.HttpResponse execute(
                com.anthropic.core.http.HttpRequest request, com.anthropic.core.RequestOptions options) {
            Objects.requireNonNull(options, "options");
            try {
                Request outbound = request(
                        resolvedUrl(request.url()),
                        request.method().name(),
                        request.headers().names(),
                        request.headers()::values,
                        false);
                return new AnthropicResponse(client.newCall(outbound).execute());
            } catch (IOException failure) {
                cancellation.throwIfCancelled();
                throw new com.anthropic.errors.AnthropicIoException("Provider model discovery request failed", failure);
            }
        }

        @Override
        public CompletableFuture<com.anthropic.core.http.HttpResponse> executeAsync(
                com.anthropic.core.http.HttpRequest request, com.anthropic.core.RequestOptions options) {
            return CompletableFuture.supplyAsync(() -> execute(request, options));
        }

        @Override
        public void close() {
            DiscoveryHttpClients.close(client);
        }

        private String resolvedUrl(String requestUrl) {
            if (requestUrl.startsWith("null/")) {
                return baseUrl + requestUrl.substring("null".length());
            }
            return requestUrl;
        }
    }

    private record OpenAiResponse(Response delegate) implements com.openai.core.http.HttpResponse {
        private OpenAiResponse {
            Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public int statusCode() {
            return delegate.code();
        }

        @Override
        public com.openai.core.http.Headers headers() {
            com.openai.core.http.Headers.Builder headers = com.openai.core.http.Headers.builder();
            delegate.headers().names().forEach(name -> headers.put(name, delegate.headers(name)));
            return headers.build();
        }

        @Override
        public InputStream body() {
            return delegate.body().byteStream();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    private record AnthropicResponse(Response delegate) implements com.anthropic.core.http.HttpResponse {
        private AnthropicResponse {
            Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public int statusCode() {
            return delegate.code();
        }

        @Override
        public com.anthropic.core.http.Headers headers() {
            com.anthropic.core.http.Headers.Builder headers = com.anthropic.core.http.Headers.builder();
            delegate.headers().names().forEach(name -> headers.put(name, delegate.headers(name)));
            return headers.build();
        }

        @Override
        public InputStream body() {
            return delegate.body().byteStream();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    static void close(OkHttpClient client) {
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
        if (client.cache() != null) {
            try {
                client.cache().close();
            } catch (IOException failure) {
                System.getLogger(DiscoveryHttpClients.class.getName())
                        .log(System.Logger.Level.WARNING, "Failed to close discovery HTTP cache", failure);
            }
        }
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
